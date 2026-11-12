package com.netessx.perdpi;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent per-app density settings, plus the display density snapshot needed
 * to undo our own changes when the watcher stops unexpectedly.
 */
public final class DpiStore {

    private static final String PREFS_NAME = "perdpi";
    private static final String KEY_DPI_PREFIX = "dpi:";
    private static final String KEY_SYSTEM_DPI = "system_dpi";
    private static final String KEY_BASE_DENSITY = "base_density";
    private static final String KEY_BASE_OVERRIDE = "base_had_override";
    private static final String KEY_CLEAN_SHUTDOWN = "clean_shutdown";
    private static final String KEY_MANUAL_STOP = "manual_stop";

    private final SharedPreferences prefs;

    public DpiStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** @return configured density, or 0 when the app should follow the system. */
    public int getDpi(String packageName) {
        return prefs.getInt(KEY_DPI_PREFIX + packageName, 0);
    }

    public void setDpi(String packageName, int dpi) {
        if (dpi <= 0) {
            prefs.edit().remove(KEY_DPI_PREFIX + packageName).apply();
        } else {
            prefs.edit().putInt(KEY_DPI_PREFIX + packageName, dpi).apply();
        }
    }

    /** Configured apps only, ordered by package name. */
    public Map<String, Integer> getAllDpis() {
        Map<String, Integer> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_DPI_PREFIX) || !(entry.getValue() instanceof Number)) {
                continue;
            }
            int dpi = ((Number) entry.getValue()).intValue();
            if (dpi > 0) {
                result.put(key.substring(KEY_DPI_PREFIX.length()), dpi);
            }
        }
        return result;
    }

    /**
     * Density used for apps without an explicit override. Zero means "follow the
     * physical display density".
     */
    public int getSystemDpi() {
        return prefs.getInt(KEY_SYSTEM_DPI, 0);
    }

    public void setSystemDpi(int dpi) {
        if (dpi <= 0) {
            prefs.edit().remove(KEY_SYSTEM_DPI).apply();
        } else {
            prefs.edit().putInt(KEY_SYSTEM_DPI, dpi).apply();
        }
    }

    public boolean hasBaseDensity() {
        return prefs.getInt(KEY_BASE_DENSITY, 0) > 0;
    }

    public int getBaseDensity() {
        return prefs.getInt(KEY_BASE_DENSITY, 0);
    }

    public boolean hadBaseOverride() {
        return prefs.getBoolean(KEY_BASE_OVERRIDE, false);
    }

    public void saveBaseDensity(int density, boolean hadOverride) {
        prefs.edit()
                .putInt(KEY_BASE_DENSITY, density)
                .putBoolean(KEY_BASE_OVERRIDE, hadOverride)
                .apply();
    }

    public boolean isCleanShutdown() {
        return prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true);
    }

    public void setCleanShutdown(boolean clean) {
        prefs.edit().putBoolean(KEY_CLEAN_SHUTDOWN, clean).apply();
    }

    /** True after the user explicitly stopped the service via the UI. */
    public boolean isManualStop() {
        return prefs.getBoolean(KEY_MANUAL_STOP, false);
    }

    public void setManualStop(boolean manualStop) {
        prefs.edit().putBoolean(KEY_MANUAL_STOP, manualStop).apply();
    }
}
