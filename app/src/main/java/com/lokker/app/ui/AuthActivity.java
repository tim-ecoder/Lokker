package com.lokker.app.ui;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.lokker.app.R;
import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AuthManager;

/**
 * PIN entry screen with 6-digit PIN pad and biometric fallback.
 *
 * Intent extras:
 *   "target_package" (optional) - if set, unlocks and launches that hidden app
 *                                 instead of navigating to MainActivity.
 *
 * Behaviour:
 *   - If no password is set, skips straight to the target destination.
 *   - Shows BiometricPrompt first (if enrolled), falls back to PIN pad.
 *   - Wrong PIN: shake animation on dots, snackbar error, increment fail count.
 *   - 5 consecutive failures: 30-second lockout with countdown, keypad disabled.
 *   - excludeFromRecents = true (set in manifest).
 */
public class AuthActivity extends AppCompatActivity {

    private static final int PIN_LENGTH = 6;

    private String targetPackage;
    private final StringBuilder pinBuffer = new StringBuilder();
    private final View[] dotViews = new View[PIN_LENGTH];
    private LinearLayout dotsContainer;
    private GridLayout keypad;
    private ImageButton biometricButton;
    private TextView lockoutText;
    private View rootView;

    private boolean lockedOut;
    private CountDownTimer lockoutTimer;

    private LokkerPrefs prefs;
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = LokkerPrefs.getInstance(this);
        authManager = new AuthManager(prefs);
        targetPackage = getIntent().getStringExtra("target_package");

        // If no password set, skip auth entirely
        if (!authManager.hasPassword()) {
            proceedAfterAuth();
            return;
        }

        // Check if already locked out from a previous session
        if (authManager.isLockedOut()) {
            buildUi();
            long remaining = authManager.getLockoutRemaining();
            if (remaining > 0) {
                startLockout(remaining);
            }
        } else {
            buildUi();
        }

