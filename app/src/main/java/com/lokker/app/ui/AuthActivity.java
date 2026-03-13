package com.lokker.app.ui;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
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

import android.widget.Toast;
import com.lokker.app.R;
import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.domain.AppRepository;
import com.lokker.app.domain.AuthManager;

/**
 * PIN entry screen with 6-digit on-screen keypad, IME keyboard support,
 * and biometric fallback.
 */
public class AuthActivity extends AppCompatActivity {

    private static final int PIN_LENGTH = 6;

    private String targetPackage;
    private final StringBuilder pinBuffer = new StringBuilder();
    private final View[] dotViews = new View[PIN_LENGTH];
    private LinearLayout dotsContainer;
    private GridLayout keypad;
    private EditText pinInput;
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
        // Also check data URI (more reliable through PendingIntent / shortcuts)
        if (targetPackage == null) {
            Uri data = getIntent().getData();
            if (data != null && "lokker".equals(data.getScheme())
                    && "launch".equals(data.getHost())) {
                targetPackage = data.getLastPathSegment();
            }
        }

        if (!authManager.hasPassword()) {
            proceedAfterAuth();
            return;
        }

        if (authManager.isLockedOut()) {
            buildUi();
            long remaining = authManager.getLockoutRemaining();
            if (remaining > 0) {
                startLockout(remaining);
            }
        } else {
            buildUi();
        }

