package com.lokker.app;

import android.app.Application;
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

        // Recover any apps that were temporarily unhidden but leaked due to
        // process death.  This must run as early as possible to minimise the
        // window during which a hidden app is visible after a crash/kill.
        try {
            AppRepository.getInstance(this).recoverLeakedApps();
        } catch (Exception e) {
            // AppRepository may fail if the database is corrupted or
            // EncryptedSharedPreferences cannot be initialised.  Log and
            // continue -- the boot/screen receivers provide additional
            // safety nets.
            Log.e(TAG, "Failed to recover leaked apps on startup", e);
        }
    }
}
