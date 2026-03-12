package com.lokker.app.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;

import android.widget.Toast;
import com.lokker.app.R;
import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.data.db.HotkeyMap;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;
import com.lokker.app.domain.AuthManager;

import java.util.List;

/**
 * Settings screen with sections:
 *   - Security: self-hide toggle, password, biometric toggle
 *   - Hotkeys: navigate to HotkeySetupActivity
 *   - Management: unhide all / hide all
 *   - Data: export / import settings
 *   - About: app name, version, platform
 */
public class SettingsActivity extends AppCompatActivity {

    private LokkerPrefs prefs;
    private AuthManager authManager;
    private LokkerDatabase db;
    private View rootView;

    private static final int REQUEST_CHANGE_PASSWORD = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = LokkerPrefs.getInstance(this);
        authManager = new AuthManager(prefs);
        db = LokkerDatabase.getInstance(this);

        rootView = buildUi();
        setContentView(rootView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh UI to reflect changes (e.g. password set/changed)
        rootView = buildUi();
        setContentView(rootView);
        rootView.requestApplyInsets();
    }

    // ── UI construction ─────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.settings_title);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        outer.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scrollable content
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        // ── Security section ────────────────────────────────────────────
        addSectionHeader(content, R.string.section_security);

        // Self-hide toggle
        addSwitchItem(content,
                R.string.self_hide_label,
                R.string.self_hide_desc,
                prefs.isStealthMode(),
                (buttonView, isChecked) -> {
                    if (isChecked) {
                        // Check hotkey is set first
                        new Thread(() -> {
                            HotkeyMap map = db.hotkeyMapDao().get();
                            runOnUiThread(() -> {
                                if (map == null || map.lokkerHotkey == null
                                        || map.lokkerHotkey.isEmpty()) {
                                    buttonView.setChecked(false);
                                    Toast.makeText(SettingsActivity.this, R.string.hotkey_required,
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    showSelfHideConfirmDialog(buttonView);
                                }
                            });
                        }).start();
                    } else {
                        setSelfHidden(false);
                    }
                });

        // Password item
        String pwLabel = authManager.hasPassword()
                ? getString(R.string.change_password)
                : getString(R.string.set_password);
        String pwDesc = authManager.hasPassword()
                ? getString(R.string.pw_change_desc)
                : getString(R.string.pw_not_set);
        addClickItem(content, pwLabel, pwDesc, v -> {
            Intent intent = new Intent(this, ChangePasswordActivity.class);
            intent.putExtra("is_change", authManager.hasPassword());
            startActivity(intent);
        });

        // Biometric toggle
        boolean bioAvailable = BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
        addSwitchItem(content,
                R.string.biometric_label,
                R.string.biometric_desc,
                prefs.isBiometricEnrolled(),
                (buttonView, isChecked) -> {
                    if (isChecked && !bioAvailable) {
                        buttonView.setChecked(false);
                        return;
                    }
                    prefs.setBiometricEnrolled(isChecked);
                });

        addDivider(content);

        // ── Hotkeys section ─────────────────────────────────────────────
        addSectionHeader(content, R.string.section_hotkeys);

        addClickItem(content,
                getString(R.string.hotkey_setup_label),
                getString(R.string.hotkey_not_set),
                v -> {
                    Intent intent = new Intent(this, HotkeySetupActivity.class);
                    startActivity(intent);
                });

        // Update hotkey count subtitle asynchronously
        LinearLayout hotkeyRow = (LinearLayout) content.getChildAt(content.getChildCount() - 1);
        TextView hotkeyDesc = (TextView) hotkeyRow.getChildAt(1);
        new Thread(() -> {
            HotkeyMap hkMap = db.hotkeyMapDao().get();
            List<LokkerApp> allApps = db.lokkerAppDao().getAll();
            int count = 0;
            if (hkMap != null && hkMap.lokkerHotkey != null && !hkMap.lokkerHotkey.isEmpty()) count++;
            if (allApps != null) {
                for (LokkerApp a : allApps) {
                    if (a.hotkeySequence != null && !a.hotkeySequence.isEmpty()) count++;
                }
            }
            if (count > 0) {
                int c = count;
                runOnUiThread(() -> hotkeyDesc.setText(
                        getString(R.string.hotkeys_configured_count, c)));
            }
        }).start();

        addDivider(content);

        // ── Management section ──────────────────────────────────────────
        addSectionHeader(content, R.string.section_management);

        addClickItem(content,
                getString(R.string.unhide_all),
                getString(R.string.unhide_all_desc),
                v -> showUnhideAllDialog());

        addClickItem(content,
                getString(R.string.hide_all),
                getString(R.string.hide_all_desc),
                v -> showHideAllDialog());

        addDivider(content);

        // ── About section ───────────────────────────────────────────────
        addSectionHeader(content, R.string.section_about);

        String versionName = "1.0.0";
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {}

        addInfoItem(content, getString(R.string.app_name), versionName);
        addInfoItem(content, getString(R.string.about_platform), null);

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return outer;
    }