        if (prefs.isBiometricEnrolled()) {
            showBiometricPrompt();
        }
    }

    // ── Biometric ───────────────────────────────────────────────────────

    private void showBiometricPrompt() {
        BiometricManager mgr = BiometricManager.from(this);
        if (mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            return;
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
                    }

                    @Override
                    public void onAuthenticationFailed() {
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setNegativeButtonText(getString(R.string.cancel))
                .build();

        prompt.authenticate(info);
    }

    // ── UI construction ──────────────────────────────────────────────────

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int btnW = (int) (screenW / 4.4f);
        int btnH = dp(80);

        LinearLayout root = new LinearLayout(this);
        root.setFitsSystemWindows(true);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));
        rootView = root;

        // Spacer pushes content to bottom
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Header ───────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        header.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText(R.string.pin_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getResColor(R.color.colorOnSurface));
        title.setGravity(Gravity.CENTER);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.pin_subtitle);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
        subtitle.setGravity(Gravity.CENTER);
        header.addView(subtitle);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── PIN dots ─────────────────────────────────────────────────────
        dotsContainer = new LinearLayout(this);
        dotsContainer.setOrientation(LinearLayout.HORIZONTAL);
        dotsContainer.setGravity(Gravity.CENTER);
        for (int i = 0; i < PIN_LENGTH; i++) {
            TextView dot = new TextView(this);
            dot.setText("_");
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            dot.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            dot.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            dot.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    dp(28), LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(dp(4), 0, dp(4), 0);
            dotsContainer.addView(dot, dlp);
            dotViews[i] = dot;
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dotsLp.gravity = Gravity.CENTER_HORIZONTAL;
        dotsLp.topMargin = dp(16);
        dotsLp.bottomMargin = dp(4);
        root.addView(dotsContainer, dotsLp);

        // Lockout text
        lockoutText = new TextView(this);
        lockoutText.setTextColor(getResColor(R.color.colorError));
        lockoutText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lockoutText.setGravity(Gravity.CENTER);
        lockoutText.setVisibility(View.GONE);
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ltLp.gravity = Gravity.CENTER_HORIZONTAL;
        ltLp.topMargin = dp(4);
        root.addView(lockoutText, ltLp);

        // ── Hidden EditText for IME keyboard input ───────────────────────
        pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTextColor(Color.TRANSPARENT);
        pinInput.setBackgroundColor(Color.TRANSPARENT);
        pinInput.setCursorVisible(false);
        pinInput.setTextSize(1);
        pinInput.setMaxLines(1);
        pinInput.setSingleLine(true);
        pinInput.setFocusable(true);
        pinInput.setFocusableInTouchMode(true);
        root.addView(pinInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        pinInput.addTextChangedListener(imeWatcher);
        pinInput.requestFocus();

        // ── Keypad 4x3 grid ─────────────────────────────────────────────
        keypad = new GridLayout(this);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setUseDefaultMargins(false);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9",
                "\uD83D\uDD13", "0", "\u232B"};
        for (String key : keys) {
            if ("\uD83D\uDD13".equals(key)) {
                // Biometric button
                ImageButton bioBtn = new ImageButton(this);
                bioBtn.setImageResource(android.R.drawable.ic_dialog_info);
                bioBtn.setBackgroundColor(Color.TRANSPARENT);
                bioBtn.setContentDescription("Biometric");
                bioBtn.setOnClickListener(v -> {
                    if (prefs.isBiometricEnrolled()) showBiometricPrompt();
                });
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = btnW;
                glp.height = btnH;
                glp.setGravity(Gravity.CENTER);
                keypad.addView(bioBtn, glp);
            } else if ("\u232B".equals(key)) {
                // Backspace
                ImageButton backspace = new ImageButton(this);
                backspace.setImageResource(android.R.drawable.ic_input_delete);
                backspace.setBackgroundColor(Color.TRANSPARENT);
                backspace.setContentDescription("Backspace");
                backspace.setOnClickListener(v -> onBackspacePressed());
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = btnW;
                glp.height = btnH;
                glp.setGravity(Gravity.CENTER);
                keypad.addView(backspace, glp);
            } else {
                // Digit button
                TextView btn = new TextView(this);
                btn.setText(key);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setTextColor(getResColor(R.color.colorOnSurface));
                btn.setGravity(Gravity.CENTER);
                btn.setClickable(true);
                btn.setFocusable(true);
                btn.setBackgroundColor(Color.TRANSPARENT);

                btn.setOnClickListener(v -> onDigitPressed(key));
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = btnW;
                glp.height = btnH;
                glp.setGravity(Gravity.CENTER);
                keypad.addView(btn, glp);
            }
        }

        LinearLayout.LayoutParams kpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        kpLp.gravity = Gravity.CENTER_HORIZONTAL;
        kpLp.topMargin = dp(8);
        kpLp.bottomMargin = dp(16);
        root.addView(keypad, kpLp);

        setContentView(root);
    }

    // ── IME TextWatcher (syncs IME input → pinBuffer → dots) ─────────

    private final TextWatcher imeWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (lockedOut) {
                s.clear();
                return;
            }
            if (s.length() > PIN_LENGTH) {
                s.delete(PIN_LENGTH, s.length());
                return;
            }
            // Sync IME → pinBuffer
            pinBuffer.setLength(0);
            pinBuffer.append(s);
            updateDots();
            if (pinBuffer.length() == PIN_LENGTH) {
                dotsContainer.post(() -> validatePin());
            }
        }
    };

    // ── On-screen keypad logic ───────────────────────────────────────────

    private void onDigitPressed(String digit) {
        if (lockedOut) return;
        if (pinBuffer.length() >= PIN_LENGTH) return;

        pinBuffer.append(digit);
        syncInputFromBuffer();
        updateDots();

        if (pinBuffer.length() == PIN_LENGTH) {
            dotsContainer.post(this::validatePin);
        }
    }

    private void onBackspacePressed() {
        if (lockedOut) return;
        if (pinBuffer.length() > 0) {
            pinBuffer.deleteCharAt(pinBuffer.length() - 1);
            syncInputFromBuffer();
            updateDots();
        }
    }

    /** Push pinBuffer content into the hidden EditText without triggering imeWatcher. */
    private void syncInputFromBuffer() {
        pinInput.removeTextChangedListener(imeWatcher);
        pinInput.setText(pinBuffer);
        pinInput.setSelection(pinBuffer.length());
        pinInput.addTextChangedListener(imeWatcher);
    }

    // ── Hardware keyboard support ────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (lockedOut) return super.onKeyDown(keyCode, event);

        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            onDigitPressed(String.valueOf(keyCode - KeyEvent.KEYCODE_0));
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            onDigitPressed(String.valueOf(keyCode - KeyEvent.KEYCODE_NUMPAD_0));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (pinBuffer.length() == PIN_LENGTH) validatePin();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspacePressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Dots & validation ────────────────────────────────────────────────

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            TextView tv = (TextView) dotViews[i];
            if (i < pinBuffer.length()) {
                tv.setText("*");
                tv.setTextColor(getResColor(R.color.colorPrimary));
            } else {
                tv.setText("_");
                tv.setTextColor(getResColor(R.color.colorOnSurfaceMedium));
            }
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
            syncInputFromBuffer();
            updateDots();

            ObjectAnimator shake = ObjectAnimator.ofFloat(dotsContainer, "translationX",
                    0, dp(10), -dp(10), dp(10), -dp(10), dp(5), -dp(5), 0);
            shake.setDuration(400);
            shake.start();

            if (failCount >= authManager.getMaxFailures()) {
                authManager.setLockout();
                startLockout(authManager.getLockoutRemaining());
            } else {
                Toast.makeText(AuthActivity.this,
                        getString(R.string.attempt_count, failCount,
                                authManager.getMaxFailures()),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Lockout ─────────────────────────────────────────────────────────

    private void startLockout(long durationMs) {
        lockedOut = true;
        setKeypadEnabled(false);
        lockoutText.setVisibility(View.VISIBLE);

        if (lockoutTimer != null) lockoutTimer.cancel();

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
        pinInput.setEnabled(enabled);
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private void proceedAfterAuth() {
        if (targetPackage != null && !targetPackage.isEmpty()) {
            AppRepository repo = AppRepository.getInstance(getApplicationContext());
            new Thread(() -> {
                repo.unhideTemporarily(targetPackage);
                // Give PackageManager time to refresh its cache after unhiding
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                runOnUiThread(() -> {
                    repo.launchHiddenApp(targetPackage);
                    finish();
                });
            }).start();
        } else if (getCallingActivity() != null) {
            setResult(RESULT_OK);
            finish();
        } else {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("authenticated", true);
            startActivity(intent);
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
        if (lockoutTimer != null) lockoutTimer.cancel();
    }
}
