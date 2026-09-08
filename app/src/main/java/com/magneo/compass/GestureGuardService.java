package com.magneo.compass;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;

/** START_STICKY application-side half of the Gesture accessibility guardian. */
public final class GestureGuardService extends Service {
    private static final long PERIOD_MS = 15_000L;
    private final Handler handler = new Handler();
    private boolean receiverRegistered;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            new Thread(() -> GestureGuardManager.ensure(getApplicationContext()), "gesture-guard-check").start();
            handler.postDelayed(this, PERIOD_MS);
        }
    };
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REPLACED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                String pkg = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
                if (!GestureGuardManager.TARGET_PACKAGE.equals(pkg)) return;
            }
            scheduleNow();
        }
    };

    public static void start(Context context) {
        try { context.startService(new Intent(context.getApplicationContext(), GestureGuardService.class)); }
        catch (Throwable ignored) {}
    }

    public static void stop(Context context) {
        try { context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), GestureGuardService.class)); }
        catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(events, screen);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(events, packages);
        receiverRegistered = true;
        scheduleNow();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleNow();
        return START_STICKY;
    }

    private void scheduleNow() {
        handler.removeCallbacks(check);
        handler.post(check);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(check);
        if (receiverRegistered) try { unregisterReceiver(events); } catch (Throwable ignored) {}
        receiverRegistered = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
