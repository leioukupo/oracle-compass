package com.magneo.compass;

/** Keeps remote maintenance access alive while the app process is running. */
public final class RemoteAccessWatchdog {
    private static final long INTERVAL_MS = 60000L;
    private static volatile boolean started;

    private RemoteAccessWatchdog() {}

    public static String tickOnce(android.content.Context context) {
        android.content.Context app = context.getApplicationContext();
        StringBuilder out = new StringBuilder();
        try {
            out.append("adb: ")
                    .append(com.magneo.compass.web.AdbManager.ensureAutoStart(app));
        } catch (Throwable t) {
            out.append("adb failed: ").append(t.getMessage());
        }
        try {
            boolean hasCfg = !Prefs.get(app, Prefs.K_FRPC_CONFIG, "").trim().isEmpty();
            out.append("\nfrpc: cfg=").append(hasCfg);
            if (hasCfg) {
                out.append(" ").append(com.magneo.compass.frp.FrpcManager.ensureStarted(app));
            }
        } catch (Throwable t) {
            out.append("\nfrpc failed: ").append(t.getMessage());
        }
        return out.toString();
    }

    public static void start(android.content.Context context) {
        synchronized (RemoteAccessWatchdog.class) {
            if (started) return;
            started = true;
        }
        final android.content.Context app = context.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tickOnce(app);
                }
            }
        }, "remote-access-watchdog");
        t.setDaemon(true);
        t.start();
    }
}