    // ── Section / item builders ─────────────────────────────────────────

    private void addSectionHeader(LinearLayout parent, int stringRes) {
        TextView header = new TextView(this);
        header.setText(stringRes);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(getResColor(R.color.colorPrimary));
        header.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        parent.addView(header, lp);
    }

    private void addSwitchItem(LinearLayout parent, int titleRes, int descRes,
                               boolean checked,
                               CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        textCol.addView(title);

        TextView desc = new TextView(this);
        desc.setText(descRes);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        textCol.addView(desc);

        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
    }

    private void addClickItem(LinearLayout parent, String titleStr, String descStr,
                              View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);
        row.setOnClickListener(listener);

        TextView title = new TextView(this);
        title.setText(titleStr);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        row.addView(title);

        if (descStr != null) {
            TextView desc = new TextView(this);
            desc.setText(descStr);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            desc.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            row.addView(desc);
        }

        parent.addView(row);
    }

    private void addInfoItem(LinearLayout parent, String primary, String secondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(primary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        row.addView(title);

        if (secondary != null) {
            TextView sub = new TextView(this);
            sub.setText(secondary);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            row.addView(sub);
        }

        parent.addView(row);
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getResColor(R.color.colorDivider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(8);
        parent.addView(divider, lp);
    }

    // ── Self-hide ───────────────────────────────────────────────────────

    private void showSelfHideConfirmDialog(CompoundButton toggle) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.self_hide_confirm_title)
                .setMessage(R.string.self_hide_confirm_msg)
                .setPositiveButton(R.string.confirm, (d, w) -> setSelfHidden(true))
                .setNegativeButton(R.string.cancel, (d, w) -> toggle.setChecked(false))
                .setOnCancelListener(d -> toggle.setChecked(false))
                .show();
    }

    private void setSelfHidden(boolean hidden) {
        prefs.setStealthMode(hidden);
        PackageManager pm = getPackageManager();
        ComponentName alias = new ComponentName(this,
                getPackageName() + ".LokkerLauncher");
        pm.setComponentEnabledSetting(alias,
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                       : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        Toast.makeText(this,
                hidden ? R.string.lokker_hidden : R.string.lokker_visible,
                Toast.LENGTH_SHORT).show();
    }

    // ── Unhide all / Hide all ───────────────────────────────────────────

    private void showUnhideAllDialog() {
        new Thread(() -> {
            List<LokkerApp> hidden = db.lokkerAppDao().getAllHidden();
            runOnUiThread(() -> {
                if (hidden == null || hidden.isEmpty()) {
                    Toast.makeText(SettingsActivity.this, R.string.no_apps_to_unhide,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(R.string.unhide_confirm_title)
                        .setMessage(getString(R.string.unhide_confirm_msg, hidden.size()))
                        .setPositiveButton(R.string.confirm, (d, w) -> {
                            new Thread(() -> {
                                AppRepository repo = AppRepository.getInstance(SettingsActivity.this);
                                repo.unhideAll();
                                runOnUiThread(() ->
                                    Toast.makeText(SettingsActivity.this, R.string.all_unhidden,
                                            Toast.LENGTH_SHORT).show()
                                );
                            }).start();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    private void showHideAllDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.hide_confirm_title)
                .setMessage(R.string.hide_confirm_msg)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    new Thread(() -> {
                        AppRepository repo = AppRepository.getInstance(SettingsActivity.this);
                        repo.rehideAll();
                        runOnUiThread(() ->
                            Toast.makeText(SettingsActivity.this, R.string.all_hidden,
                                    Toast.LENGTH_SHORT).show()
                        );
                    }).start();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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
