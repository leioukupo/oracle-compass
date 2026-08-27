package com.magneo.compass;

/** Keeps remote maintenance access alive while the app process is running. */
public final class RemoteAccessWatchdog {
    private static volatile long frpcSyncedRecoveryAt;

    private RemoteAccessWatchdog() {}

    public static String tickOnce(android.content.Context context) {
        android.content.Context app = context.getApplicationContext();
        StringBuilder out = new StringBuilder();
        try {
            boolean connected = SavedWifiAutoConnector.ensureConnected(app);
            out.append("wifi: connected=").append(connected).append(' ')
                    .append(SavedWifiAutoConnector.detail());
        } catch (Throwable t) {
            out.append("wifi failed: ").append(t.getMessage());
        }
        long adbRecoveryAt = 0L;
        try {
            out.append("\nadb: ")
                    .append(com.magneo.compass.web.AdbManager.ensureAutoStart(app));
            adbRecoveryAt = com.magneo.compass.web.AdbManager.lastRecoveryAt();
        } catch (Throwable t) {
            out.append("adb failed: ").append(t.getMessage());
        }
        try {
            boolean hasCfg = !Prefs.get(app, Prefs.K_FRPC_CONFIG, "").trim().isEmpty();
            out.append("\nfrpc: cfg=").append(hasCfg);
            if (hasCfg) {
                if (adbRecoveryAt > 0 && adbRecoveryAt > frpcSyncedRecoveryAt) {
                    frpcSyncedRecoveryAt = adbRecoveryAt;
                    out.append(" resync after adb recovery: ")
                            .append(com.magneo.compass.frp.FrpcManager.start(app));
                } else {
                    out.append(" ").append(com.magneo.compass.frp.FrpcManager.ensureStarted(app));
                }
            }
        } catch (Throwable t) {
            out.append("\nfrpc failed: ").append(t.getMessage());
        }
        return out.toString();
    }

    public static boolean needsFastRetry(String summary) {
        if (summary == null) return false;
        return summary.contains("connected=false")
                || summary.contains("degraded")
                || summary.contains("failed")
                || summary.contains("立即退出")
                || summary.contains("启动失败");
    }

}
