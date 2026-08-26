package com.magneo.compass;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Keeps ADB TCP and FRP healthy when the launcher activity is not in the foreground. */
public final class RemoteAccessService extends Service {
    private static final long INTERVAL_MS = 30000L;

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
                while (!stopping) {
                    try {
                        RemoteAccessWatchdog.tickOnce(app);
                    } catch (Throwable ignored) {}
                    try {
                        Thread.sleep(INTERVAL_MS);
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
