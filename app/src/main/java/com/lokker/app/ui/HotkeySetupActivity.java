package com.lokker.app.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.lokker.app.R;
import com.lokker.app.data.db.Converters;
import com.lokker.app.data.db.HotkeyMap;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Hotkey configuration screen.
 *
 * Section 1: Lokker hotkey recorder
 *   - Shows current key badges (key codes as chips).
 *   - Click to start recording a new sequence (simulated 3-second timer).
 *
 * Section 2: Per-app hotkey list
 *   - Dynamically populated from hidden apps in the database.
 *   - Each item opens a BottomSheetDialog for recording an app-specific hotkey.
 *
 * Recording is simulated with a 3-second countdown timer and a visual
 * recording indicator.
 */
public class HotkeySetupActivity extends AppCompatActivity {

    private LokkerDatabase db;
    private LinearLayout appHotkeyContainer;
    private LinearLayout lokkerBadgeContainer;
    private View rootView;

    private boolean isRecording;
    private CountDownTimer recordTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = LokkerDatabase.getInstance(this);
        rootView = buildUi();
        setContentView(rootView);
        loadData();
    }

    // ── UI construction ─────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.hotkey_setup_label);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);
        outer.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scrollable content
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        // ── Section 1: Lokker hotkey ────────────────────────────────────
        addSectionHeader(content, R.string.section_lokker_hotkey);

        // Current key badges container
        lokkerBadgeContainer = new LinearLayout(this);
        lokkerBadgeContainer.setOrientation(LinearLayout.HORIZONTAL);
        lokkerBadgeContainer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.bottomMargin = dp(8);
        content.addView(lokkerBadgeContainer, badgeLp);

        // Hint text
        TextView hint = new TextView(this);
        hint.setText(R.string.hotkey_record_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = dp(12);
        content.addView(hint, hintLp);

        // Record button for Lokker hotkey
        MaterialButton recordBtn = new MaterialButton(this);
        recordBtn.setText(R.string.hotkey_recording);
        recordBtn.setOnClickListener(v -> startLokkerRecording(recordBtn));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rbLp.bottomMargin = dp(16);
        content.addView(recordBtn, rbLp);

        // Divider
        addDivider(content);

        // ── Section 2: Per-app hotkeys ──────────────────────────────────
        addSectionHeader(content, R.string.section_app_hotkeys);

        appHotkeyContainer = new LinearLayout(this);
        appHotkeyContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(appHotkeyContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return outer;
    }

    // ── Data loading ────────────────────────────────────────────────────

    private void loadData() {
        new Thread(() -> {
            // Load Lokker hotkey
            HotkeyMap map = db.hotkeyMapDao().get();
            List<Integer> lokkerHotkey = (map != null) ? map.lokkerHotkey : null;

            // Load hidden apps
            List<LokkerApp> hiddenApps = db.lokkerAppDao().getAllHidden();

            runOnUiThread(() -> {
                updateLokkerBadges(lokkerHotkey);
                populateAppHotkeys(hiddenApps);
            });
        }).start();
    }

    private void updateLokkerBadges(List<Integer> keyCodes) {
        lokkerBadgeContainer.removeAllViews();

        if (keyCodes == null || keyCodes.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.hotkey_not_set);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            none.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            lokkerBadgeContainer.addView(none);
            return;
        }

        for (int i = 0; i < keyCodes.size(); i++) {
            if (i > 0) {
                TextView plus = new TextView(this);
                plus.setText(" + ");
                plus.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
                lokkerBadgeContainer.addView(plus);
            }
            lokkerBadgeContainer.addView(createKeyBadge(keyCodes.get(i)));
        }
    }

    private void populateAppHotkeys(List<LokkerApp> apps) {
        appHotkeyContainer.removeAllViews();

        if (apps == null || apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_hidden_apps);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            appHotkeyContainer.addView(empty, lp);
            return;
        }

        for (LokkerApp app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);
            row.setClickable(true);
            row.setFocusable(true);

            // App icon
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(
                        getPackageManager().getApplicationIcon(app.packageName));
            } catch (Exception e) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(dp(36), dp(36));
            iconLp.setMarginEnd(dp(12));
            row.addView(icon, iconLp);

            // Text column
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(this);
            label.setText(app.appLabel != null ? app.appLabel : app.packageName);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(getResColor(R.color.colorOnSurface));
            textCol.addView(label);

            TextView subtitle = new TextView(this);
            if (app.hotkeySequence != null && !app.hotkeySequence.isEmpty()) {
                subtitle.setText(formatKeySequence(app.hotkeySequence));
            } else {
                subtitle.setText(R.string.hotkey_not_set);
            }
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            subtitle.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            textCol.addView(subtitle);

            row.addView(textCol, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> showAppHotkeyBottomSheet(app));

            appHotkeyContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    // ── Recording ───────────────────────────────────────────────────────

    private void startLokkerRecording(MaterialButton btn) {
        if (isRecording) return;
        isRecording = true;
        btn.setEnabled(false);
        btn.setText(R.string.hotkey_recording);

        recordTimer = new CountDownTimer(3000, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Recording indicator - pulse button text
                int secs = (int) Math.ceil(millisUntilFinished / 1000.0);
                btn.setText(getString(R.string.hotkey_recording) + " " + secs);
            }

            @Override
            public void onFinish() {
                isRecording = false;
                btn.setEnabled(true);
                btn.setText(R.string.hotkey_recording);

                // Simulated result: save a sample hotkey sequence
                // In production, the accessibility service captures real key events.
                List<Integer> simulated = new ArrayList<>();
                simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
                simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
                simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_UP);

                new Thread(() -> {
                    HotkeyMap map = db.hotkeyMapDao().get();
                    if (map == null) {
                        map = new HotkeyMap();
                    }
                    map.lokkerHotkey = simulated;
                    db.hotkeyMapDao().insertOrUpdate(map);
                    runOnUiThread(() -> {
                        updateLokkerBadges(simulated);
                        Snackbar.make(rootView, R.string.hotkey_saved,
                                Snackbar.LENGTH_SHORT).show();
                    });
                }).start();
            }
        }.start();
    }

    private void showAppHotkeyBottomSheet(LokkerApp app) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        int pad = dp(24);
        sheet.setPadding(pad, pad, pad, pad);

        // Title
        TextView title = new TextView(this);
        title.setText(app.appLabel != null ? app.appLabel : app.packageName);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        sheet.addView(title);

        // Current hotkey display
        LinearLayout currentRow = new LinearLayout(this);
        currentRow.setOrientation(LinearLayout.HORIZONTAL);
        currentRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(16);
        crLp.bottomMargin = dp(16);

        if (app.hotkeySequence != null && !app.hotkeySequence.isEmpty()) {
            for (int i = 0; i < app.hotkeySequence.size(); i++) {
                if (i > 0) {
                    TextView plus = new TextView(this);
                    plus.setText(" + ");
                    plus.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
                    currentRow.addView(plus);
                }
                currentRow.addView(createKeyBadge(app.hotkeySequence.get(i)));
            }
        } else {
            TextView none = new TextView(this);
            none.setText(R.string.hotkey_not_set);
            none.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            currentRow.addView(none);
        }
        sheet.addView(currentRow, crLp);

        // Recording indicator
        TextView recordingLabel = new TextView(this);
        recordingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        recordingLabel.setTextColor(getResColor(R.color.colorPrimary));
        recordingLabel.setVisibility(View.GONE);
        sheet.addView(recordingLabel);

        // Record button
        MaterialButton recordBtn = new MaterialButton(this);
        recordBtn.setText(R.string.hotkey_record_hint);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rbLp.topMargin = dp(8);
        sheet.addView(recordBtn, rbLp);

        recordBtn.setOnClickListener(v -> {
            recordBtn.setEnabled(false);
            recordingLabel.setVisibility(View.VISIBLE);
            recordingLabel.setText(R.string.hotkey_recording);

            new CountDownTimer(3000, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                    int secs = (int) Math.ceil(millisUntilFinished / 1000.0);
                    recordingLabel.setText(
                            getString(R.string.hotkey_recording) + " " + secs);
                }

                @Override
                public void onFinish() {
                    recordBtn.setEnabled(true);
                    recordingLabel.setVisibility(View.GONE);

                    // Simulated key sequence for this app
                    List<Integer> simulated = new ArrayList<>();
                    simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_UP);
                    simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_UP);
                    simulated.add(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);

                    String hotkeyJson = Converters.fromIntList(simulated);
                    new Thread(() -> {
                        db.lokkerAppDao().setHotkey(app.packageName, hotkeyJson);
                        runOnUiThread(() -> {
                            Snackbar.make(rootView,
                                    getString(R.string.hotkey_app_saved, app.appLabel),
                                    Snackbar.LENGTH_SHORT).show();
                            dialog.dismiss();
                            loadData(); // refresh list
                        });
                    }).start();
                }
            }.start();
        });

        dialog.setContentView(sheet);
        dialog.show();
    }

    // ── Key badge widget ────────────────────────────────────────────────

    private View createKeyBadge(int keyCode) {
        TextView badge = new TextView(this);
        badge.setText(android.view.KeyEvent.keyCodeToString(keyCode)
                .replace("KEYCODE_", ""));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setTextColor(getResColor(R.color.colorOnSurface));
        badge.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(getResColor(R.color.colorCardBg));
        bg.setStroke(dp(1), getResColor(R.color.colorDivider));
        badge.setBackground(bg);

        int hPad = dp(8);
        int vPad = dp(4);
        badge.setPadding(hPad, vPad, hPad, vPad);

        return badge;
    }

    private String formatKeySequence(List<Integer> keyCodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyCodes.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(android.view.KeyEvent.keyCodeToString(keyCodes.get(i))
                    .replace("KEYCODE_", ""));
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

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

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getResColor(R.color.colorDivider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        parent.addView(divider, lp);
    }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordTimer != null) {
            recordTimer.cancel();
        }
    }
}
