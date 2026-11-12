package com.netessx.perdpi;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    public interface Listener {
        void onAppClick(AppEntry entry);
    }

    private final List<AppEntry> items = new ArrayList<>();
    private final Listener listener;
    private final int itemLayout;

    public AppListAdapter(Listener listener, int itemLayout) {
        this.listener = listener;
        this.itemLayout = itemLayout;
    }

    public void submit(List<AppEntry> entries) {
        items.clear();
        items.addAll(entries);
        notifyDataSetChanged();
    }

    public void updateDpi(String packageName, int dpi) {
        for (int i = 0; i < items.size(); i++) {
            AppEntry entry = items.get(i);
            if (entry.packageName.equals(packageName)) {
                entry.dpi = dpi;
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(itemLayout, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppEntry entry = items.get(position);
        holder.icon.setImageDrawable(entry.icon);
        holder.name.setText(entry.label);
        holder.packageName.setText(entry.packageName);

        if (entry.dpi > 0) {
            holder.dpi.setText(holder.itemView.getContext()
                    .getString(R.string.dpi_value_short, entry.dpi));
            holder.dpi.setAlpha(1f);
        } else {
            holder.dpi.setText(R.string.dpi_default);
            holder.dpi.setAlpha(0.52f);
        }

        holder.itemView.setOnClickListener(v -> listener.onAppClick(entry));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView dpi;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.appIcon);
            name = itemView.findViewById(R.id.appName);
            packageName = itemView.findViewById(R.id.appPackage);
            dpi = itemView.findViewById(R.id.appDpi);
        }
    }
}
