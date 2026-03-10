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
import com.lokker.app.service.LokkerAccessibilityService;

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

    private String targetPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = LokkerDatabase.getInstance(this);
        targetPackage = getIntent().getStringExtra("target_package");
        rootView = buildUi();
        setContentView(rootView);
        loadData();
    }

    // ── UI construction ─────────────────────────────────────────────────

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
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

                // If launched from context menu for a specific app, open its recorder
                if (targetPackage != null && hiddenApps != null) {
                    for (LokkerApp app : hiddenApps) {
                        if (targetPackage.equals(app.packageName)) {
                            targetPackage = null; // only once
                            showAppHotkeyBottomSheet(app);
                            break;
                        }
                    }
                }
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

    private final List<Integer> recordedKeys = new ArrayList<>();

    private void startLokkerRecording(MaterialButton btn) {
        if (isRecording) return;
        isRecording = true;
        recordedKeys.clear();
        btn.setEnabled(false);
        btn.setText(R.string.hotkey_recording);

        LokkerAccessibilityService.startRecording(keyCode -> runOnUiThread(() -> {
            if (keyCode < 0) {
                // Long press upgrade: replace last matching tap
                int normalKey = -keyCode;
                if (!recordedKeys.isEmpty()
                        && recordedKeys.get(recordedKeys.size() - 1) == normalKey) {
                    recordedKeys.set(recordedKeys.size() - 1, keyCode);
                }
            } else {
                recordedKeys.add(keyCode);
            }
            btn.setText(getString(R.string.hotkey_recording) + " (" + recordedKeys.size() + ")");

            // Reset the finish timer on each key press
            if (recordTimer != null) recordTimer.cancel();
            recordTimer = new CountDownTimer(1500, 1500) {
                @Override public void onTick(long ms) {}
                @Override
                public void onFinish() {
                    finishLokkerRecording(btn);
                }
            }.start();
        }));

        // Timeout if no keys pressed within 5 seconds
        recordTimer = new CountDownTimer(5000, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secs = (int) Math.ceil(millisUntilFinished / 1000.0);
                btn.setText(getString(R.string.hotkey_recording) + " " + secs);
            }
            @Override
            public void onFinish() {
                finishLokkerRecording(btn);
            }
        }.start();
    }

    private void finishLokkerRecording(MaterialButton btn) {
        LokkerAccessibilityService.stopRecording();
        isRecording = false;
        btn.setEnabled(true);
        btn.setText(R.string.hotkey_record_hint);

        if (recordedKeys.isEmpty()) {
            Snackbar.make(rootView, R.string.hotkey_not_set, Snackbar.LENGTH_SHORT).show();
            return;
        }

        List<Integer> captured = new ArrayList<>(recordedKeys);
        new Thread(() -> {
            String conflict = checkHotkeyConflict(captured, "lokker");
            if (conflict != null) {
                runOnUiThread(() -> Snackbar.make(rootView,
                        getString(R.string.hotkey_conflict, conflict),
                        Snackbar.LENGTH_LONG).show());
                return;
            }
            HotkeyMap map = db.hotkeyMapDao().get();
            if (map == null) map = new HotkeyMap();
            map.lokkerHotkey = captured;
            db.hotkeyMapDao().insertOrUpdate(map);
            runOnUiThread(() -> {
                updateLokkerBadges(captured);
                Snackbar.make(rootView, R.string.hotkey_saved,
                        Snackbar.LENGTH_SHORT).show();
            });
        }).start();
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

        final CountDownTimer[] sheetTimer = {null};
        final List<Integer> appRecordedKeys = new ArrayList<>();
        dialog.setOnDismissListener(d -> {
            LokkerAccessibilityService.stopRecording();
            if (sheetTimer[0] != null) {
                sheetTimer[0].cancel();
            }
        });

        recordBtn.setOnClickListener(v -> {
            recordBtn.setEnabled(false);
            appRecordedKeys.clear();
            recordingLabel.setVisibility(View.VISIBLE);
            recordingLabel.setText(R.string.hotkey_recording);

            LokkerAccessibilityService.startRecording(keyCode -> runOnUiThread(() -> {
                if (keyCode < 0) {
                    int normalKey = -keyCode;
                    if (!appRecordedKeys.isEmpty()
                            && appRecordedKeys.get(appRecordedKeys.size() - 1) == normalKey) {
                        appRecordedKeys.set(appRecordedKeys.size() - 1, keyCode);
                    }
                } else {
                    appRecordedKeys.add(keyCode);
                }
                recordingLabel.setText(getString(R.string.hotkey_recording)
                        + " (" + appRecordedKeys.size() + ")");

                // Reset the finish timer on each key press
                if (sheetTimer[0] != null) sheetTimer[0].cancel();
                sheetTimer[0] = new CountDownTimer(1500, 1500) {
                    @Override public void onTick(long ms) {}
                    @Override
                    public void onFinish() {
                        finishAppRecording(app, dialog, recordBtn,
                                recordingLabel, appRecordedKeys);
                    }
                }.start();
            }));

            // Timeout if no keys within 5 seconds
            sheetTimer[0] = new CountDownTimer(5000, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (appRecordedKeys.isEmpty()) {
                        int secs = (int) Math.ceil(millisUntilFinished / 1000.0);
                        recordingLabel.setText(
                                getString(R.string.hotkey_recording) + " " + secs);
                    }
                }
                @Override
                public void onFinish() {
                    finishAppRecording(app, dialog, recordBtn,
                            recordingLabel, appRecordedKeys);
                }
            }.start();
        });

        dialog.setContentView(sheet);
        dialog.show();
    }

    private void finishAppRecording(LokkerApp app, BottomSheetDialog dialog,
                                    MaterialButton recordBtn, TextView recordingLabel,
                                    List<Integer> keys) {
        LokkerAccessibilityService.stopRecording();
        recordBtn.setEnabled(true);
        recordingLabel.setVisibility(View.GONE);

        if (keys.isEmpty()) {
            Snackbar.make(rootView, R.string.hotkey_not_set, Snackbar.LENGTH_SHORT).show();
            return;
        }

        List<Integer> captured = new ArrayList<>(keys);
        new Thread(() -> {
            String conflict = checkHotkeyConflict(captured, app.packageName);
            if (conflict != null) {
                runOnUiThread(() -> Snackbar.make(rootView,
                        getString(R.string.hotkey_conflict, conflict),
                        Snackbar.LENGTH_LONG).show());
                return;
            }
            String hotkeyJson = Converters.fromIntList(captured);
            db.lokkerAppDao().setHotkey(app.packageName, hotkeyJson);
            runOnUiThread(() -> {
                Snackbar.make(rootView,
                        getString(R.string.hotkey_app_saved, app.appLabel),
                        Snackbar.LENGTH_SHORT).show();
                dialog.dismiss();
                loadData();
            });
        }).start();
    }

    // ── Conflict checking ─────────────────────────────────────────────

    /**
     * Check if the given hotkey sequence conflicts with any existing hotkey.
     *
     * @param sequence      the recorded key sequence.
     * @param excludeTarget {@code "lokker"} to exclude the Lokker hotkey from
     *                      the check, or a package name to exclude that app's
     *                      hotkey, or {@code null} to check everything.
     * @return a human-readable name of the conflicting assignment, or
     *         {@code null} if no conflict.
     */
    private String checkHotkeyConflict(List<Integer> sequence, String excludeTarget) {
        // Check Lokker hotkey
        if (!"lokker".equals(excludeTarget)) {
            HotkeyMap map = db.hotkeyMapDao().get();
            if (map != null && map.lokkerHotkey != null
                    && map.lokkerHotkey.equals(sequence)) {
                return getString(R.string.section_lokker_hotkey);
            }
        }

        // Check per-app hotkeys
        List<LokkerApp> apps = db.lokkerAppDao().getAll();
        if (apps != null) {
            for (LokkerApp app : apps) {
                if (app.packageName.equals(excludeTarget)) continue;
                if (app.hotkeySequence != null
                        && app.hotkeySequence.equals(sequence)) {
                    return app.appLabel != null ? app.appLabel : app.packageName;
                }
            }
        }

        return null;
    }

    // ── Key badge widget ────────────────────────────────────────────────

    private View createKeyBadge(int keyCode) {
        TextView badge = new TextView(this);
        String name;
        if (keyCode < 0) {
            name = "LONG " + android.view.KeyEvent.keyCodeToString(-keyCode)
                    .replace("KEYCODE_", "");
        } else {
            name = android.view.KeyEvent.keyCodeToString(keyCode)
                    .replace("KEYCODE_", "");
        }
        badge.setText(name);
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
            int kc = keyCodes.get(i);
            if (kc < 0) {
                sb.append("LONG ");
                sb.append(android.view.KeyEvent.keyCodeToString(-kc)
                        .replace("KEYCODE_", ""));
            } else {
                sb.append(android.view.KeyEvent.keyCodeToString(kc)
                        .replace("KEYCODE_", ""));
            }
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
        LokkerAccessibilityService.stopRecording();
        if (recordTimer != null) {
            recordTimer.cancel();
        }
    }
}
