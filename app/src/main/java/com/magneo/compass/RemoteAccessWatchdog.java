package com.magneo.compass;

/** Keeps remote maintenance access alive while the app process is running. */
public final class RemoteAccessWatchdog {
    private static final long TUNNEL_SETTLE_MS = 10000L;
    private static volatile long tunnelReadyAt;

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
        boolean hasCfg = !Prefs.get(app, Prefs.K_FRPC_CONFIG, "").trim().isEmpty();
        boolean adbAuto = Prefs.getB(app, Prefs.K_ADB_TCP_AUTO, false);
        boolean wifiConnected = SavedWifiAutoConnector.isConnected(app);
        try {
            out.append("\nadb: ");
            if (!adbAuto) {
                tunnelReadyAt = 0L;
                out.append(com.magneo.compass.web.AdbManager.ensureAutoStart(app));
            } else if (!wifiConnected) {
                tunnelReadyAt = 0L;
                out.append("waiting for WiFi before ADB sync");
            } else {
                long now = android.os.SystemClock.elapsedRealtime();
                if (!com.magneo.compass.web.AdbManager.isSystemBootComplete()) {
                    tunnelReadyAt = 0L;
                    out.append("system boot incomplete; ADB sync pending");
                    return out.toString();
                }
                if (!com.magneo.compass.web.AdbManager.isTunnelSynchronized(app)) {
                    // Keep the public tunnel down while the Android 5.1 adbd performs its
                    // one-time AUTH readiness check. Otherwise a reconnecting host can occupy
                    // the only usable transport and leave both connections offline.
                    if (hasCfg && com.magneo.compass.frp.FrpcManager.isRunning()) {
                        com.magneo.compass.frp.FrpcManager.stop();
                    }
                    if (tunnelReadyAt == 0L) tunnelReadyAt = now;
                    if (now - tunnelReadyAt < TUNNEL_SETTLE_MS) {
                        out.append("startup ADB sync pending");
                    } else {
                        out.append(com.magneo.compass.web.AdbManager
                                .ensureTunnelSynchronized(app));
                    }
                } else {
                    tunnelReadyAt = 0L;
                    out.append(com.magneo.compass.web.AdbManager.ensureAutoStart(app));
                }
            }
        } catch (Throwable t) {
            out.append("adb failed: ").append(t.getMessage());
        }
        try {
            out.append("\nfrpc: cfg=").append(hasCfg);
            boolean remoteReady = !adbAuto
                    || com.magneo.compass.web.AdbManager.isTunnelSynchronized(app);
            if (hasCfg && remoteReady) {
                out.append(" ").append(com.magneo.compass.frp.FrpcManager.ensureStarted(app));
            } else if (hasCfg) {
                out.append(" waiting for startup ADB sync");
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
                || summary.contains("sync pending")
                || summary.contains("system boot incomplete")
                || summary.contains("waiting for WiFi")
                || summary.contains("failed")
                || summary.contains("立即退出")
                || summary.contains("启动失败");
    }

}
