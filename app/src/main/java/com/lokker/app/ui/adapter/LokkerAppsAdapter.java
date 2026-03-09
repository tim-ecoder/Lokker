package com.lokker.app.ui.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lokker.app.R;
import com.lokker.app.data.db.LokkerApp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LokkerAppsAdapter extends RecyclerView.Adapter<LokkerAppsAdapter.AppViewHolder> {

    public interface OnAppActionListener {
        void onAppClick(String packageName);
        void onMenuAction(String packageName, String action);
    }

    private List<LokkerApp> apps = new ArrayList<>();
    private final OnAppActionListener listener;

    public LokkerAppsAdapter(@NonNull OnAppActionListener listener) {
        this.listener = listener;
    }

    public void setApps(@NonNull List<LokkerApp> newApps) {
        this.apps = new ArrayList<>(newApps);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hidden_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        LokkerApp app = apps.get(position);
        holder.bind(app);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    class AppViewHolder extends RecyclerView.ViewHolder {

        private final ImageView appIcon;
        private final TextView appName;
        private final TextView packageName;
        private final ImageButton menuButton;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            menuButton = itemView.findViewById(R.id.menu_button);
        }

        void bind(@NonNull LokkerApp app) {
            appName.setText(app.appLabel);
            packageName.setText(app.packageName);

            // Load cached icon from files/icons/{pkg}.png
            Context context = itemView.getContext();
            File iconFile = new File(context.getFilesDir(), "icons/" + app.packageName + ".png");
            if (iconFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                if (bitmap != null) {
                    appIcon.setImageBitmap(bitmap);
                } else {
                    appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            } else {
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            // Row click
            itemView.setOnClickListener(v -> listener.onAppClick(app.packageName));

            // Menu button click
            menuButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, menuButton);
                popup.inflate(R.menu.context_menu);
                popup.setOnMenuItemClickListener(item -> {
                    String action;
                    int id = item.getItemId();
                    if (id == R.id.ctx_hotkey) {
                        action = "hotkey";
                    } else if (id == R.id.ctx_launch) {
                        action = "launch";
                    } else if (id == R.id.ctx_remove) {
                        action = "remove";
                    } else {
                        return false;
                    }
                    listener.onMenuAction(app.packageName, action);
                    return true;
                });
                popup.show();
            });
        }
    }
}
