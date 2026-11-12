package com.netessx.perdpi;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/** Small wrapper around the Shizuku shell entry points used by PerDPI. */
public final class ShizukuUtils {

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static final Pattern PHYSICAL_DENSITY =
            Pattern.compile("Physical density:\\s*(\\d+)");

    private ShizukuUtils() {
    }

    public static final class DensityInfo {
        public final int density;
        public final boolean hadOverride;

        DensityInfo(int density, boolean hadOverride) {
            this.density = density;
            this.hadOverride = hadOverride;
        }
    }

    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when Shizuku is running, recent enough, and this app is authorized. */
    public static boolean isReady() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Starts a process with shell (adb) uid through Shizuku.
     * Caller must ensure {@link #isReady()}.
     */
    public static Process newProcess(String[] command) throws IOException {
        if (!isReady()) {
            throw new IOException("Shizuku 未连接或未授权");
        }
        try {
            return Shizuku.newProcess(command, null, null);
        } catch (RuntimeException e) {
            throw new IOException("无法启动 Shizuku 进程", e);
        }
    }

    /**
     * Runs a short shell command and captures combined stdout/stderr.
     *
     * <p>We deliberately avoid waiting on the process object here:
     * Shizuku's remote process implements waitFor() by calling exitValue(), which
     * throws "process hasn't exited" while the shell is still winding down.
     * Reading stdout until EOF already means the shell finished; an explicit
     * exit marker tells us the real status without polling the process.</p>
     */
    public static CommandResult runCommand(String command) {
        final String marker = "__PERDPI_EXIT__:";
        Process process = null;
        try {
            String script = command + " 2>&1; echo " + marker + "$?";
            process = newProcess(new String[]{"sh", "-c", script});
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }

            String text = output.toString();
            int exitCode = -1;
            int markerIndex = text.lastIndexOf(marker);
            if (markerIndex >= 0) {
                String tail = text.substring(markerIndex + marker.length()).trim();
                try {
                    exitCode = Integer.parseInt(tail);
                } catch (NumberFormatException ignored) {
                }
                text = text.substring(0, markerIndex).trim();
            }
            return new CommandResult(exitCode, text);
        } catch (Exception e) {
            return new CommandResult(-1, e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * Reads the factory physical density. We deliberately ignore any existing
     * "Override density" here: an override may be a leftover from an earlier
     * run or another tool, and treating it as the baseline can permanently
     * poison the restore value. The watcher applies its own base explicitly.
     */
    public static DensityInfo captureDensity() throws IOException {
        CommandResult result = runCommand("wm density");
        if (result.exitCode != 0) {
            throw new IOException("读取显示密度失败：" + result.output.trim());
        }
        Matcher physical = PHYSICAL_DENSITY.matcher(result.output);
        if (physical.find()) {
            return new DensityInfo(Integer.parseInt(physical.group(1)), false);
        }
        throw new IOException("无法解析显示密度：" + result.output.trim());
    }

    /** Restores the density snapshot captured when the service started. */
    public static void restoreDensity(int baseDensity, boolean hadOverride) throws IOException {
        String command = hadOverride && baseDensity > 0
                ? "wm density " + baseDensity
                : "wm density reset";
        CommandResult result = runCommand(command);
        if (result.exitCode != 0) {
            throw new IOException("恢复显示密度失败：" + result.output.trim());
        }
    }
}
