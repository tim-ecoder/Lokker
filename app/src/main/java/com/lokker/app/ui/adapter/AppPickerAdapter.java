package com.lokker.app.ui.adapter;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lokker.app.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.PickerViewHolder> {

    public static class PickerApp {
        public final String packageName;
        public final String name;
        public final Drawable icon;
        public final boolean system;
        public final boolean alreadyHidden;

        public PickerApp(String packageName, String name, Drawable icon, boolean system, boolean alreadyHidden) {
            this.packageName = packageName;
            this.name = name;
            this.icon = icon;
            this.system = system;
            this.alreadyHidden = alreadyHidden;
        }
    }

    private List<PickerApp> allApps = new ArrayList<>();
    private List<PickerApp> filteredApps = new ArrayList<>();
    private final Set<String> selectedPackages = new HashSet<>();

    public void setApps(@NonNull List<PickerApp> apps) {
        this.allApps = new ArrayList<>(apps);
        this.filteredApps = new ArrayList<>(apps);
        selectedPackages.clear();
        notifyDataSetChanged();
    }

    public void filter(String query, boolean showSystem) {
        String lowerQuery = query != null ? query.toLowerCase(Locale.ROOT).trim() : "";
        filteredApps = new ArrayList<>();
        for (PickerApp app : allApps) {
            if (!showSystem && app.system) {
                continue;
            }
            if (!lowerQuery.isEmpty()) {
                boolean matchesName = app.name != null
                        && app.name.toLowerCase(Locale.ROOT).contains(lowerQuery);
                boolean matchesPkg = app.packageName.toLowerCase(Locale.ROOT).contains(lowerQuery);
                if (!matchesName && !matchesPkg) {
                    continue;
                }
            }
            filteredApps.add(app);
        }
        notifyDataSetChanged();
    }

    @NonNull
    public Set<String> selectedPackages() {
        return new HashSet<>(selectedPackages);
    }

    @NonNull
    @Override
    public PickerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_picker_app, parent, false);
        return new PickerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PickerViewHolder holder, int position) {
        PickerApp app = filteredApps.get(position);
        holder.bind(app);
    }

    @Override
    public int getItemCount() {
        return filteredApps.size();
    }

    class PickerViewHolder extends RecyclerView.ViewHolder {

        private final CheckBox checkbox;
        private final ImageView appIcon;
        private final TextView appName;
        private final TextView packageName;
        private final TextView hiddenLabel;

        PickerViewHolder(@NonNull View itemView) {
            super(itemView);
            checkbox = itemView.findViewById(R.id.checkbox);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            packageName = itemView.findViewById(R.id.package_name);
            hiddenLabel = itemView.findViewById(R.id.hidden_label);
        }

        void bind(@NonNull PickerApp app) {
            appName.setText(app.name);
            packageName.setText(app.packageName);

            if (app.icon != null) {
                appIcon.setImageDrawable(app.icon);
            } else {
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            // Remove old listener before setting checked state to avoid spurious toggles
            checkbox.setOnCheckedChangeListener(null);

            if (app.alreadyHidden) {
                itemView.setAlpha(0.5f);
                checkbox.setEnabled(false);
                checkbox.setChecked(false);
                hiddenLabel.setVisibility(View.VISIBLE);
            } else {
                itemView.setAlpha(1.0f);
                checkbox.setEnabled(true);
                checkbox.setChecked(selectedPackages.contains(app.packageName));
                hiddenLabel.setVisibility(View.GONE);
            }

            checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (app.alreadyHidden) return;
                if (isChecked) {
                    selectedPackages.add(app.packageName);
                } else {
                    selectedPackages.remove(app.packageName);
                }
            });

            // Allow tapping the whole row to toggle the checkbox
            itemView.setOnClickListener(v -> {
                if (!app.alreadyHidden && checkbox.isEnabled()) {
                    checkbox.toggle();
                }
            });
        }
    }
}
