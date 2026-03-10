package com.lokker.app.domain;

import android.util.Base64;

import com.lokker.app.data.LokkerPrefs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password management using PBKDF2-HMAC-SHA256.
 *
 * Stored format in EncryptedPrefs key "pw_hash":
 *     Base64(salt):Base64(hash)
 *
 * Lockout: after 5 consecutive failures the user is locked out for 30 seconds.
 */
public class AuthManager {

    private static final String KEY_PW_HASH = "pw_hash";
    private static final String KEY_FAIL_COUNT = "fail_count";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";

    private static final int SALT_LENGTH = 16;
    private static final int ITERATIONS = 10_000;
    private static final int KEY_LENGTH = 256;
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_DURATION_MS = 30_000L;

    private final LokkerPrefs prefs;

    public AuthManager(LokkerPrefs prefs) {
        this.prefs = prefs;
    }

    // ── Password management ─────────────────────────────────────────────

    /**
     * Hash and store a new password.  Generates a fresh 16-byte salt,
     * derives a 256-bit key with PBKDF2-HMAC-SHA256 (310 000 iterations),
     * and persists "Base64(salt):Base64(hash)" in encrypted preferences.
     */
    public void setPassword(String password) {
        try {
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);

            byte[] hash = deriveKey(password, salt);

            String encoded = Base64.encodeToString(salt, Base64.NO_WRAP)
                    + ":"
                    + Base64.encodeToString(hash, Base64.NO_WRAP);
            prefs.putString(KEY_PW_HASH, encoded);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 not available", e);
        }
    }

    /**
     * Verify {@code input} against the stored hash.
     *
     * @return {@code true} if the password matches, {@code false} otherwise
     *         (including when no password has been set).
     */
    public boolean verifyPassword(String input) {
        String stored = prefs.getString(KEY_PW_HASH, null);
        if (stored == null) return false;

        String[] parts = stored.split(":");
        if (parts.length != 2) return false;

        try {
            byte[] salt = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] storedHash = Base64.decode(parts[1], Base64.NO_WRAP);

            // Try current iteration count first
            byte[] inputHash = deriveKey(input, salt);
            if (MessageDigest.isEqual(storedHash, inputHash)) return true;

            // Fall back to legacy 310k iterations for old hashes
            byte[] legacyHash = deriveKey(input, salt, 310_000);
            if (MessageDigest.isEqual(storedHash, legacyHash)) {
                // Re-hash with new iteration count
                setPassword(input);
                return true;
            }
            return false;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 not available", e);
        }
    }

    /**
     * @return {@code true} if a password hash is stored.
     */
    public boolean hasPassword() {
        String stored = prefs.getString(KEY_PW_HASH, null);
        return stored != null && !stored.isEmpty();
    }

    // ── Failure tracking ────────────────────────────────────────────────

    /**
     * @return the current number of consecutive authentication failures.
     */
    public int getFailCount() {
        return Integer.parseInt(prefs.getString(KEY_FAIL_COUNT, "0"));
    }

    /**
     * Increment the consecutive-failure counter by one.
     */
    public void incrementFailCount() {
        int count = getFailCount() + 1;
        prefs.putString(KEY_FAIL_COUNT, String.valueOf(count));
    }

    /**
     * Reset the consecutive-failure counter to zero.
     */
    public void resetFailCount() {
        prefs.putString(KEY_FAIL_COUNT, "0");
    }

    // ── Lockout ─────────────────────────────────────────────────────────

    /**
     * @return {@code true} if the user is currently locked out (lockout
     *         timestamp is in the future).
     */
    public boolean isLockedOut() {
        long until = getLockoutUntil();
        return until > 0 && System.currentTimeMillis() < until;
    }

    /**
     * @return remaining lockout time in milliseconds, or 0 if not locked out.
     */
    public long getLockoutRemaining() {
        long until = getLockoutUntil();
        if (until <= 0) return 0;
        long remaining = until - System.currentTimeMillis();
        return Math.max(remaining, 0);
    }

    /**
     * Set the lockout timestamp to now + {@link #LOCKOUT_DURATION_MS}.
     * Called after {@link #MAX_FAILURES} consecutive failures.
     */
    public void setLockout() {
        long until = System.currentTimeMillis() + LOCKOUT_DURATION_MS;
        prefs.putString(KEY_LOCKOUT_UNTIL, String.valueOf(until));
    }

    /**
     * @return the maximum number of failures before lockout.
     */
    public int getMaxFailures() {
        return MAX_FAILURES;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private long getLockoutUntil() {
        String raw = prefs.getString(KEY_LOCKOUT_UNTIL, "0");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private byte[] deriveKey(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        return deriveKey(password, salt, ITERATIONS);
    }

    private byte[] deriveKey(String password, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
        return factory.generateSecret(spec).getEncoded();
    }
}
