package com.netessx.perdpi;

import android.Manifest;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_SHIZUKU = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    private DpiStore store;
    private AppListAdapter adapter;
    private final List<AppEntry> allApps = new ArrayList<>();

    private EditText searchInput;
    private RecyclerView appList;
    private View loading;
    private View emptyState;
    private TextView emptyText;
    private TextView appsCount;
    private TextView systemDpiButton;

    private MaterialToolbar toolbar;
    private View statusCard;
    private View statusDot;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView currentState;
    private MaterialButton btnOpenShizuku;
    private MaterialButton btnGrant;
    private MaterialButton btnService;

    private boolean appsLoaded;
    private boolean pendingStart;
    private boolean receiverRegistered;
    private boolean shizukuListenersRegistered;
    private boolean watchUi;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_SHIZUKU) {
                    return;
                }
                runOnUiThread(() -> {
                    refreshStatus();
                    toast(grantResult == PackageManager.PERMISSION_GRANTED
                            ? R.string.toast_granted
                            : R.string.toast_denied);
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        maybeAutoStartService();
                    }
                });
            };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(() -> {
                refreshStatus();
                maybeAutoStartService();
            });

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::refreshStatus);

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(DpiService.EXTRA_STATE);
            String message = intent.getStringExtra(DpiService.EXTRA_MESSAGE);
            refreshStatus();
            if (DpiService.STATE_ERROR.equals(state)) {
                toast(message != null ? message : getString(R.string.toast_restore_failed));
            } else if (DpiService.STATE_STOPPED.equals(state)) {
                toast(intent.getBooleanExtra(DpiService.EXTRA_RESTORED, false)
                        ? R.string.toast_restored
                        : R.string.toast_service_stopped);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        watchUi = Math.min(metrics.widthPixels, metrics.heightPixels) <= 600;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(watchUi ? R.layout.activity_main_watch : R.layout.activity_main);

        store = new DpiStore(this);
        bindViews();
        applyWindowInsets();
        setupToolbar();
        setupList();
        setupActions();
        registerStateReceiver();
        registerShizukuListeners();
        refreshStatus();
        loadApps();
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        statusCard = findViewById(R.id.statusCard);
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        currentState = findViewById(R.id.currentState);
        btnOpenShizuku = findViewById(R.id.btnOpenShizuku);
        btnGrant = findViewById(R.id.btnGrant);
        btnService = findViewById(R.id.btnService);
        searchInput = findViewById(R.id.searchInput);
        appsCount = findViewById(R.id.appsCount);
        systemDpiButton = findViewById(R.id.systemDpiButton);
        appList = findViewById(R.id.appList);
        loading = findViewById(R.id.loading);
        emptyState = findViewById(R.id.emptyState);
        emptyText = findViewById(R.id.emptyText);
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(
                    initialLeft + bars.left,
                    initialTop + bars.top,
                    initialRight + bars.right,
                    initialBottom + Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupToolbar() {
        toolbar.inflateMenu(watchUi ? R.menu.menu_watch : R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_search) {
                toggleSearch();
                return true;
            }
            if (id == R.id.action_refresh) {
                loadApps();
                return true;
            }
            if (id == R.id.action_system_dpi) {
                showSystemDpiDialog();
                return true;
            }
            if (id == R.id.action_reset) {
                confirmReset();
                return true;
            }
            if (id == R.id.action_stop) {
                stopServiceFlow();
                return true;
            }
            if (id == R.id.action_help) {
                showHelp();
                return true;
            }
            return false;
        });
    }

    private void toggleSearch() {
        boolean show = searchInput.getVisibility() != View.VISIBLE;
        searchInput.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (show) {
            searchInput.requestFocus();
            if (imm != null) {
                imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            searchInput.setText("");
            searchInput.clearFocus();
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        }
    }

    private void updateStopMenu(boolean running) {
        if (toolbar.getMenu() == null) {
            return;
        }
        MenuItem stop = toolbar.getMenu().findItem(R.id.action_stop);
        if (stop != null) {
            stop.setVisible(running);
        }
    }

    private void setupList() {
        adapter = new AppListAdapter(this::showDpiDialog,
                watchUi ? R.layout.item_app_watch : R.layout.item_app);
        appList.setAdapter(adapter);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s.toString());
            }
        });
    }

    private void setupActions() {
        btnOpenShizuku.setOnClickListener(v -> openShizuku());
        btnGrant.setOnClickListener(v -> requestShizukuPermission());
        btnService.setOnClickListener(v -> {
            if (DpiService.isRunning) {
                stopServiceFlow();
            } else {
                startServiceFlow();
            }
        });
        if (systemDpiButton != null) {
            systemDpiButton.setOnClickListener(v -> showSystemDpiDialog());
        }
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(DpiService.ACTION_STATE);
        ContextCompat.registerReceiver(this, stateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void registerShizukuListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            shizukuListenersRegistered = true;
        } catch (Throwable ignored) {
            shizukuListenersRegistered = false;
        }
    }

    private void refreshStatus() {
        boolean binderAlive = ShizukuUtils.isBinderAlive();
        boolean running = DpiService.isRunning;
        if (statusCard != null) {
            statusCard.setVisibility(watchUi && running ? View.GONE : View.VISIBLE);
        }
        updateDefaultDpiLabel();
        statusDetail.setVisibility(View.VISIBLE);
        updateStopMenu(running);

        if (!binderAlive) {
            boolean installed = ShizukuUtils.isShizukuInstalled(this);
            if (installed) {
                setStatus(R.string.status_shizuku_not_running_title,
                        R.string.status_shizuku_not_running_detail,
                        R.color.status_warn);
            } else {
                setStatus(R.string.status_shizuku_missing_title,
                        R.string.status_shizuku_missing_detail,
                        R.color.status_error);
            }
            btnOpenShizuku.setText(installed
                    ? R.string.btn_open_shizuku
                    : R.string.btn_get_shizuku);
            btnOpenShizuku.setVisibility(View.VISIBLE);
            btnGrant.setVisibility(View.GONE);
            btnService.setVisibility(View.GONE);
            currentState.setVisibility(View.GONE);
            return;
        }

        boolean oldShizuku;
        try {
            oldShizuku = Shizuku.isPreV11();
        } catch (Throwable t) {
            oldShizuku = true;
        }
        if (oldShizuku) {
            setStatus(R.string.status_shizuku_old_title,
                    R.string.status_shizuku_old_detail,
                    R.color.status_error);
            btnOpenShizuku.setText(R.string.btn_open_shizuku);
            btnOpenShizuku.setVisibility(View.VISIBLE);
            btnGrant.setVisibility(View.GONE);
            btnService.setVisibility(View.GONE);
            currentState.setVisibility(View.GONE);
            return;
        }

        if (!ShizukuUtils.hasPermission()) {
            setStatus(R.string.status_permission_title,
                    R.string.status_permission_detail,
                    R.color.status_warn);
            btnOpenShizuku.setVisibility(View.GONE);
            btnGrant.setVisibility(View.VISIBLE);
            btnService.setVisibility(View.GONE);
            currentState.setVisibility(View.GONE);
            return;
        }

        btnOpenShizuku.setVisibility(View.GONE);
        btnGrant.setVisibility(View.GONE);
        btnService.setVisibility(View.VISIBLE);
        btnService.setText(running ? R.string.btn_stop_service : R.string.btn_start_service);

        if (running) {
            setStatus(R.string.status_service_running_title,
                    R.string.status_service_running_detail,
                    R.color.status_ok);
            updateCurrentState();
            if (watchUi) {
                statusDetail.setVisibility(View.GONE);
                btnService.setVisibility(View.GONE);
            }
        } else {
            setStatus(R.string.status_ready_title,
                    R.string.status_ready_detail,
                    R.color.status_ok);
            currentState.setVisibility(View.GONE);
        }
    }

    private void updateCurrentState() {
        String label = !TextUtils.isEmpty(DpiService.currentLabel)
                ? DpiService.currentLabel
                : DpiService.currentPackage;
        if (TextUtils.isEmpty(label)) {
            currentState.setText(R.string.status_current_waiting);
        } else if (DpiService.currentDpi > 0) {
            currentState.setText(getString(R.string.status_current, label, DpiService.currentDpi));
        } else {
            currentState.setText(getString(R.string.status_current_system, label));
        }
        currentState.setVisibility(View.VISIBLE);
    }

    private void updateDefaultDpiLabel() {
        if (systemDpiButton == null) {
            return;
        }
        int dpi = store.getSystemDpi();
        systemDpiButton.setText(dpi > 0
                ? getString(R.string.system_dpi_compact, dpi)
                : getString(R.string.system_dpi_follow_short));
    }

    private void setStatus(int titleRes, int detailRes, int colorRes) {
        statusTitle.setText(titleRes);
        statusDetail.setText(detailRes);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, colorRes)));
    }

    private void loadApps() {
        loading.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        executor.execute(() -> {
            List<AppEntry> loaded = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN);
                launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
                Map<String, AppEntry> unique = new LinkedHashMap<>();
                for (ResolveInfo info : resolved) {
                    if (info.activityInfo == null) {
                        continue;
                    }
                    String packageName = info.activityInfo.packageName;
                    if (packageName.equals(getPackageName())) {
                        // Scaling our own settings screen only causes confusion.
                        continue;
                    }
                    if (unique.containsKey(packageName)) {
                        continue;
                    }
                    CharSequence label = info.loadLabel(pm);
                    Drawable icon = info.loadIcon(pm);
                    unique.put(packageName, new AppEntry(
                            packageName,
                            label == null ? packageName : label.toString(),
                            icon,
                            store.getDpi(packageName)));
                }
                loaded.addAll(unique.values());
                Collections.sort(loaded, (a, b) -> collator.compare(a.label, b.label));
            } catch (Exception ignored) {
                // Empty state is shown below.
            }

            final List<AppEntry> result = loaded;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                allApps.clear();
                allApps.addAll(result);
                appsLoaded = true;
                loading.setVisibility(View.GONE);
                updateCount();
                applyFilter(searchInput.getText().toString());
            });
        });
    }

    private void applyFilter(String query) {
        if (!appsLoaded) {
            return;
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry entry : allApps) {
            if (needle.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(needle)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(entry);
            }
        }
        adapter.submit(filtered);

        boolean empty = filtered.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyText.setText(allApps.isEmpty() ? R.string.empty_apps : R.string.empty_search);
        }
    }

    private void updateCount() {
        int configured = 0;
        for (AppEntry entry : allApps) {
            if (entry.dpi > 0) {
                configured++;
            }
        }
        if (appsCount != null) {
            appsCount.setText(getString(R.string.apps_count, configured));
        }
    }

    private void showDpiDialog(AppEntry entry) {
        int reference = store.getSystemDpi() > 0
                ? store.getSystemDpi()
                : physicalDensity();
        showDensityDialog(entry.label, entry.icon, entry.packageName, entry.dpi,
                reference, R.string.dpi_default, R.string.dpi_following_system,
                R.string.chip_system, dpi -> saveDpi(entry, dpi));
    }

    private void showSystemDpiDialog() {
        showDensityDialog(getString(R.string.system_dpi_title), null, null,
                store.getSystemDpi(), physicalDensity(),
                R.string.dpi_physical, R.string.dpi_following_physical,
                R.string.system_dpi_follow_chip, this::saveSystemDpi);
    }

    private int physicalDensity() {
        int base = store.getBaseDensity();
        if (base > 0) {
            return base;
        }
        int dpi = getResources().getDisplayMetrics().densityDpi;
        return dpi > 0 ? dpi : 160;
    }

    /**
     * Shared editor for a per-app override (0 = follow the default) and for the
     * system-wide default itself (0 = follow the physical density).
     */
    private void showDensityDialog(String title, Drawable icon, String appPackage,
                                   int initialDpi, int referenceDpi,
                                   int zeroLabelRes, int zeroSubRes,
                                   int followChipRes, DensityCallback callback) {
        View content = getLayoutInflater().inflate(
                watchUi ? R.layout.dialog_dpi_watch : R.layout.dialog_dpi, null);
        ImageView iconView = content.findViewById(R.id.dialogIcon);
        TextView name = content.findViewById(R.id.dialogName);
        TextView packageView = content.findViewById(R.id.dialogPackage);
        TextView value = content.findViewById(R.id.dpiValue);
        TextView sub = content.findViewById(R.id.dpiSub);
        Slider slider = content.findViewById(R.id.dpiSlider);
        ChipGroup group = content.findViewById(R.id.chipGroup);
        Chip chipSystem = content.findViewById(R.id.chipSystem);
        Chip chip360 = content.findViewById(R.id.chip360);
        Chip chip480 = content.findViewById(R.id.chip480);
        Chip chip560 = content.findViewById(R.id.chip560);
        Chip chip640 = content.findViewById(R.id.chip640);

        if (iconView != null) {
            if (icon != null) {
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(View.GONE);
            }
        }
        name.setText(title);
        if (packageView != null) {
            if (appPackage != null) {
                packageView.setText(appPackage);
            } else {
                packageView.setVisibility(View.GONE);
            }
        }
        chipSystem.setText(followChipRes);

        final int baseDpi = referenceDpi > 0 ? referenceDpi : 160;
        final int[] selected = {initialDpi};
        final boolean[] suppress = {false};

        slider.setValue(snapDensity(initialDpi > 0 ? initialDpi : baseDpi));

        if (initialDpi == 0) {
            chipSystem.setChecked(true);
        } else if (initialDpi == 360) {
            chip360.setChecked(true);
        } else if (initialDpi == 480) {
            chip480.setChecked(true);
        } else if (initialDpi == 560) {
            chip560.setChecked(true);
        } else if (initialDpi == 640) {
            chip640.setChecked(true);
        }

        Runnable refresh = () -> {
            int dpi = selected[0];
            if (dpi <= 0) {
                value.setText(zeroLabelRes);
                sub.setText(getString(zeroSubRes, baseDpi));
            } else {
                value.setText(String.valueOf(dpi));
                float ratio = dpi / (float) baseDpi;
                sub.setText(getString(R.string.dpi_ratio, String.format(Locale.US, "%.2f", ratio))
                        + " · " + getString(R.string.dpi_base, baseDpi));
            }
        };

        chipSystem.setOnClickListener(v -> {
            selected[0] = 0;
            group.check(chipSystem.getId());
            suppress[0] = true;
            slider.setValue(snapDensity(baseDpi));
            suppress[0] = false;
            refresh.run();
        });
        chip360.setOnClickListener(v -> selectPreset(group, chip360, slider,
                selected, suppress, 360, refresh));
        chip480.setOnClickListener(v -> selectPreset(group, chip480, slider,
                selected, suppress, 480, refresh));
        chip560.setOnClickListener(v -> selectPreset(group, chip560, slider,
                selected, suppress, 560, refresh));
        chip640.setOnClickListener(v -> selectPreset(group, chip640, slider,
                selected, suppress, 640, refresh));

        slider.addOnChangeListener((seekBar, sliderValue, fromUser) -> {
            if (suppress[0]) {
                return;
            }
            selected[0] = Math.round(sliderValue);
            group.clearCheck();
            refresh.run();
        });

        refresh.run();

        View saveButton = content.findViewById(R.id.dialogSave);
        if (saveButton != null) {
            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(content);
            dialog.setCanceledOnTouchOutside(true);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0.6f);
                window.setGravity(Gravity.CENTER);
            }
            saveButton.setOnClickListener(v -> {
                callback.onPicked(selected[0]);
                dialog.dismiss();
            });
            View cancelButton = content.findViewById(R.id.dialogCancel);
            if (cancelButton != null) {
                cancelButton.setOnClickListener(v -> dialog.dismiss());
            }
            dialog.show();
            if (window != null) {
                int width = getResources().getDisplayMetrics().widthPixels - dp(16);
                window.setLayout(Math.max(dp(120), width),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_pick_dpi)
                    .setView(content)
                    .setPositiveButton(R.string.dialog_save,
                            (dialog, which) -> callback.onPicked(selected[0]))
                    .setNeutralButton(R.string.dialog_reset,
                            (dialog, which) -> callback.onPicked(0))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
        }
    }

    private void saveSystemDpi(int dpi) {
        store.setSystemDpi(dpi);
        updateDefaultDpiLabel();
        toast(dpi > 0
                ? getString(R.string.system_dpi_saved, dpi)
                : getString(R.string.system_dpi_reset_toast));
        if (DpiService.isRunning) {
            DpiService.requestReload(this);
        }
    }

    private interface DensityCallback {
        void onPicked(int dpi);
    }

    private void selectPreset(ChipGroup group, Chip chip, Slider slider,
                              int[] selected, boolean[] suppress, int dpi, Runnable refresh) {
        selected[0] = dpi;
        group.check(chip.getId());
        suppress[0] = true;
        slider.setValue(snapDensity(dpi));
        suppress[0] = false;
        refresh.run();
    }

    private static int snapDensity(int dpi) {
        int clamped = Math.max(100, Math.min(800, dpi));
        return 100 + Math.round((clamped - 100) / 10f) * 10;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveDpi(AppEntry entry, int dpi) {
        store.setDpi(entry.packageName, dpi);
        entry.dpi = dpi;
        adapter.updateDpi(entry.packageName, dpi);
        updateCount();
        toast(dpi > 0
                ? getString(R.string.toast_saved, entry.label)
                : getString(R.string.toast_reset_app, entry.label));
        if (DpiService.isRunning) {
            DpiService.requestReload(this);
        }
    }

    private void startServiceFlow() {
        if (!ShizukuUtils.isReady()) {
            toast(R.string.toast_open_shizuku_first);
            refreshStatus();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        doStartService();
    }

    private void doStartService() {
        try {
            store.setManualStop(false);
            ContextCompat.startForegroundService(this,
                    new Intent(this, DpiService.class).setAction(DpiService.ACTION_START));
            mainHandler.postDelayed(this::refreshStatus, 500);
        } catch (Exception e) {
            toast(getString(R.string.toast_service_failed,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void stopServiceFlow() {
        try {
            store.setManualStop(true);
            startService(new Intent(this, DpiService.class).setAction(DpiService.ACTION_STOP));
            mainHandler.postDelayed(this::refreshStatus, 700);
        } catch (Exception e) {
            toast(R.string.toast_service_stopped);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            toast(R.string.toast_notif_denied);
        }
        if (pendingStart) {
            pendingStart = false;
            doStartService();
        }
    }

    private void requestShizukuPermission() {
        if (!ShizukuUtils.isBinderAlive()) {
            openShizuku();
            return;
        }
        try {
            if (Shizuku.isPreV11()) {
                refreshStatus();
                return;
            }
            Shizuku.requestPermission(REQUEST_SHIZUKU);
        } catch (Throwable t) {
            toast(t.getMessage() == null ? getString(R.string.toast_denied) : t.getMessage());
            refreshStatus();
        }
    }

    private void openShizuku() {
        if (!ShizukuUtils.isShizukuInstalled(this)) {
            showInstallDialog();
            return;
        }
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage(ShizukuUtils.SHIZUKU_PACKAGE);
        if (launch == null) {
            toast(R.string.shizuku_open_failed);
            return;
        }
        try {
            startActivity(launch);
        } catch (Exception e) {
            toast(R.string.shizuku_open_failed);
        }
    }

    private void showInstallDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_missing_title)
                .setMessage(R.string.shizuku_missing_message)
                .setPositiveButton(R.string.shizuku_download,
                        (dialog, which) -> openUrl(getString(R.string.shizuku_url)))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(R.string.shizuku_open_failed);
        }
    }

    private void confirmReset() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset_confirm, (dialog, which) -> performReset())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void performReset() {
        if (!ShizukuUtils.isReady()) {
            toast(R.string.toast_open_shizuku_first);
            return;
        }
        if (DpiService.isRunning) {
            stopServiceFlow();
            return;
        }
        final int base = store.getBaseDensity();
        final boolean had = store.hadBaseOverride();
        executor.execute(() -> {
            try {
                if (base > 0) {
                    ShizukuUtils.restoreDensity(base, had);
                } else {
                    ShizukuUtils.runCommand("wm density reset");
                }
                store.setCleanShutdown(true);
                mainHandler.post(() -> toast(R.string.toast_restored));
            } catch (Exception e) {
                mainHandler.post(() -> toast(R.string.toast_restore_failed));
            }
        });
    }

    private void showHelp() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        maybeAutoStartService();
    }

    /**
     * Opening the app is the user's intent to use per-app DPI, so start the
     * service automatically. Do not override an explicit "stop".
     */
    private void maybeAutoStartService() {
        if (DpiService.isRunning || store.isManualStop() || !ShizukuUtils.isReady()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        doStartService();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Exception ignored) {
            }
        }
        if (shizukuListenersRegistered) {
            try {
                Shizuku.removeRequestPermissionResultListener(permissionListener);
                Shizuku.removeBinderReceivedListener(binderReceivedListener);
                Shizuku.removeBinderDeadListener(binderDeadListener);
            } catch (Throwable ignored) {
            }
        }
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
