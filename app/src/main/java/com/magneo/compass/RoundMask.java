package com.magneo.compass;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/** 给应用图标加圆形遮罩（图标本体裁圆 + 可选圆底）。 */
public class RoundMask {
    public static void circle(ImageView iv) {
        circle(iv, 0);
    }

    public static void circle(ImageView iv, int bgRes) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bgRes != 0) iv.setBackgroundResource(bgRes);
    }
}