        // Try biometric first if enrolled
        if (prefs.isBiometricEnrolled()) {
            showBiometricPrompt();
        }
    }

    // ── Biometric ───────────────────────────────────────────────────────

    private void showBiometricPrompt() {
        BiometricManager mgr = BiometricManager.from(this);
        if (mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            return; // hardware unavailable - fall back to PIN pad
        }

        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        authManager.resetFailCount();
                        proceedAfterAuth();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                            @NonNull CharSequence errString) {
                        // User cancelled or hardware error - stay on PIN screen
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Single attempt failed - biometric prompt handles retries
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setNegativeButtonText(getString(R.string.cancel))
                .build();

        prompt.authenticate(info);
    }

    // ── UI construction (programmatic) ──────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));
        int pad = dp(32);
        root.setPadding(pad, dp(64), pad, dp(32));
        rootView = root;

        // Logo
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.bottomMargin = dp(16);
        root.addView(logo, logoLp);

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.pin_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.pin_subtitle);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(32);
        root.addView(subtitle, subLp);

        // PIN dots
        dotsContainer = new LinearLayout(this);
        dotsContainer.setOrientation(LinearLayout.HORIZONTAL);
        dotsContainer.setGravity(Gravity.CENTER);
        for (int i = 0; i < PIN_LENGTH; i++) {
            View dot = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setSize(dp(16), dp(16));
            bg.setStroke(dp(2), getResColor(R.color.colorPrimary));
            bg.setColor(Color.TRANSPARENT);
            dot.setBackground(bg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(16), dp(16));
            dlp.setMargins(dp(8), 0, dp(8), 0);
            dotsContainer.addView(dot, dlp);
            dotViews[i] = dot;
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dotsLp.bottomMargin = dp(16);
        root.addView(dotsContainer, dotsLp);

        // Lockout text (hidden by default)
        lockoutText = new TextView(this);
        lockoutText.setTextColor(getResColor(R.color.colorError));
        lockoutText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lockoutText.setGravity(Gravity.CENTER);
        lockoutText.setVisibility(View.GONE);
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ltLp.bottomMargin = dp(16);
        root.addView(lockoutText, ltLp);

        // Keypad 4x3 grid
        keypad = new GridLayout(this);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setUseDefaultMargins(true);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "\u232B"};
        for (String key : keys) {
            if (key.isEmpty()) {
                // Empty cell - placeholder for biometric button
                biometricButton = new ImageButton(this);
                biometricButton.setImageResource(android.R.drawable.ic_dialog_info);
                biometricButton.setBackgroundColor(Color.TRANSPARENT);
                biometricButton.setContentDescription("Biometric");
                biometricButton.setOnClickListener(v -> {
                    if (prefs.isBiometricEnrolled()) {
                        showBiometricPrompt();
                    }
                });
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = dp(72);
                glp.height = dp(56);
                glp.setGravity(Gravity.CENTER);
                keypad.addView(biometricButton, glp);
            } else if ("\u232B".equals(key)) {
                ImageButton backspace = new ImageButton(this);
                backspace.setImageResource(android.R.drawable.ic_input_delete);
                backspace.setBackgroundColor(Color.TRANSPARENT);
                backspace.setContentDescription("Backspace");
                backspace.setOnClickListener(v -> onBackspacePressed());
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = dp(72);
                glp.height = dp(56);
                glp.setGravity(Gravity.CENTER);
                keypad.addView(backspace, glp);
            } else {
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(getResColor(R.color.colorOnSurface));
                btn.setGravity(Gravity.CENTER);
                btn.setClickable(true);
                btn.setFocusable(true);

                TypedValue outValue = new TypedValue();
                getTheme().resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, outValue, true);
                btn.setBackgroundResource(outValue.resourceId);

                btn.setOnClickListener(v -> onDigitPressed(key));
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = dp(72);
                glp.height = dp(56);
                glp.setGravity(Gravity.CENTER);
                keypad.addView(btn, glp);
            }
        }

        LinearLayout.LayoutParams kpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        kpLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(keypad, kpLp);

        setContentView(root);
    }

    // ── Keypad logic ────────────────────────────────────────────────────

    private void onDigitPressed(String digit) {
        if (lockedOut) return;
        if (pinBuffer.length() >= PIN_LENGTH) return;

        pinBuffer.append(digit);
        updateDots();

        if (pinBuffer.length() == PIN_LENGTH) {
            // Small delay so user sees the last dot fill
            rootView.postDelayed(this::validatePin, 150);
        }
    }

    private void onBackspacePressed() {
        if (lockedOut) return;
        if (pinBuffer.length() > 0) {
            pinBuffer.deleteCharAt(pinBuffer.length() - 1);
            updateDots();
        }
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setSize(dp(16), dp(16));
            if (i < pinBuffer.length()) {
                bg.setColor(getResColor(R.color.colorPrimary));
            } else {
                bg.setStroke(dp(2), getResColor(R.color.colorPrimary));
                bg.setColor(Color.TRANSPARENT);
            }
            dotViews[i].setBackground(bg);
        }
    }

    private void validatePin() {
        String entered = pinBuffer.toString();
        if (authManager.verifyPassword(entered)) {
            authManager.resetFailCount();
            proceedAfterAuth();
        } else {
            authManager.incrementFailCount();
            int failCount = authManager.getFailCount();
            pinBuffer.setLength(0);
            updateDots();

            // Shake animation on dots
            ObjectAnimator shake = ObjectAnimator.ofFloat(dotsContainer, "translationX",
                    0, dp(10), -dp(10), dp(10), -dp(10), dp(5), -dp(5), 0);
            shake.setDuration(400);
            shake.start();

            if (failCount >= authManager.getMaxFailures()) {
                authManager.setLockout();
                startLockout(authManager.getLockoutRemaining());
            } else {
                Snackbar.make(rootView,
                        getString(R.string.attempt_count, failCount,
                                authManager.getMaxFailures()),
                        Snackbar.LENGTH_SHORT).show();
            }
        }
    }

    // ── Lockout ─────────────────────────────────────────────────────────

    private void startLockout(long durationMs) {
        lockedOut = true;
        setKeypadEnabled(false);
        lockoutText.setVisibility(View.VISIBLE);

        if (lockoutTimer != null) {
            lockoutTimer.cancel();
        }

        lockoutTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secs = (int) (millisUntilFinished / 1000);
                lockoutText.setText(getString(R.string.lockout_msg, secs));
            }

            @Override
            public void onFinish() {
                lockedOut = false;
                authManager.resetFailCount();
                lockoutText.setVisibility(View.GONE);
                setKeypadEnabled(true);
            }
        }.start();
    }

    private void setKeypadEnabled(boolean enabled) {
        for (int i = 0; i < keypad.getChildCount(); i++) {
            keypad.getChildAt(i).setEnabled(enabled);
            keypad.getChildAt(i).setAlpha(enabled ? 1f : 0.3f);
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private void proceedAfterAuth() {
        if (targetPackage != null && !targetPackage.isEmpty()) {
            // Unhide temporarily and launch via the database/system layer.
            // In production this delegates to AppRepository; here we use
            // a background thread through the database directly.
            LokkerDatabase db = LokkerDatabase.getInstance(getApplicationContext());
            new Thread(() -> {
                db.lokkerAppDao().setHidden(targetPackage, false);
                runOnUiThread(() -> {
                    // Attempt to launch the hidden app
                    Intent launchIntent = getPackageManager()
                            .getLaunchIntentForPackage(targetPackage);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchIntent);
                    }
                    finish();
                });
            }).start();
        } else {
            Intent main = new Intent(this, MainActivity.class);
            startActivity(main);
            finish();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lockoutTimer != null) {
            lockoutTimer.cancel();
        }
    }
}
