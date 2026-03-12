package com.lokker.app.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.widget.Toast;
import com.lokker.app.R;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;
import com.lokker.app.domain.AuthManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main screen showing the list of managed / hidden applications.
 *
 * UI:
 *   - Toolbar with title "Lokker" and settings gear icon.
 *   - SearchView (EditText) for filtering the list.
 *   - RecyclerView of managed apps.
 *   - FAB to open AppPickerDialog.
 *   - Empty-state when no apps are managed.
 *
 * Each item has a three-dot overflow button with a PopupMenu:
 *   - "Назначить горячую клавишу"
 *   - "Запустить"
 *   - "Удалить из Lokker"
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_AUTH = 100;

    private RecyclerView recyclerView;
    private LinearLayout emptyState;
    private View rootView;
    private AppAdapter adapter;
    private LokkerDatabase db;
    private String currentSearchQuery = "";
    private boolean authenticated;
    private boolean navigatingInternally;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        db = LokkerDatabase.getInstance(this);

        rootView = buildUi();
        setContentView(rootView);

        // Observe app list via LiveData
        db.lokkerAppDao().getAllLive().observe(this, apps -> {
            if (apps == null || apps.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyState.setVisibility(View.VISIBLE);
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyState.setVisibility(View.GONE);
                adapter.submitList(apps);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from internal navigation — stay authenticated
        if (navigatingInternally) {
            navigatingInternally = false;
            return;
        }

        // Check if AuthActivity already authenticated us (hotkey path)
        if (!authenticated && getIntent().getBooleanExtra("authenticated", false)) {
            authenticated = true;
            getIntent().removeExtra("authenticated");
        }

        if (!authenticated) {
            AuthManager auth = new AuthManager(
                    com.lokker.app.data.LokkerPrefs.getInstance(this));
            if (auth.hasPassword()) {
                navigatingInternally = true;
                Intent intent = new Intent(this, AuthActivity.class);
                startActivityForResult(intent, REQUEST_AUTH);
            } else {
                authenticated = true;
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Reset auth when actually leaving the app, not during internal navigation
        if (!navigatingInternally) {
            authenticated = false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUTH) {
            if (resultCode == RESULT_OK) {
                authenticated = true;
            } else {
                // User didn't authenticate — close the app
                finish();
            }
        }
    }

    // ── UI construction ─────────────────────────────────────────────────

    private View buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.app_name);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));

        // Settings gear icon
        ImageButton settingsBtn = new ImageButton(this);
        settingsBtn.setImageResource(android.R.drawable.ic_menu_preferences);
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setColorFilter(getResColor(R.color.colorOnSurface));
        settingsBtn.setContentDescription(getString(R.string.settings_title));
        settingsBtn.setOnClickListener(v -> {
            navigatingInternally = true;
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        Toolbar.LayoutParams tbLp = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                Toolbar.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        tbLp.setMarginEnd(dp(8));
        toolbar.addView(settingsBtn, tbLp);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Search field
        EditText searchField = new EditText(this);
        searchField.setHint(R.string.main_search_hint);
        searchField.setTextColor(getResColor(R.color.colorOnSurface));
        searchField.setHintTextColor(getResColor(R.color.colorOnSurfaceMedium));
        searchField.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        searchField.setSingleLine(true);
        int searchPad = dp(12);
        searchField.setPadding(searchPad, searchPad, searchPad, searchPad);
        LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sfLp.setMargins(dp(16), dp(8), dp(16), dp(8));
        root.addView(searchField, sfLp);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString();
                adapter.filter(currentSearchQuery);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // RecyclerView
        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Empty state
        emptyState = new LinearLayout(this);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setVisibility(View.GONE);

        TextView emptyTitle = new TextView(this);
        emptyTitle.setText(R.string.empty_title);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setTextColor(getResColor(R.color.colorOnSurface));
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle);

        TextView emptySub = new TextView(this);
        emptySub.setText(R.string.empty_subtitle);
        emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptySub.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        emptySub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        esLp.topMargin = dp(8);
        emptyState.addView(emptySub, esLp);

        root.addView(emptyState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // FAB
        FloatingActionButton fab = new FloatingActionButton(this);
        fab.setImageResource(android.R.drawable.ic_input_add);
        fab.setContentDescription(getString(R.string.picker_title));
        fab.setOnClickListener(v -> {
            navigatingInternally = true;
            Intent picker = new Intent(this, AppPickerDialog.class);
            startActivity(picker);
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(0, 0, dp(16), dp(16));
        frame.addView(fab, fabLp);

        return frame;
    }

    // ── App item actions ────────────────────────────────────────────────

    private void onAppClick(LokkerApp app) {
        Toast.makeText(this,
                getString(R.string.launching_msg, app.appLabel),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AppRepository repo = AppRepository.getInstance(this);
            repo.unhideTemporarily(app.packageName);
            repo.launchHiddenApp(app.packageName);
        }).start();
    }

    private void showContextMenu(View anchor, LokkerApp app) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.context_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.ctx_hotkey) {
                navigatingInternally = true;
                Intent intent = new Intent(this, HotkeySetupActivity.class);
                intent.putExtra("target_package", app.packageName);
                startActivity(intent);
                return true;
            } else if (id == R.id.ctx_launch) {
                onAppClick(app);
                return true;
            } else if (id == R.id.ctx_remove) {
                showRemoveConfirmDialog(app);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showRemoveConfirmDialog(LokkerApp app) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.remove_confirm_title)
                .setMessage(getString(R.string.remove_confirm_msg, app.appLabel))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    new Thread(() -> {
                        AppRepository.getInstance(this).removeApplication(app.packageName);
                        runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.removed_msg, app.appLabel),
                                    Toast.LENGTH_SHORT).show()
                        );
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── RecyclerView Adapter ────────────────────────────────────────────

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        private List<LokkerApp> allApps = new ArrayList<>();
        private List<LokkerApp> filteredApps = new ArrayList<>();

        void submitList(List<LokkerApp> apps) {
            allApps = apps != null ? new ArrayList<>(apps) : new ArrayList<>();
            filter(currentSearchQuery);
        }

        void filter(String query) {
            if (query == null || query.isEmpty()) {
                filteredApps = new ArrayList<>(allApps);
            } else {
                String lower = query.toLowerCase(Locale.getDefault());
                filteredApps = new ArrayList<>();
                for (LokkerApp app : allApps) {
                    if (app.appLabel != null
                            && app.appLabel.toLowerCase(Locale.getDefault()).contains(lower)) {
                        filteredApps.add(app);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(12);
            row.setPadding(dp(16), p, dp(8), p);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));

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
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTextColor(getResColor(R.color.colorOnSurface));
            textCol.addView(label);

            TextView pkg = new TextView(parent.getContext());
            pkg.setId(android.R.id.text2);
            pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pkg.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            textCol.addView(pkg);

            row.addView(textCol, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Overflow button
            ImageButton overflow = new ImageButton(parent.getContext());
            overflow.setId(android.R.id.button1);
            overflow.setImageResource(android.R.drawable.ic_menu_more);
            overflow.setBackgroundColor(Color.TRANSPARENT);
            overflow.setColorFilter(getResColor(R.color.colorOnSurfaceMedium));
            overflow.setContentDescription("Menu");
            row.addView(overflow, new LinearLayout.LayoutParams(dp(40), dp(40)));

            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            LokkerApp app = filteredApps.get(position);
            holder.label.setText(app.appLabel != null ? app.appLabel : app.packageName);
            holder.pkg.setText(app.packageName);

            // Load app icon — try cached icon first (hidden apps are invisible to PM)
            File iconFile = new File(getFilesDir(), "icons/" + app.packageName + ".png");
            if (iconFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                if (bmp != null) {
                    holder.icon.setImageDrawable(new BitmapDrawable(getResources(), bmp));
                } else {
                    holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            } else {
                try {
                    Drawable appIcon = getPackageManager()
                            .getApplicationIcon(app.packageName);
                    holder.icon.setImageDrawable(appIcon);
                } catch (PackageManager.NameNotFoundException e) {
                    holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            }

            holder.itemView.setOnClickListener(v -> onAppClick(app));
            holder.overflow.setOnClickListener(v -> showContextMenu(v, app));
        }

        @Override
        public int getItemCount() {
            return filteredApps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView pkg;
            final ImageButton overflow;

            VH(View itemView) {
                super(itemView);
                icon = itemView.findViewById(android.R.id.icon);
                label = itemView.findViewById(android.R.id.text1);
                pkg = itemView.findViewById(android.R.id.text2);
                overflow = itemView.findViewById(android.R.id.button1);
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
