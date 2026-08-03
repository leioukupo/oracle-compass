package com.magneo.compass.web;

import android.content.Context;
import android.os.PowerManager;

/** 推流期间强制设备屏幕保持点亮。
 *  screencap/screenrecord 抓的都是物理屏，屏幕息屏后推流画面会变黑（长时间静止容易被误解为黑屏）。
 *  双重保险：应用内屏幕 WakeLock（不插电也常亮）+ 系统 svc power stayon true（插电常亮）。 */
public class ScreenAwake {
    private static PowerManager.WakeLock wl;

    private ScreenAwake() {}

    public static void on(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wl == null) {
                    wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "compass:stream");
                }
                if (!wl.isHeld()) wl.acquire();
            }
        } catch (Exception ignored) {}
        run("svc power stayon true");
    }

    public static void off() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        run("svc power stayon false");
    }

    private static void run(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        } catch (Exception ignored) {}
    }
}
