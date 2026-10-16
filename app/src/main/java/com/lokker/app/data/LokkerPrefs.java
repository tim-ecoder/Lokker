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
 * Keys (owners in parentheses):
 *   "pw_hash"                 (String)    - PBKDF2 hash, Base64(salt):Base64(hash) (AuthManager)
 *   "fail_count"              (String)    - consecutive failed PIN attempts (AuthManager)
 *   "lockout_until"           (String)    - lockout end, epoch ms (AuthManager)
 *   "biometric_enrolled"      (boolean)   - biometric unlock enabled (Settings / AuthActivity)
 *   "pending_rehide"          (StringSet) - temporarily unhidden packages (AppRepository)
 *   "pending_rehide_on_close" (StringSet) - "unhide until closed" packages (AppRepository)
 *   "unhide_all_snapshot"     (String)    - JSON array for "Hide all apps" (AppRepository)
 */
public class LokkerPrefs {

    private static final String PREFS_FILE = "lokker_secure_prefs";

    // Preference keys
    public static final String KEY_BIOMETRIC_ENROLLED = "biometric_enrolled";

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

    public boolean isBiometricEnrolled() {
        return getBoolean(KEY_BIOMETRIC_ENROLLED, false);
    }

    public void setBiometricEnrolled(boolean enrolled) {
        putBoolean(KEY_BIOMETRIC_ENROLLED, enrolled);
    }

    /**
     * Returns the underlying SharedPreferences for operations not covered
     * by the convenience methods (e.g. putStringSet, remove).
     */
    public SharedPreferences getPrefs() {
        return prefs;
    }
}
