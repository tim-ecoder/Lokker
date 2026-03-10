package com.lokker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.lokker.app.data.LokkerPrefs;
import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;

import java.util.concurrent.Executors;

/**
 * Monitors {@code PACKAGE_REPLACED} and {@code PACKAGE_ADDED} intents.
 *
 * <ul>
 *   <li>If the replaced/added package is Lokker itself: reset the
 *       {@code self_hidden} preference to {@code false} so that the launcher
 *       alias state stays consistent after an update/reinstall (the system
 *       resets {@code setComponentEnabledSetting} to the manifest default).</li>
 *   <li>If the package is a managed hidden app: re-apply the hidden state as
 *       a safety net.  The system normally preserves
 *       {@code setApplicationHiddenSetting} across updates, but this guards
 *       against any edge cases.</li>
 * </ul>
 */
public class PackageMonitor extends BroadcastReceiver {

    private static final String TAG = "PackageMonitor";
    private static final String LOKKER_PACKAGE = "com.lokker.app";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            return;
        }

        Uri data = intent.getData();
        if (data == null) return;

        String packageName = data.getSchemeSpecificPart();
        if (packageName == null || packageName.isEmpty()) return;

        if (LOKKER_PACKAGE.equals(packageName)) {
            handleSelfUpdate(context);
        } else {
            handleManagedPackageUpdate(context, packageName);
        }
    }

    /**
     * Lokker was updated or reinstalled.  The system resets the launcher
     * activity-alias to the manifest default (enabled), so clear the
     * {@code self_hidden} flag to keep prefs consistent.
     */
    private void handleSelfUpdate(Context context) {
        Log.i(TAG, "Lokker package updated/reinstalled -- resetting self_hidden pref");
        try {
            LokkerPrefs prefs = LokkerPrefs.getInstance(context);
            prefs.putBoolean("self_hidden", false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset self_hidden preference", e);
        }
    }

    /**
     * A managed app was updated or reinstalled.  Re-apply its hidden state
     * if it is supposed to be hidden.
     */
    private void handleManagedPackageUpdate(Context context, String packageName) {
        final PendingResult pendingResult = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                LokkerDatabase db = LokkerDatabase.getInstance(context);
                LokkerApp app = db.lokkerAppDao().get(packageName);

                if (app == null) {
                    // Not a managed app -- nothing to do.
                    return;
                }

                if (app.hidden) {
                    AppRepository.setApplicationHiddenSetting(packageName, true);
                    Log.d(TAG, "Re-applied hidden state for updated package " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to re-hide updated package " + packageName, e);
            } finally {
                pendingResult.finish();
            }
        });
    }
}
