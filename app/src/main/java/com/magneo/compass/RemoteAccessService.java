package com.magneo.compass;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Keeps ADB TCP and FRP healthy when the launcher activity is not in the foreground. */
public final class RemoteAccessService extends Service {
    private static final long CONNECTED_INTERVAL_MS = 30000L;
    private static final long OFFLINE_INTERVAL_MS = 5000L;

    private volatile boolean stopping;
    private Thread worker;

    public static void start(Context context) {
        context.getApplicationContext().startService(
                new Intent(context.getApplicationContext(), RemoteAccessService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        stopping = false;
        final Context app = getApplicationContext();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                // Apply root-facing preferences before FRPC/ADB make their first su request.
                RootGrantNotificationManager.applyBlocking(
                        Prefs.rootGrantNotifications(app));
                SystemLockscreenManager.setEnabledBlocking(app,
                        Prefs.systemLockscreenEnabled(app), false);
                while (!stopping) {
                    String summary = "";
                    try {
                        summary = RemoteAccessWatchdog.tickOnce(app);
                    } catch (Throwable ignored) {}
                    try {
                        boolean fastRetry = !SavedWifiAutoConnector.isConnected(app)
                                || RemoteAccessWatchdog.needsFastRetry(summary);
                        Thread.sleep(fastRetry ? OFFLINE_INTERVAL_MS : CONNECTED_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        if (stopping) return;
                    }
                }
            }
        }, "remote-access-watchdog");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
