package com.lokker.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lokker.app.R;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@link Intent#ACTION_CREATE_SHORTCUT} so external apps like
 * KeyMapper can discover and create launch shortcuts for hidden apps.
 *
 * Flow:
 * 1. KeyMapper (or any launcher) sends ACTION_CREATE_SHORTCUT.
 * 2. This activity shows a list of Lokker-managed (hidden) apps.
 * 3. User picks one → we return EXTRA_SHORTCUT_INTENT + EXTRA_SHORTCUT_NAME.
 * 4. When KeyMapper triggers the shortcut, it launches AuthActivity with
 *    the target_package extra → auth → unhide → launch.
 */
public class CreateShortcutActivity extends AppCompatActivity {

    private LokkerDatabase db;
    private AppRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = LokkerDatabase.getInstance(this);
        repo = AppRepository.getInstance(this);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        setContentView(buildUi());
        loadHiddenApps();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.shortcut_picker_title);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        setSupportActionBar(toolbar);
        outer.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        RecyclerView recycler = new RecyclerView(this);
        recycler.setId(android.R.id.list);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        outer.addView(recycler, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Empty state
        TextView empty = new TextView(this);
        empty.setId(android.R.id.empty);
        empty.setText(R.string.no_hidden_apps);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        empty.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        empty.setVisibility(View.GONE);
        outer.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return outer;
    }

    // ── Data ──────────────────────────────────────────────────────────────

    private void loadHiddenApps() {
        new Thread(() -> {
            List<LokkerApp> apps = db.lokkerAppDao().getAll();
            List<AppEntry> entries = new ArrayList<>();

            if (apps != null) {
                for (LokkerApp app : apps) {
                    String label = app.appLabel != null ? app.appLabel : app.packageName;
                    entries.add(new AppEntry(app.packageName, label));
                }
            }

            runOnUiThread(() -> {
                RecyclerView recycler = findViewById(android.R.id.list);
                TextView empty = findViewById(android.R.id.empty);

                if (entries.isEmpty()) {
                    recycler.setVisibility(View.GONE);
                    empty.setVisibility(View.VISIBLE);
                } else {
                    recycler.setAdapter(new ShortcutAdapter(entries));
                }
            });
        }).start();
    }

    // ── Result ────────────────────────────────────────────────────────────

    private void onAppSelected(AppEntry entry) {
        Intent shortcutIntent = new Intent("com.lokker.app.LAUNCH_HIDDEN");
        shortcutIntent.setClassName(getPackageName(),
                "com.lokker.app.ui.AuthActivity");
        shortcutIntent.putExtra("target_package", entry.packageName);
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        Intent result = new Intent();
        result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        result.putExtra(Intent.EXTRA_SHORTCUT_NAME, entry.label);

        // Try to attach the cached icon
        android.content.pm.ShortcutManager sm =
                getSystemService(android.content.pm.ShortcutManager.class);
        android.graphics.drawable.Icon icon = repo.loadCachedIcon(entry.packageName);
        if (icon != null) {
            result.putExtra(Intent.EXTRA_SHORTCUT_ICON,
                    iconToBitmap(icon));
        }

        setResult(RESULT_OK, result);
        finish();
    }

    private android.graphics.Bitmap iconToBitmap(android.graphics.drawable.Icon icon) {
        Drawable d = icon.loadDrawable(this);
        if (d == null) return null;
        int size = dp(48);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(canvas);
        return bmp;
    }

    // ── Data model ────────────────────────────────────────────────────────

    private static class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private class ShortcutAdapter extends RecyclerView.Adapter<ShortcutAdapter.VH> {

        private final List<AppEntry> apps;

        ShortcutAdapter(List<AppEntry> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(12);
            row.setPadding(dp(16), p, dp(16), p);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));

            // Ripple effect
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);

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

            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppEntry entry = apps.get(position);
            holder.label.setText(entry.label);
            holder.pkg.setText(entry.packageName);

            // Load cached icon (app is hidden so PM won't resolve it)
            android.graphics.drawable.Icon cachedIcon =
                    repo.loadCachedIcon(entry.packageName);
            if (cachedIcon != null) {
                holder.icon.setImageDrawable(cachedIcon.loadDrawable(
                        CreateShortcutActivity.this));
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            holder.itemView.setOnClickListener(v -> onAppSelected(entry));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView pkg;

            VH(View itemView) {
                super(itemView);
                icon = itemView.findViewById(android.R.id.icon);
                label = itemView.findViewById(android.R.id.text1);
                pkg = itemView.findViewById(android.R.id.text2);
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

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
