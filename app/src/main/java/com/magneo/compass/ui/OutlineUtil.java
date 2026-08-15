package com.magneo.compass.ui;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 把原先逐字复制 10 处的 oval-mask 三件套收敛成一行调用。
 * 适用：无法换父容器为 RoundFrame 的子视图（如 WebView、SurfaceView、ListView）。
 *
 * 范式参考：真圆屏其实可不必套 mask（物理玻璃已裁，见 com.android.settings）；
 * 只在需要触控防误命中角点、或想加金边视觉时套。
 */
public final class OutlineUtil {

    private OutlineUtil() {}

    /** 给目标视图套上 oval mask（clipToOutline=true + setOval outline）。 */
    public static void oval(View v) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
    }
}