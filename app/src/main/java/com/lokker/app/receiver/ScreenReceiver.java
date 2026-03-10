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
 * Safety-net receiver that fires on {@code SCREEN_ON}.
 *
 * Re-verifies that every app marked as hidden in the database is actually
 * hidden at the system level.  This catches any state drift that might
 * occur while the screen is off (e.g. package manager anomalies, OTA
 * updates applied during sleep).
 */
public class ScreenReceiver extends BroadcastReceiver {

    private static final String TAG = "ScreenReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            return;
        }

        Log.d(TAG, "SCREEN_ON received -- re-verifying hidden app states");

        final PendingResult pendingResult = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                reverifyHiddenStates(context);
            } catch (Exception e) {
                Log.e(TAG, "Error re-verifying hidden states on screen on", e);
            } finally {
                pendingResult.finish();
            }
        });
    }

    private void reverifyHiddenStates(Context context) {
        LokkerDatabase db = LokkerDatabase.getInstance(context);
        List<LokkerApp> hiddenApps = db.lokkerAppDao().getAllHidden();

        if (hiddenApps == null || hiddenApps.isEmpty()) {
            return;
        }

        for (LokkerApp app : hiddenApps) {
            try {
                AppRepository.setApplicationHiddenSetting(app.packageName, true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to re-verify hidden state for " + app.packageName, e);
            }
        }
    }
}
