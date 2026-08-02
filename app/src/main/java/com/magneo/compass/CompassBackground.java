package com.magneo.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 罗盘风格背景：深色径向渐变 + 淡金同心圆环 + 刻度 + 子午卯酉四正。 */
public class CompassBackground extends View {

    private static final int GOLD = 0xD4AF37;

    public CompassBackground(Context c) {
        super(c);
        // 老设备 GPU 对径向渐变渲染不稳（会白屏），强制软件渲染
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 纯深色底（老设备上渐变易出白屏）
        canvas.drawColor(0xFF0D0B08);

        // 同心圆环
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, r * 0.003f));
        float[] rings = {0.34f, 0.52f, 0.72f, 0.92f};
        for (float f : rings) {
            p.setColor(0x1FD4AF37);
            canvas.drawCircle(cx, cy, r * f, p);
        }

        // 刻度：每 30° 一格，四正加长
        p.setStrokeWidth(Math.max(2f, r * 0.005f));
        for (int d = 0; d < 360; d += 30) {
            float rad = (float) Math.toRadians(d - 90);
            float r1 = (d % 90 == 0 ? 0.94f : 0.90f) * r;
            float r2 = 0.985f * r;
            p.setColor(d % 90 == 0 ? 0x38D4AF37 : 0x24D4AF37);
            canvas.drawLine(cx + (float) Math.cos(rad) * r1, cy + (float) Math.sin(rad) * r1,
                    cx + (float) Math.cos(rad) * r2, cy + (float) Math.sin(rad) * r2, p);
        }

        // 四正字：子(北) 午(南) 卯(东) 酉(西)
        p.setColor(0x40D4AF37);
        p.setTextSize(r * 0.10f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        canvas.drawText("子", cx, cy - r * 0.72f, p);
        canvas.drawText("午", cx, cy + r * 0.80f, p);
        canvas.drawText("卯", cx - r * 0.74f, cy + r * 0.04f, p);
        canvas.drawText("酉", cx + r * 0.74f, cy + r * 0.04f, p);

        // 八卦方位点
        p.setColor(0x48D4AF37);
        for (int i = 0; i < 8; i++) {
            float rad = (float) Math.toRadians(-90 + i * 45);
            canvas.drawCircle(cx + (float) Math.cos(rad) * r * 0.34f,
                    cy + (float) Math.sin(rad) * r * 0.34f, r * 0.010f, p);
        }
    }
}
