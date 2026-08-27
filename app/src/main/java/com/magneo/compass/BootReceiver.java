package com.magneo.compass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the HOME screen and background services after a full or quick boot. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "OracleBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        RootGrantNotificationManager.applySaved(context);
        SystemLockscreenManager.applySavedAsync(context);
        try {
            SavedWifiAutoConnector.ensureConnected(context);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to restore saved WiFi after boot", t);
        }
        try {
            Intent home = new Intent(context, MainActivity.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(home);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to launch HOME after boot", t);
        }
        try {
            RemoteAccessService.start(context);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start remote access after boot", t);
        }
    }
}
