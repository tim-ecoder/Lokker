package com.lokker.app.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.lokker.app.R;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Full-screen picker showing all installed apps for multi-select.
 *
 * Features:
 *   - SearchView for filtering.
 *   - "Show system apps" toggle.
 *   - Checkbox multi-select.
 *   - Already-hidden apps are greyed out with a "Скрыто" label.
 *   - Bottom button: "Скрыть N приложение/приложения/приложений".
 *   - On confirm: inserts each selected app and marks it hidden.
 */
public class AppPickerDialog extends AppCompatActivity {

    private LokkerDatabase db;
    private AppRepository repo;
    private PickerAdapter adapter;
    private MaterialButton confirmBtn;
    private View rootView;

    private final Set<String> selectedPackages = new HashSet<>();
    private Set<String> alreadyManagedPackages = new HashSet<>();
    private boolean showSystemApps;
    private String currentFilter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = LokkerDatabase.getInstance(this);
        repo = AppRepository.getInstance(this);
        rootView = buildUi();
        setContentView(rootView);
        loadInstalledApps();
    }

    // ── UI construction ─────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.picker_title);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        outer.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Search field
        EditText searchField = new EditText(this);
        searchField.setHint(R.string.picker_search_hint);
        searchField.setTextColor(getResColor(R.color.colorOnSurface));
        searchField.setHintTextColor(getResColor(R.color.colorOnSurfaceMedium));
        searchField.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        searchField.setSingleLine(true);
        int sp = dp(12);
        searchField.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sfLp.setMargins(dp(16), dp(8), dp(16), dp(4));
        outer.addView(searchField, sfLp);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                currentFilter = s.toString();
                adapter.applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Show system apps toggle
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(dp(16), dp(4), dp(16), dp(4));

        TextView toggleLabel = new TextView(this);
        toggleLabel.setText(R.string.picker_show_system);
        toggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggleLabel.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        toggleRow.addView(toggleLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        SwitchCompat systemToggle = new SwitchCompat(this);
        systemToggle.setChecked(false);
        systemToggle.setOnCheckedChangeListener((btn, checked) -> {
            showSystemApps = checked;
            adapter.applyFilter();
        });
        toggleRow.addView(systemToggle);
        outer.addView(toggleRow);

        // RecyclerView
        RecyclerView recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PickerAdapter();
        recycler.setAdapter(adapter);
        outer.addView(recycler, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Confirm button
        confirmBtn = new MaterialButton(this);
        confirmBtn.setText(R.string.picker_btn_empty);
        confirmBtn.setEnabled(false);
        confirmBtn.setOnClickListener(v -> onConfirm());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int bm = dp(12);
        btnLp.setMargins(bm, bm, bm, bm);
        outer.addView(confirmBtn, btnLp);

        return outer;
    }

    // ── Data loading ────────────────────────────────────────────────────

    private void loadInstalledApps() {
        new Thread(() -> {
            // Get already-managed packages
            List<LokkerApp> managed = db.lokkerAppDao().getAll();
            Set<String> managedSet = new HashSet<>();
            if (managed != null) {
                for (LokkerApp a : managed) {
                    managedSet.add(a.packageName);
                }
            }

            // Get all installed apps
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            List<AppEntry> entries = new ArrayList<>();
            String ownPkg = getPackageName();

            for (ApplicationInfo info : installed) {
                if (info.packageName.equals(ownPkg)) continue;
                String label = pm.getApplicationLabel(info).toString();
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean alreadyManaged = managedSet.contains(info.packageName);

                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(info.packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    icon = null;
                }

                entries.add(new AppEntry(info.packageName, label, icon,
                        system, alreadyManaged));
            }

            // Sort alphabetically
            Collections.sort(entries,
                    (a, b) -> a.label.compareToIgnoreCase(b.label));

            alreadyManagedPackages = managedSet;

            runOnUiThread(() -> {
                adapter.setAllApps(entries);
            });
        }).start();
    }

    // ── Confirm action ──────────────────────────────────────────────────

    private void onConfirm() {
        if (selectedPackages.isEmpty()) return;

        List<String> packages = new ArrayList<>(selectedPackages);
        PackageManager pm = getPackageManager();

        new Thread(() -> {
            for (String pkg : packages) {
                repo.addApplication(pkg);
                repo.hideApp(pkg);
            }

            int count = packages.size();
            String noun = getAppNoun(count);
            runOnUiThread(() -> {
                Snackbar.make(rootView,
                        getString(R.string.apps_hidden_count, count, noun),
                        Snackbar.LENGTH_SHORT).show();
                rootView.postDelayed(this::finish, 600);
            });
        }).start();
    }

    private void updateConfirmButton() {
        int count = selectedPackages.size();
        if (count == 0) {
            confirmBtn.setText(R.string.picker_btn_empty);
            confirmBtn.setEnabled(false);
        } else {
            String noun = getAppNoun(count);
            confirmBtn.setText(getString(R.string.picker_btn_format, count, noun));
            confirmBtn.setEnabled(true);
        }
    }

    /**
     * Russian pluralization for "приложение":
     *   1             -> приложение
     *   2, 3, 4       -> приложения
     *   5-20          -> приложений
     *   21            -> приложение   (etc.)
     */
    private String getAppNoun(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "\u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u0435";
        } else if (mod10 >= 2 && mod10 <= 4
                && (mod100 < 12 || mod100 > 14)) {
            return "\u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F";
        } else {
            return "\u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u0439";
        }
    }

    // ── Data model ──────────────────────────────────────────────────────

    private static class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean systemApp;
        final boolean alreadyManaged;

        AppEntry(String packageName, String label, Drawable icon,
                 boolean systemApp, boolean alreadyManaged) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.systemApp = systemApp;
            this.alreadyManaged = alreadyManaged;
        }
    }

    // ── RecyclerView Adapter ────────────────────────────────────────────

    private class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {

        private List<AppEntry> allApps = new ArrayList<>();
        private List<AppEntry> filteredApps = new ArrayList<>();

        void setAllApps(List<AppEntry> apps) {
            allApps = apps != null ? apps : new ArrayList<>();
            applyFilter();
        }

        void applyFilter() {
            String lower = currentFilter.toLowerCase(Locale.getDefault());
            filteredApps = new ArrayList<>();
            for (AppEntry e : allApps) {
                // Filter by system toggle
                if (!showSystemApps && e.systemApp) continue;
                // Filter by search
                if (!lower.isEmpty()
                        && !e.label.toLowerCase(Locale.getDefault()).contains(lower)
                        && !e.packageName.toLowerCase(Locale.getDefault()).contains(lower)) {
                    continue;
                }
                filteredApps.add(e);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(10);
            row.setPadding(dp(16), p, dp(16), p);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));

            // Checkbox
            CheckBox cb = new CheckBox(parent.getContext());
            cb.setId(android.R.id.checkbox);
            LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cbLp.setMarginEnd(dp(8));
            row.addView(cb, cbLp);

            // App icon
            ImageView icon = new ImageView(parent.getContext());
            icon.setId(android.R.id.icon);
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLp.setMarginEnd(dp(12));
            row.addView(icon, iconLp);

            // Text column
            LinearLayout textCol = new LinearLayout(parent.getContext());
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(parent.getContext());
            label.setId(android.R.id.text1);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(getResColor(R.color.colorOnSurface));
            textCol.addView(label);

            TextView pkg = new TextView(parent.getContext());
            pkg.setId(android.R.id.text2);
            pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pkg.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            textCol.addView(pkg);

            row.addView(textCol, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // "Скрыто" badge (hidden by default)
            TextView badge = new TextView(parent.getContext());
            badge.setId(android.R.id.summary);
            badge.setText(R.string.label_hidden);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            badge.setVisibility(View.GONE);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(4));
            badgeBg.setColor(getResColor(R.color.colorCardBg));
            badge.setBackground(badgeBg);
            int bp = dp(4);
            badge.setPadding(dp(8), bp, dp(8), bp);

            row.addView(badge);

            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppEntry entry = filteredApps.get(position);

            holder.label.setText(entry.label);
            holder.pkg.setText(entry.packageName);

            if (entry.icon != null) {
                holder.icon.setImageDrawable(entry.icon);
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            // Remove previous listener BEFORE setting checked state to avoid recycling issues
            holder.checkbox.setOnCheckedChangeListener(null);

            if (entry.alreadyManaged) {
                holder.badge.setVisibility(View.VISIBLE);
                holder.checkbox.setEnabled(false);
                holder.checkbox.setChecked(false);
                holder.itemView.setAlpha(0.5f);
            } else {
                holder.badge.setVisibility(View.GONE);
                holder.checkbox.setEnabled(true);
                holder.checkbox.setChecked(
                        selectedPackages.contains(entry.packageName));
                holder.itemView.setAlpha(1f);
            }

            holder.checkbox.setOnCheckedChangeListener((btn, checked) -> {
                if (entry.alreadyManaged) return;
                if (checked) {
                    selectedPackages.add(entry.packageName);
                } else {
                    selectedPackages.remove(entry.packageName);
                }
                updateConfirmButton();
            });

            holder.itemView.setOnClickListener(v -> {
                if (!entry.alreadyManaged) {
                    holder.checkbox.setChecked(!holder.checkbox.isChecked());
                }
            });
        }

        @Override
        public int getItemCount() {
            return filteredApps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final CheckBox checkbox;
            final ImageView icon;
            final TextView label;
            final TextView pkg;
            final TextView badge;

            VH(View itemView) {
                super(itemView);
                checkbox = itemView.findViewById(android.R.id.checkbox);
                icon = itemView.findViewById(android.R.id.icon);
                label = itemView.findViewById(android.R.id.text1);
                pkg = itemView.findViewById(android.R.id.text2);
                badge = itemView.findViewById(android.R.id.summary);
            }
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private int getResColor(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int getColorAttr(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
