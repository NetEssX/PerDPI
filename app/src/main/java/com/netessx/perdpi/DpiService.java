package com.netessx.perdpi;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Keeps a tiny shell loop alive through Shizuku. The loop watches the top
 * resumed activity and runs {@code wm density} when the foreground app changes.
 */
public class DpiService extends Service {

    public static final String ACTION_START = "com.netessx.perdpi.action.START";
    public static final String ACTION_STOP = "com.netessx.perdpi.action.STOP";
    public static final String ACTION_RELOAD = "com.netessx.perdpi.action.RELOAD";

    public static final String ACTION_STATE = "com.netessx.perdpi.action.STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RESTORED = "restored";

    public static final String STATE_STARTED = "started";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_CURRENT = "current";
    public static final String STATE_ERROR = "error";

    public static volatile boolean isRunning;
    public static volatile String currentPackage = "";
    public static volatile String currentLabel = "";
    public static volatile int currentDpi;

    private static final String CHANNEL_ID = "perdpi_status";
    private static final int NOTIFICATION_ID = 0x5044;
    private static final String CONF_PATH = "/data/local/tmp/perdpi.conf";
    private static final String PID_PATH = "/data/local/tmp/perdpi.pid";

    private final Object lock = new Object();
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::onShizukuDead;

    private DpiStore store;
    private ExecutorService io;
    private volatile Process watcher;
    private Thread readerThread;
    private volatile boolean stopping;
    private volatile boolean startScheduled;
    private volatile String lastError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        store = new DpiStore(this);
        io = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        try {
            Shizuku.addBinderDeadListener(binderDeadListener);
        } catch (Throwable ignored) {
            // Shizuku is not available; the start path will report this.
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new DpiStore(this).setManualStop(true);
            stopEverything(true);
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            reloadConfig();
            return START_STICKY;
        }
        handleStart();
        return START_STICKY;
    }

    private void handleStart() {
        new DpiStore(this).setManualStop(false);
        stopping = false;
        isRunning = true;
        startForegroundCompat(buildNotification());
        if (!ShizukuUtils.isReady()) {
            fail(getString(R.string.toast_open_shizuku_first));
            return;
        }
        if (startScheduled) {
            return;
        }
        startScheduled = true;
        io.execute(this::startWatcher);
    }

    private synchronized void startWatcher() {
        if (stopping) {
            return;
        }
        Process previous = watcher;
        if (previous != null && previous.isAlive()) {
            return;
        }
        try {
            // Watchers from earlier app processes may still be alive. The pid
            // file is not reliable after force-stop/update, so find every shell
            // running our script by its command line and terminate it. Its trap
            // restores the old baseline, then we snapshot again.
            ShizukuUtils.runCommand(killWatchersCommand() + "; sleep 0.5");

            // Never inherit an override left by a crashed watcher. Normalize to
            // the physical panel density before taking the new baseline.
            ShizukuUtils.runCommand("wm density reset");
            ShizukuUtils.DensityInfo base = ShizukuUtils.captureDensity();
            store.saveBaseDensity(base.density, base.hadOverride);
            store.setCleanShutdown(false);
            writeConfig(base.density, base.hadOverride);

            String script = readWatcherScript(base.density, base.hadOverride);
            Process process = ShizukuUtils.newProcess(new String[]{"sh", "-c", script});
            watcher = process;
            lastError = "";

            readerThread = new Thread(() -> readWatcher(process), "perdpi-watcher");
            readerThread.start();

            currentPackage = "";
            currentLabel = "";
            currentDpi = 0;
            updateNotification();
            broadcastState(STATE_STARTED, null, false);
        } catch (Exception e) {
            fail(messageOf(e));
        }
    }

    private void readWatcher(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TOP|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length != 3) {
                        continue;
                    }
                    int dpi;
                    try {
                        dpi = Integer.parseInt(parts[2].trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    currentPackage = parts[1];
                    currentLabel = labelFor(parts[1]);
                    currentDpi = dpi;
                    updateNotification();
                    broadcastState(STATE_CURRENT, null, false);
                } else if (line.startsWith("ERR|")) {
                    lastError = line.substring(4).trim();
                }
            }
        } catch (IOException ignored) {
            // Process died; handled below.
        }
        if (!stopping) {
            fail(getString(R.string.notif_shizuku_lost));
        }
    }

    private void reloadConfig() {
        if (!ShizukuUtils.isReady() || store.getBaseDensity() <= 0) {
            return;
        }
        final int base = store.getBaseDensity();
        final boolean had = store.hadBaseOverride();
        io.execute(() -> {
            try {
                writeConfig(base, had);
            } catch (IOException e) {
                lastError = messageOf(e);
            }
        });
    }

    private void writeConfig(int baseDensity, boolean hadOverride) throws IOException {
        StringBuilder config = new StringBuilder();
        config.append("default=").append(store.getSystemDpi()).append('\n');
        if (baseDensity > 0) {
            config.append("#base=").append(baseDensity).append('\n');
            config.append("#had=").append(hadOverride ? 1 : 0).append('\n');
        }
        for (Map.Entry<String, Integer> entry : store.getAllDpis().entrySet()) {
            config.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        String command = "printf '%s' '" + config + "' > " + CONF_PATH + ".tmp"
                + " && chmod 600 " + CONF_PATH + ".tmp"
                + " && mv " + CONF_PATH + ".tmp " + CONF_PATH;
        ShizukuUtils.CommandResult result = ShizukuUtils.runCommand(command);
        if (result.exitCode != 0) {
            throw new IOException("写入配置失败：" + result.output.trim());
        }
    }

    private String readWatcherScript(int baseDensity, boolean hadOverride) throws IOException {
        try (InputStream input = getResources().openRawResource(R.raw.watcher);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace("@BASE@", String.valueOf(baseDensity))
                    .replace("@HAD@", hadOverride ? "1" : "0");
        }
    }

    private static String killWatchersCommand() {
        // Toybox on the watch has no awk. Kill politely first so the watcher's
        // trap can restore density, then SIGKILL anything left (including old
        // builds whose TERM trap did not exit).
        String find = "ps -A -o PID,ARGS 2>/dev/null "
                + "| grep '[p]erdpi.conf' "
                + "| sed -E 's/^ *([0-9]+).*/\\1/'";
        return "for p in $(" + find + "); do kill $p 2>/dev/null; done; "
                + "sleep 0.3; "
                + "for p in $(" + find + "); do kill -9 $p 2>/dev/null; done; "
                + "rm -f " + PID_PATH;
    }

    private void stopEverything(boolean restore) {
        synchronized (lock) {
            if (stopping) {
                return;
            }
            stopping = true;
        }
        isRunning = false;
        io.execute(() -> {
            Process process = watcher;
            watcher = null;
            if (process != null) {
                // ShizukuRemoteProcess.waitFor() can throw IllegalThreadStateException
                // from exitValue() if the remote shell is still winding down. Do not
                // wait on it here; give the trap a moment, then restore ourselves.
                try {
                    process.destroy();
                } catch (RuntimeException ignored) {
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // destroy() only covers the process we know about; reap any orphan
            // watcher left by a previous app process.
            ShizukuUtils.runCommand(killWatchersCommand());

            boolean restored = false;
            if (restore && ShizukuUtils.isReady()) {
                try {
                    if (store.hasBaseDensity()) {
                        ShizukuUtils.restoreDensity(
                                store.getBaseDensity(), store.hadBaseOverride());
                    } else {
                        ShizukuUtils.runCommand("wm density reset");
                    }
                    store.setCleanShutdown(true);
                    restored = true;
                } catch (IOException e) {
                    lastError = messageOf(e);
                }
            }

            currentPackage = "";
            currentLabel = "";
            currentDpi = 0;
            updateNotification();
            broadcastState(STATE_STOPPED, lastError, restored);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void fail(String message) {
        synchronized (lock) {
            if (stopping) {
                return;
            }
            stopping = true;
        }
        isRunning = false;
        startScheduled = false;
        currentPackage = "";
        currentLabel = "";
        currentDpi = 0;
        lastError = message;

        Process process = watcher;
        watcher = null;
        if (process != null) {
            process.destroy();
        }

        broadcastState(STATE_ERROR, message, false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void onShizukuDead() {
        if (stopping) {
            return;
        }
        isRunning = false;
        currentPackage = "";
        currentLabel = "";
        currentDpi = 0;
        Process process = watcher;
        watcher = null;
        if (process != null) {
            process.destroy();
        }
        broadcastState(STATE_ERROR, getString(R.string.notif_shizuku_lost), false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_description));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        String text;
        if (currentDpi > 0 && !currentLabel.isEmpty()) {
            text = getString(R.string.notif_applying, currentLabel, currentDpi);
        } else if (lastError != null && !lastError.isEmpty() && !isRunning) {
            text = getString(R.string.notif_error, lastError);
        } else {
            text = getString(R.string.notif_waiting);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, DpiService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_perdpi)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, getString(R.string.notif_stop), stopPendingIntent)
                .build();
    }

    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void broadcastState(String state, String message, boolean restored) {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        if (message != null) {
            intent.putExtra(EXTRA_MESSAGE, message);
        }
        intent.putExtra(EXTRA_RESTORED, restored);
        sendBroadcast(intent);
    }

    private String labelFor(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    /** Called from the UI after a per-app setting changes. */
    public static void requestReload(Context context) {
        if (!isRunning) {
            return;
        }
        try {
            Intent intent = new Intent(context, DpiService.class).setAction(ACTION_RELOAD);
            context.startService(intent);
        } catch (Exception ignored) {
            // The service will pick the settings up on its next start.
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        isRunning = false;
        Process process = watcher;
        watcher = null;
        if (process != null) {
            process.destroy();
        }
        try {
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Throwable ignored) {
        }
        if (io != null) {
            io.shutdown();
        }
        super.onDestroy();
    }
}
