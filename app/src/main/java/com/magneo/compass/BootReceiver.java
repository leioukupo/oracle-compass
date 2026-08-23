package com.magneo.compass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Applies background startup toggles after boot without relying on third-party helper apps. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        final PendingResult pr = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                com.magneo.compass.web.AdbManager.ensureAutoStart(app);
            } finally {
                pr.finish();
            }
        }, "boot-adb").start();
    }
}
