package com.magneo.compass;

/** Keeps remote maintenance access alive while the app process is running. */
public final class RemoteAccessWatchdog {
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

}
