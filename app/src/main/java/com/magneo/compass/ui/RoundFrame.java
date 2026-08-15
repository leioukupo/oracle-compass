package com.magneo.compass.ui;

import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * 圆屏统一容器基类。把原先散落在 10 处逐字复制的 oval-mask 三件套
 * (setClipToOutline + setOutlineProvider setOval) 收敛到这里。
 *
 * 范式参考（实测系统 app）：
 *   - 真圆屏其实不必在 app 内再套 mask，物理玻璃已经裁了
 *     （com.android.settings 不套，靠 ListView 滚动 + 玻璃裁）
 *   - 只在两种场景需要 mask：
 *     (1) ListView/GridView 触控防角点误命中
 *     (2) 想给容器加金边视觉（com.android.music 的圆封面、圆进度环）
 *
 * 用法：
 *   new RoundFrame(ctx, true, false, 0)             // 套 mask，无金边，无内边距
 *   new RoundFrame(ctx, false, false, 0).goldBg()    // 不套 mask（玻璃裁），加金边 oval 背景
 *   new RoundFrame(ctx, false, false, 22)            // 不套 mask，仅给内 padding 22dp
 */
public class RoundFrame extends FrameLayout {

    private final boolean mask;

    public RoundFrame(Context c, boolean mask, boolean goldEdge, int insetDp) {
        super(c);
        this.mask = mask;
        float density = c.getResources().getDisplayMetrics().density;
        int pad = (int) (insetDp * density);
        setPadding(pad, pad, pad, pad);
        if (mask) {
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }
        if (goldEdge) {
            setBackgroundResource(com.magneo.compass.R.drawable.bg_dialog_oval);
        }
    }

    /** 套 oval mask（保留原 oval-trio 语义）。 */
    public RoundFrame withMask() {
        if (!mask) {
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        }
        return this;
    }

    /** 把 oval mask + bg_dialog_oval（深底金边）一次性应用，等同旧 Activity 里的三件套。 */
    public RoundFrame withGoldEdge() {
        setBackgroundResource(com.magneo.compass.R.drawable.bg_dialog_oval);
        if (!mask) withMask();
        return this;
    }
}