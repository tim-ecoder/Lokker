package com.lokker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.lokker.app.domain.AppRepository;
import com.lokker.app.ui.AuthActivity;

/**
 * Receives secret dialer codes:
 * <ul>
 *   <li>{@code *#565537#} — open Lokker (through auth)</li>
 *   <li>{@code *#5655377469#} ({@code *#LOKKERSHOW#}) — unhide all hidden apps</li>
 * </ul>
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    private static final String TAG = "SecretCodeReceiver";
    private static final String HOST_UNHIDE_ALL = "5655377469";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        Uri data = intent.getData();
        String host = data != null ? data.getHost() : null;

        // Dialpad: *#5655377469#
        if (HOST_UNHIDE_ALL.equals(host)) {
            Log.i(TAG, "Unhiding all hidden apps (system-wide)");
            new Thread(() -> {
                AppRepository repo = AppRepository.getInstance(context);
                repo.unhideAllSystemWide();
            }).start();
            Toast.makeText(context, "Lokker: all apps unhidden",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Dialpad: *#565537# — open Lokker
        Log.i(TAG, "Secret code received -- launching AuthActivity");
        Intent authIntent = new Intent(context, AuthActivity.class);
        authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(authIntent);
    }
}
