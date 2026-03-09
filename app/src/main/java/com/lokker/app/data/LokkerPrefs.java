package com.lokker.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Encrypted SharedPreferences wrapper for Lokker.
 *
 * Keys:
 *   "onboarding_complete"  (boolean) - true after the user finishes first-run setup
 *   "service_enabled"      (boolean) - true when the accessibility service is active
 *   "biometric_enrolled"   (boolean) - true when biometric authentication is enrolled
 *   "stealth_mode"         (boolean) - true when stealth/decoy mode is enabled
 *   "theme"                (String)  - UI theme preference ("light", "dark", "system")
 */
public class LokkerPrefs {

    private static final String PREFS_FILE = "lokker_secure_prefs";

    // Preference keys
    public static final String KEY_ONBOARDING_COMPLETE = "onboarding_complete";
    public static final String KEY_SERVICE_ENABLED = "service_enabled";
    public static final String KEY_BIOMETRIC_ENROLLED = "biometric_enrolled";
    public static final String KEY_STEALTH_MODE = "stealth_mode";
    public static final String KEY_THEME = "theme";

    private static volatile LokkerPrefs INSTANCE;
    private final SharedPreferences prefs;

    private LokkerPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException("Failed to create encrypted prefs", e);
        }
    }

    public static LokkerPrefs getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (LokkerPrefs.class) {
                if (INSTANCE == null) {
                    INSTANCE = new LokkerPrefs(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public boolean isOnboardingComplete() {
        return getBoolean(KEY_ONBOARDING_COMPLETE, false);
    }

    public void setOnboardingComplete(boolean complete) {
        putBoolean(KEY_ONBOARDING_COMPLETE, complete);
    }

    public boolean isServiceEnabled() {
        return getBoolean(KEY_SERVICE_ENABLED, false);
    }

    public void setServiceEnabled(boolean enabled) {
        putBoolean(KEY_SERVICE_ENABLED, enabled);
    }

    public boolean isBiometricEnrolled() {
        return getBoolean(KEY_BIOMETRIC_ENROLLED, false);
    }

    public void setBiometricEnrolled(boolean enrolled) {
        putBoolean(KEY_BIOMETRIC_ENROLLED, enrolled);
    }

    public boolean isStealthMode() {
        return getBoolean(KEY_STEALTH_MODE, false);
    }

    public void setStealthMode(boolean enabled) {
        putBoolean(KEY_STEALTH_MODE, enabled);
    }

    public String getTheme() {
        return getString(KEY_THEME, "system");
    }

    public void setTheme(String theme) {
        putString(KEY_THEME, theme);
    }

    /**
     * Returns the underlying SharedPreferences for operations not covered
     * by the convenience methods (e.g. putStringSet, remove).
     */
    public SharedPreferences getPrefs() {
        return prefs;
    }
}
