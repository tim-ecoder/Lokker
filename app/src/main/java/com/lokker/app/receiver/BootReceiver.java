package com.lokker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.domain.AppRepository;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Safety-net receiver that fires on {@code BOOT_COMPLETED}.
 *
 * Although {@code setApplicationHiddenSetting} state survives reboots
 * (persisted in {@code packages.xml}), this receiver re-applies the hidden
 * flag for every app in the database as an extra precaution against edge-case
 * state drift (e.g. factory-reset protection, OTA side-effects).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Log.i(TAG, "BOOT_COMPLETED received -- re-verifying hidden app states");

        // Database access must happen off the main thread.
        final PendingResult pendingResult = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                reapplyHiddenStates(context);
            } catch (Exception e) {
                Log.e(TAG, "Error re-applying hidden states after boot", e);
            } finally {
                pendingResult.finish();
            }
        });
    }

    private void reapplyHiddenStates(Context context) {
        LokkerDatabase db = LokkerDatabase.getInstance(context);
        List<LokkerApp> hiddenApps = db.lokkerAppDao().getAllHidden();

        if (hiddenApps == null || hiddenApps.isEmpty()) {
            Log.i(TAG, "No hidden apps to re-apply");
            return;
        }

        for (LokkerApp app : hiddenApps) {
            try {
                AppRepository.setApplicationHiddenSetting(app.packageName, true);
                Log.d(TAG, "Re-applied hidden state for " + app.packageName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to re-hide " + app.packageName, e);
            }
        }

        Log.i(TAG, "Re-applied hidden state for " + hiddenApps.size() + " app(s)");
    }
}
