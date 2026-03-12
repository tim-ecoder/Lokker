package com.lokker.app.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import android.widget.Toast;
import com.lokker.app.R;
import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.domain.AuthManager;

/**
 * Create or change the application password.
 *
 * Modes (determined by intent extra "is_change"):
 *   - Create: new password + confirm. No current password field.
 *   - Change: current password + new password + confirm.
 *
 * Validation:
 *   - Current password must match (change mode).
 *   - New password must be >= 6 characters.
 *   - Confirm password must match new password.
 *
 * On success: saves the password via AuthManager and finishes with RESULT_OK.
 */
public class ChangePasswordActivity extends AppCompatActivity {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private boolean isChangeMode;
    private AuthManager authManager;

    private EditText currentPwField;
    private EditText newPwField;
    private EditText confirmPwField;
    private LinearLayout rootLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LokkerPrefs prefs = LokkerPrefs.getInstance(this);
        authManager = new AuthManager(prefs);
        isChangeMode = getIntent().getBooleanExtra("is_change", false);

        buildUi();
    }

    // ── UI construction ─────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setFitsSystemWindows(true);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(getColorAttr(android.R.attr.colorBackground));

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(isChangeMode
                ? R.string.change_password : R.string.set_password);
        toolbar.setTitleTextColor(getResColor(R.color.colorOnSurface));
        toolbar.setBackgroundColor(getResColor(R.color.colorSurfaceVariant));
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        outer.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scrollable content
        ScrollView scroll = new ScrollView(this);
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        rootLayout.setPadding(pad, dp(32), pad, pad);

        // Current password field (change mode only)
        if (isChangeMode) {
            TextView currentLabel = createLabel(R.string.current_password);
            rootLayout.addView(currentLabel);
            currentPwField = createPasswordField(
                    getString(R.string.current_password));
            rootLayout.addView(currentPwField, createFieldLp());
        }

        // New password field
        TextView newLabel = createLabel(R.string.new_password);
        rootLayout.addView(newLabel);
        newPwField = createPasswordField(getString(R.string.new_password));
        rootLayout.addView(newPwField, createFieldLp());

        // Confirm password field
        TextView confirmLabel = createLabel(
                isChangeMode ? R.string.confirm_new_password : R.string.confirm_password);
        rootLayout.addView(confirmLabel);
        confirmPwField = createPasswordField(
                getString(isChangeMode
                        ? R.string.confirm_new_password : R.string.confirm_password));
        confirmPwField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        rootLayout.addView(confirmPwField, createFieldLp());

        // Save button
        MaterialButton saveBtn = new MaterialButton(this);
        saveBtn.setText(R.string.save);
        saveBtn.setOnClickListener(v -> onSaveClicked());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(24);
        rootLayout.addView(saveBtn, btnLp);

        scroll.addView(rootLayout, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(outer);
    }

    private TextView createLabel(int stringRes) {
        TextView label = new TextView(this);
        label.setText(stringRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(getResColor(R.color.colorOnSurface));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        label.setLayoutParams(lp);
        return label;
    }

    private EditText createPasswordField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTextColor(getResColor(R.color.colorOnSurface));
        field.setHintTextColor(getResColor(R.color.colorOnSurfaceMedium));
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), getResColor(R.color.colorDivider));
        bg.setColor(getResColor(R.color.colorSurfaceVariant));
        field.setBackground(bg);

        int p = dp(12);
        field.setPadding(p, p, p, p);

        // Must be set after setBackground and setInputType
        field.setTransformationMethod(
                android.text.method.PasswordTransformationMethod.getInstance());
        return field;
    }

    private LinearLayout.LayoutParams createFieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        return lp;
    }

    // ── Validation ──────────────────────────────────────────────────────

    private void onSaveClicked() {
        // Reset field borders
        resetFieldBorder(newPwField);
        resetFieldBorder(confirmPwField);
        if (currentPwField != null) resetFieldBorder(currentPwField);

        // Validate current password in change mode
        if (isChangeMode) {
            String current = currentPwField.getText().toString();
            if (!authManager.verifyPassword(current)) {
                setFieldError(currentPwField);
                Toast.makeText(this, R.string.pw_wrong_current,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String newPw = newPwField.getText().toString();
        String confirmPw = confirmPwField.getText().toString();

        // Check minimum length
        if (newPw.length() < MIN_PASSWORD_LENGTH) {
            setFieldError(newPwField);
            Toast.makeText(this, R.string.pw_too_short,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Check passwords match
        if (!newPw.equals(confirmPw)) {
            setFieldError(confirmPwField);
            Toast.makeText(this, R.string.pw_mismatch,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Save password
        authManager.setPassword(newPw);

        Toast.makeText(this,
                isChangeMode ? R.string.pw_change_success : R.string.pw_set_success,
                Toast.LENGTH_SHORT).show();

        setResult(RESULT_OK);
        rootLayout.postDelayed(this::finish, 800);
    }

    private void setFieldError(EditText field) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(2), getResColor(R.color.colorError));
        bg.setColor(getResColor(R.color.colorSurfaceVariant));
        field.setBackground(bg);
    }

    private void resetFieldBorder(EditText field) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), getResColor(R.color.colorDivider));
        bg.setColor(getResColor(R.color.colorSurfaceVariant));
        field.setBackground(bg);
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
