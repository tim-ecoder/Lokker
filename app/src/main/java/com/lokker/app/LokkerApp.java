package com.lokker.app;

import android.app.Application;
import android.content.ComponentName;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.lokker.app.domain.AppRepository;

/**
 * Application subclass for Lokker.
 *
 * Performs critical recovery on every process start: any apps that were
 * temporarily unhidden (pendingRehide) but not re-hidden before the process
 * died are immediately re-hidden via {@link AppRepository#recoverLeakedApps()}.
 */
public class LokkerApp extends Application {

    private static final String TAG = "LokkerApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // Ensure our accessibility service is enabled (requires WRITE_SECURE_SETTINGS).
        ensureAccessibilityServiceEnabled();

        // Recover any apps that were temporarily unhidden but leaked due to
        // process death.  This must run as early as possible to minimise the
        // window during which a hidden app is visible after a crash/kill.
        try {
            AppRepository.getInstance(this).recoverLeakedApps();
        } catch (Exception e) {
            Log.e(TAG, "Failed to recover leaked apps on startup", e);
        }
    }

    private void ensureAccessibilityServiceEnabled() {
        try {
            ComponentName cn = new ComponentName(this,
                    "com.lokker.app.service.LokkerAccessibilityService");
            String flat = cn.flattenToString();

            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null || !enabled.contains(flat)) {
                String updated = TextUtils.isEmpty(enabled)
                        ? flat
                        : enabled + ":" + flat;
                Settings.Secure.putString(
                        getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated);
                Settings.Secure.putInt(
                        getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "Auto-enabled accessibility service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to auto-enable accessibility service", e);
        }
    }
}
