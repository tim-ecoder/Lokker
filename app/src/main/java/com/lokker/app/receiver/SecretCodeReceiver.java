package com.lokker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.lokker.app.ui.AuthActivity;

/**
 * Receives the secret dialer code {@code *#5655#} ({@code *#LOKK#}).
 *
 * Manifest registration:
 * <pre>{@code
 * <receiver android:name=".receiver.SecretCodeReceiver" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.provider.Telephony.SECRET_CODE"/>
 *         <data android:scheme="android_secret_code" android:host="5655"/>
 *     </intent-filter>
 * </receiver>
 * }</pre>
 *
 * Launches {@link AuthActivity} as a new task so that the user can
 * authenticate and access Lokker even when the launcher icon is hidden
 * (self-hide mode).
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    private static final String TAG = "SecretCodeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        Log.i(TAG, "Secret code received -- launching AuthActivity");

        Intent authIntent = new Intent(context, AuthActivity.class);
        authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // No target_package extra -- this opens Lokker itself after auth.
        context.startActivity(authIntent);
    }
}
