package com.netessx.perdpi;

import android.graphics.drawable.Drawable;

public final class AppEntry {

    public final String packageName;
    public final String label;
    public final Drawable icon;
    public int dpi;

    public AppEntry(String packageName, String label, Drawable icon, int dpi) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.dpi = dpi;
    }
}
