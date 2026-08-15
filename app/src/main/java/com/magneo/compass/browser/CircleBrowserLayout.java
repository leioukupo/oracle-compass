package com.magneo.compass.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 圆弧浏览器布局：地址栏文字沿顶部圆弧绘制，WebView 占满中心。
 * 按钮沿圆周分布，不再直线排列超出显示区域。
 */
public class CircleBrowserLayout extends FrameLayout {

    private String urlText = "";
    private String hint = "点击输入网址";
    private OnUrlClickListener urlClick;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public interface OnUrlClickListener {
        void onUrlClick();
    }

    public CircleBrowserLayout(Context c) {
        super(c);
        setWillNotDraw(false);
        textPaint.setColor(Color.rgb(232, 220, 192));
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(Color.rgb(120, 114, 98));
        hintPaint.setTextSize(28f);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.rgb(60, 55, 40));
        borderPaint.setStrokeWidth(2f);
        setOnClickListener(v -> { if (urlClick != null) urlClick.onUrlClick(); });
    }

    public void setUrlText(String url) {
        this.urlText = url == null ? "" : url;
        invalidate();
    }

    public void setOnUrlClickListener(OnUrlClickListener l) { this.urlClick = l; }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        // 顶部圆弧路径（从左侧210°到右侧330°，即顶部120°弧段）
        RectF arcRect = new RectF(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f);
        Path arcPath = new Path();
        arcPath.addArc(arcRect, 210f, 120f);

        // 绘制圆弧边框
        c.drawPath(arcPath, borderPaint);

        // 沿圆弧绘制 URL 文本
        String display = urlText.isEmpty() ? hint : urlText;
        // 截断过长文本
        Paint usePaint = urlText.isEmpty() ? hintPaint : textPaint;
        float maxLen = (float) (r * 0.85f * Math.PI * 120.0 / 180.0);  // 弧长
        if (usePaint.measureText(display) > maxLen) {
            while (display.length() > 1 && usePaint.measureText(display + "…") > maxLen)
                display = display.substring(0, display.length() - 1);
            display += "…";
        }
        c.drawTextOnPath(display, arcPath, 0, 10f, usePaint);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
