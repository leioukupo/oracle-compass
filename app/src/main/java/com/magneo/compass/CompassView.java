package com.magneo.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.magneo.compass.ui.RoundScreen;
import com.magneo.compass.ui.Ui;

import java.util.Calendar;
import java.util.Locale;

/** 真理罗盘主视图：外圈八卦功能舱 + 罗盘/时钟 + 传感器读数 + 中央太极。 */
public class CompassView extends View {
    public interface Actions {
        void onSector(int index);
        void onCenterTap();
    }

    public static final String[] SECTOR_NAMES = {"乾", "坎", "艮", "震", "巽", "离", "兑", "坤"};
    public static final String[] SECTOR_LABELS = {"应用", "网盘", "设置", "语音", "音乐", "灵眼", "浏览", "详情"};
    // 卦象三爻（初爻→二爻→三爻，1=阳爻/连，0=阴爻/断），按 SECTOR_NAMES 顺序
    private static final int[][] TRIGRAMS = {
            {1, 1, 1},  // 乾 ☰
            {0, 1, 0},  // 坎 ☵
            {0, 0, 1},  // 艮 ☶
            {1, 0, 0},  // 震 ☳
            {0, 1, 1},  // 巽 ☴
            {1, 0, 1},  // 离 ☲
            {1, 1, 0},  // 兑 ☱
            {0, 0, 0},  // 坤 ☷
    };

    private static final String[] TIAN_GAN = {"甲","乙","丙","丁","戊","己","庚","辛","壬","癸"};
    private static final String[] DI_ZHI = {"子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥"};

    private static final int C_GOLD = Ui.COLOR_GOLD;
    private static final int C_GOLD_DARK = Ui.COLOR_GOLD_DARK;
    private static final int C_GOLD_DIM = Ui.COLOR_GOLD_DIM;
    private static final int C_RED = Ui.COLOR_RED;
    private static final int C_TEXT = Ui.COLOR_TEXT;
    private static final int C_DIM = Ui.COLOR_TEXT_DIM;
    private static final int C_MUTED = Ui.COLOR_TEXT_MUTED;
    private static final int C_CYAN = Ui.COLOR_AETHER;
    private static final int C_BG = Ui.COLOR_BG;
    private static final int C_BG_DEEP = Ui.COLOR_BG_DEEP;
    private static final int C_PANEL = Ui.COLOR_PANEL;
    private static final int C_BRONZE = Color.rgb(54, 35, 16);

    private final SensorHub hub;
    private final Actions actions;
    private String status = "";
    private boolean detailMode = false;
    private boolean detailDiagnostic = false;
    private boolean dragging = false;
    private int previewIdx = -1;
    private float downX, downY;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CompassView(Context c, SensorHub hub, Actions actions) {
        super(c);
        this.hub = hub;
        this.actions = actions;
        pStroke.setStyle(Paint.Style.STROKE);
        pText.setColor(C_TEXT);
        pText.setSubpixelText(true);
        pSmall.setColor(C_DIM);
        pSmall.setSubpixelText(true);
    }

    public void setStatus(String s) { status = s == null ? "" : s; postInvalidate(); }
    public void toggleDetail() {
        detailMode = !detailMode;
        detailDiagnostic = false;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = RoundScreen.cx(w), cy = RoundScreen.cy(h);
        float scale = RoundScreen.scale800(w, h);
        float rMax = RoundScreen.R(w, h) - 4f;  // 安全圆内缩 4px

        canvas.drawColor(C_BG_DEEP);

        float az = Float.isNaN(hub.azimuth) ? 0 : hub.azimuth;
        int highlight = dragging ? previewIdx : -1;
        drawMysticField(canvas, cx, cy, rMax, scale);
        drawSectors(canvas, cx, cy, rMax, scale, highlight);
        drawTianganDizhi(canvas, cx, cy, rMax, scale);
        drawBatteryRing(canvas, cx, cy, rMax, scale);
        drawCompassRing(canvas, cx, cy, rMax * 0.50f, az, scale);
        drawClock(canvas, cx, cy, rMax * 0.36f, scale);

        if (dragging && previewIdx >= 0) drawCenterPreview(canvas, cx, cy, rMax, scale);
        else if (detailMode) drawDetail(canvas, cx, cy, scale);
        else drawCenter(canvas, cx, cy, rMax * 0.235f, scale, hub.azimuth);

        drawStatus(canvas, cx, cy, rMax, scale);
    }

    private int a(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.rgb(r, g, b);
    }

    private Paint pStroke(Paint p, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        return p;
    }

    private void drawTextCentered(Canvas c, String text, float x, float baseline, Paint paint) {
        c.drawText(text, x - paint.measureText(text) / 2f, baseline, paint);
    }

    private void drawTextCenteredOnPoint(Canvas c, String text, float x, float y, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText(text, x - paint.measureText(text) / 2f,
                y - (fm.ascent + fm.descent) / 2f, paint);
    }

    private String ellipsize(String text, Paint paint, float maxW) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxW) return text;
        int n = text.length();
        while (n > 1 && paint.measureText(text.substring(0, n) + "…") > maxW) n--;
        return text.substring(0, n) + "…";
    }

    /** 暖墨底 + 同心层：不用 Shader，避免老 MTK 渲染异常。 */
    private void drawMysticField(Canvas c, float cx, float cy, float r, float s) {
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_BG_DEEP);
        c.drawCircle(cx, cy, r, pFill);

        for (int i = 14; i >= 0; i--) {
            float k = i / 14f;
            float rr = r * (0.18f + 0.82f * k);
            int col = blend(Color.rgb(28, 15, 8), Color.rgb(24, 24, 18), 1f - k);
            pFill.setColor(col);
            c.drawCircle(cx, cy, rr, pFill);
        }

        pFill.setColor(Color.argb(72, 70, 42, 18));
        c.drawCircle(cx, cy, r * 0.92f, pFill);
        pFill.setColor(Color.argb(58, 10, 36, 38));
        c.drawCircle(cx, cy, r * 0.56f, pFill);

        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        float[] rings = {0.23f, 0.36f, 0.50f, 0.57f, 0.61f, 0.72f, 0.86f};
        for (int i = 0; i < rings.length; i++) {
            boolean important = i >= 3;
            pStroke.setColor(i % 2 == 0 ? a(C_GOLD, important ? 96 : 62) : a(C_CYAN, important ? 62 : 42));
            pStroke.setStrokeWidth((important ? 1.8f : 1.15f) * s);
            c.drawCircle(cx, cy, r * rings[i], pStroke);
        }

        pStroke.setStrokeWidth(1f * s);
        for (int i = 0; i < 8; i++) {
            float rad = (float) Math.toRadians(-90 + i * 45);
            float inner = r * 0.24f;
            float outer = r * 0.56f;
            pStroke.setColor(i % 2 == 0 ? a(C_GOLD, 66) : a(C_CYAN, 58));
            c.drawLine(cx + (float) Math.cos(rad) * inner,
                    cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer,
                    cy + (float) Math.sin(rad) * outer,
                    pStroke);
            pFill.setColor(a(C_CYAN, 100));
            c.drawCircle(cx + (float) Math.cos(rad) * r * 0.57f,
                    cy + (float) Math.sin(rad) * r * 0.57f,
                    2.5f * s, pFill);
        }

        RectF scan = new RectF(cx - r * 0.53f, cy - r * 0.53f, cx + r * 0.53f, cy + r * 0.53f);
        pStroke.setColor(a(C_CYAN, 98));
        pStroke.setStrokeWidth(2.2f * s);
        c.drawArc(scan, -30f, 58f, false, pStroke);
        c.drawArc(scan, 152f, 48f, false, pStroke);

        pStroke.setColor(C_GOLD);
        pStroke.setStrokeWidth(2.6f * s);
        c.drawCircle(cx, cy, r, pStroke);
    }

    private void drawSectors(Canvas c, float cx, float cy, float r, float s, int highlight) {
        RectF outer = new RectF(cx - r, cy - r, cx + r, cy + r);
        float innerR = r * 0.865f;
        RectF inner = new RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
        for (int i = 0; i < 8; i++) {
            float start = i * 45f - 90f;
            Path seg = new Path();
            seg.arcTo(outer, start, 45f);
            seg.arcTo(inner, start + 45f, -45f);
            seg.close();

            boolean hot = i == highlight;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(hot
                    ? Color.rgb(86, 58, 18)
                    : (i % 2 == 0 ? Color.rgb(39, 25, 13) : Color.rgb(30, 22, 15)));
            c.drawPath(seg, pFill);
            drawSectorTexture(c, cx, cy, r, innerR, start, i, hot, s);

            if (hot) {
                pStroke.setStrokeCap(Paint.Cap.BUTT);
                c.drawPath(seg, pStroke(pStroke, a(C_GOLD, 140), 8f * s));
            }
            c.drawPath(seg, pStroke(pStroke, hot ? C_GOLD : a(C_GOLD_DARK, 235),
                    (hot ? 3.2f : 1.9f) * s));

            float midDeg = start + 22.5f;
            float mid = (float) Math.toRadians(midDeg);
            float mr = (r + innerR) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            if (hot) {
                pStroke.setColor(a(C_CYAN, 175));
                pStroke.setStrokeWidth(2.0f * s);
                c.drawLine(cx + (float) Math.cos(mid) * (innerR + 9f * s),
                        cy + (float) Math.sin(mid) * (innerR + 9f * s),
                        cx + (float) Math.cos(mid) * (r - 14f * s),
                        cy + (float) Math.sin(mid) * (r - 14f * s),
                        pStroke);
            }

            c.save();
            c.rotate(midDeg + 90f, mx, my);
            pText.setStyle(Paint.Style.FILL);
            pText.setFakeBoldText(true);
            pText.setTextSize(ringTextSize(28f, r - innerR, 0.54f, 22f, s));
            pText.setColor(hot ? Color.rgb(255, 221, 96) : C_GOLD);
            drawTextCenteredOnPoint(c, SECTOR_NAMES[i], mx, my, pText);
            pText.setFakeBoldText(false);
            c.restore();
        }
    }

    private void drawSectorTexture(Canvas c, float cx, float cy, float r, float innerR,
                                   float start, int idx, boolean hot, float s) {
        float gap = r - innerR;
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setStrokeWidth(1.05f * s);
        pStroke.setColor(a(hot ? C_CYAN : C_GOLD, hot ? 120 : 82));
        float texR1 = innerR + gap * 0.32f;
        float texR2 = innerR + gap * 0.68f;
        c.drawArc(new RectF(cx - texR1, cy - texR1, cx + texR1, cy + texR1),
                start + 6f, 33f, false, pStroke);
        c.drawArc(new RectF(cx - texR2, cy - texR2, cx + texR2, cy + texR2),
                start + 6f, 33f, false, pStroke);

        float midDeg = start + 22.5f;
        float mid = (float) Math.toRadians(midDeg);
        float tx = cx + (float) Math.cos(mid) * (innerR + gap * 0.50f);
        float ty = cy + (float) Math.sin(mid) * (innerR + gap * 0.50f);
        drawTrigramBars(c, tx, ty, midDeg + 90f, idx,
                50f * s, 5f * s, 6f * s, 17f * s,
                a(hot ? C_CYAN : C_GOLD, hot ? 88 : 52), 2f * s);
    }

    private void drawTrigramBars(Canvas c, float x, float y, float rotation, int idx,
                                 float barW, float barH, float gapY, float yinGap,
                                 int color, float radius) {
        int[] tri = TRIGRAMS[idx];
        float totalH = 3 * barH + 2 * gapY;
        float topY = y - totalH / 2f;
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(color);
        c.save();
        c.rotate(rotation, x, y);
        for (int i = 0; i < 3; i++) {
            int yao = tri[2 - i];
            float yy = topY + i * (barH + gapY);
            if (yao == 1) {
                c.drawRoundRect(new RectF(x - barW / 2f, yy, x + barW / 2f, yy + barH),
                        radius, radius, pFill);
            } else {
                float halfW = (barW - yinGap) / 2f;
                c.drawRoundRect(new RectF(x - barW / 2f, yy, x - barW / 2f + halfW, yy + barH),
                        radius, radius, pFill);
                c.drawRoundRect(new RectF(x + barW / 2f - halfW, yy, x + barW / 2f, yy + barH),
                        radius, radius, pFill);
            }
        }
        c.restore();
    }

    private void drawCompassRing(Canvas c, float cx, float cy, float r, float az, float s) {
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_GOLD_DARK, 130));
        pStroke.setStrokeWidth(1.7f * s);
        c.drawCircle(cx, cy, r * 1.02f, pStroke);
        pStroke.setColor(a(C_CYAN, 70));
        pStroke.setStrokeWidth(1.2f * s);
        c.drawCircle(cx, cy, r * 0.76f, pStroke);

        c.save();
        c.rotate(-az, cx, cy);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        for (int d = 0; d < 360; d += 5) {
            boolean cardinal = d % 90 == 0;
            boolean major = d % 30 == 0;
            float rad = (float) Math.toRadians(d - 90);
            float len = cardinal ? 0.78f : (major ? 0.84f : 0.91f);
            float x1 = cx + (float) Math.cos(rad) * r * len;
            float y1 = cy + (float) Math.sin(rad) * r * len;
            float x2 = cx + (float) Math.cos(rad) * r;
            float y2 = cy + (float) Math.sin(rad) * r;
            pStroke.setColor(cardinal ? a(C_GOLD, 230) : (major ? a(C_GOLD, 160) : a(C_CYAN, 88)));
            pStroke.setStrokeWidth(cardinal ? 2.5f * s : (major ? 1.45f * s : 0.9f * s));
            c.drawLine(x1, y1, x2, y2, pStroke);
        }

        String[] dirs = {"北", "东", "南", "西"};
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(i * 90 - 90);
            float tx = cx + (float) Math.cos(rad) * r * 0.70f;
            float ty = cy + (float) Math.sin(rad) * r * 0.70f;
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(22f * s);
            pText.setFakeBoldText(i == 0);
            pText.setColor(i == 0 ? Color.rgb(196, 49, 38) : C_GOLD);
            drawTextCentered(c, dirs[i], tx, ty + 8f * s, pText);
        }
        pText.setFakeBoldText(false);
        c.restore();
    }

    /** 天干地支环：保留术数信息，但压低对比度，让它成为外圈导航和中心罗盘之间的辅助纹理。 */
    private void drawTianganDizhi(Canvas c, float cx, float cy, float rMax, float s) {
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        int a = (14 - month) / 12;
        int y2 = year + 4800 - a;
        int m = month + 12 * a - 3;
        int jdn = day + (153 * m + 2) / 5 + 365 * y2 + y2 / 4 - y2 / 100 + y2 / 400 - 32045;
        int dayGan = ((jdn + 9) % 10 + 10) % 10;
        int hourZhi = ((hour + 1) / 2) % 12;
        int hourGan = ((dayGan % 5) * 2 + hourZhi) % 10;

        float zhiOuter = rMax * 0.865f, zhiInner = rMax * 0.715f;
        RectF zhiRingO = new RectF(cx - zhiOuter, cy - zhiOuter, cx + zhiOuter, cy + zhiOuter);
        RectF zhiRingI = new RectF(cx - zhiInner, cy - zhiInner, cx + zhiInner, cy + zhiInner);
        for (int i = 0; i < 12; i++) {
            float start = i * 30f - 90f;
            Path seg = new Path();
            seg.arcTo(zhiRingO, start, 30f);
            seg.arcTo(zhiRingI, start + 30f, -30f);
            seg.close();
            boolean hl = i == hourZhi;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(hl ? Color.rgb(75, 50, 18) : Color.rgb(28, 21, 13));
            c.drawPath(seg, pFill);
            c.drawPath(seg, pStroke(pStroke, hl ? a(C_GOLD, 245) : a(C_GOLD_DIM, 145),
                    (hl ? 1.75f : 1.25f) * s));
            float mid = (float) Math.toRadians(start + 15f);
            float mr = (zhiOuter + zhiInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save();
            c.rotate(start + 15f + 90f, mx, my);
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(ringTextSize(23f, zhiOuter - zhiInner, 0.50f, 17f, s));
            pText.setFakeBoldText(hl);
            pText.setColor(hl ? Color.rgb(244, 203, 76) : a(C_DIM, 220));
            drawTextCenteredOnPoint(c, DI_ZHI[i], mx, my, pText);
            pText.setFakeBoldText(false);
            c.restore();
        }

        float ganOuter = zhiInner, ganInner = rMax * 0.610f;
        RectF ganRingO = new RectF(cx - ganOuter, cy - ganOuter, cx + ganOuter, cy + ganOuter);
        RectF ganRingI = new RectF(cx - ganInner, cy - ganInner, cx + ganInner, cy + ganInner);
        for (int i = 0; i < 10; i++) {
            float start = i * 36f - 90f;
            Path seg = new Path();
            seg.arcTo(ganRingO, start, 36f);
            seg.arcTo(ganRingI, start + 36f, -36f);
            seg.close();
            boolean hl = i == hourGan;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(hl ? Color.rgb(60, 40, 16) : Color.rgb(22, 18, 13));
            c.drawPath(seg, pFill);
            c.drawPath(seg, pStroke(pStroke, hl ? a(C_GOLD, 220) : a(C_GOLD_DIM, 120),
                    (hl ? 1.35f : 1.05f) * s));

            float mid = (float) Math.toRadians(start + 18f);
            float mr = (ganOuter + ganInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save();
            c.rotate(start + 18f + 90f, mx, my);
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(ringTextSize(19f, ganOuter - ganInner, 0.50f, 15.5f, s));
            pText.setColor(hl ? Color.rgb(238, 196, 70) : a(C_MUTED, 220));
            drawTextCenteredOnPoint(c, TIAN_GAN[i], mx, my, pText);
            c.restore();
        }

        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(a(C_GOLD, 135));
        pStroke.setStrokeWidth(1.35f * s);
        c.drawCircle(cx, cy, zhiOuter, pStroke);
        c.drawCircle(cx, cy, zhiInner, pStroke);
        pStroke.setColor(a(C_CYAN, 82));
        pStroke.setStrokeWidth(1.0f * s);
        c.drawCircle(cx, cy, ganInner, pStroke);
    }

    private void drawClock(Canvas c, float cx, float cy, float r, float s) {
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < 12; i++) {
            float rad = (float) Math.toRadians(i * 30 - 90);
            float x1 = cx + (float) Math.cos(rad) * r * 0.94f;
            float y1 = cy + (float) Math.sin(rad) * r * 0.94f;
            float x2 = cx + (float) Math.cos(rad) * r;
            float y2 = cy + (float) Math.sin(rad) * r;
            pStroke.setColor(i % 3 == 0 ? a(C_GOLD, 170) : a(C_DIM, 125));
            pStroke.setStrokeWidth(i % 3 == 0 ? 2.4f * s : 1.1f * s);
            c.drawLine(x1, y1, x2, y2, pStroke);
        }

        Calendar cal = Calendar.getInstance();
        float min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f;
        float hour = (cal.get(Calendar.HOUR) % 12) + min / 60f;
        drawClockHand(c, cx, cy, r * 0.54f, hour * 30f, a(C_GOLD, 135), 3.0f * s);
        drawClockHand(c, cx, cy, r * 0.76f, min * 6f, a(C_GOLD, 165), 2.0f * s);
        drawClockHand(c, cx, cy, r * 0.82f, cal.get(Calendar.SECOND) * 6f, a(C_CYAN, 120), 1.15f * s);
    }

    private void drawClockHand(Canvas c, float cx, float cy, float len, float deg, int color, float width) {
        float rad = (float) Math.toRadians(deg - 90f);
        pStroke.setColor(color);
        pStroke.setStrokeWidth(width);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, cy,
                cx + (float) Math.cos(rad) * len,
                cy + (float) Math.sin(rad) * len,
                pStroke);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 电量环：紧挨天干内侧，金色弧按电量比例填充。 */
    private void drawBatteryRing(Canvas c, float cx, float cy, float rMax, float s) {
        float outer = rMax * 0.595f, inner = rMax * 0.565f;
        float mid = (outer + inner) / 2f;
        int usedColor = Color.rgb(74, 46, 24);
        int remainColor = hub.battery >= 0 && hub.battery < 20
                ? Color.rgb(183, 47, 37)
                : Color.rgb(246, 204, 70);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(usedColor);
        pStroke.setStrokeWidth(outer - inner);
        c.drawCircle(cx, cy, mid, pStroke);

        if (hub.battery >= 0 && hub.battery <= 100) {
            float sweep = 360f * hub.battery / 100f;
            RectF arc = new RectF(cx - mid, cy - mid, cx + mid, cy + mid);
            pStroke.setColor(remainColor);
            pStroke.setStrokeWidth(outer - inner);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(arc, -90f, sweep, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    private void drawNeedle(Canvas c, float cx, float cy, float r, float az, float s) {
        c.save();
        c.rotate(-az, cx, cy);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_RED, 82));
        pStroke.setStrokeWidth(10f * s);
        c.drawLine(cx, cy + r * 0.18f, cx, cy - r * 0.90f, pStroke);
        pStroke.setColor(C_RED);
        pStroke.setStrokeWidth(3.4f * s);
        c.drawLine(cx, cy + r * 0.10f, cx, cy - r * 0.92f, pStroke);
        pStroke.setColor(a(C_GOLD, 130));
        pStroke.setStrokeWidth(2f * s);
        c.drawLine(cx, cy, cx, cy + r * 0.34f, pStroke);
        pStroke.setStrokeCap(Paint.Cap.BUTT);

        Path tri = new Path();
        tri.moveTo(cx - 8f * s, cy - r * 0.82f);
        tri.lineTo(cx + 8f * s, cy - r * 0.82f);
        tri.lineTo(cx, cy - r * 1.03f);
        tri.close();
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_RED);
        c.drawPath(tri, pFill);
        c.restore();
    }

    private void drawCenter(Canvas c, float cx, float cy, float r, float s, float az) {
        float ringR = r * 1.14f;
        float diskR = r * 0.93f;
        RectF glow = new RectF(cx - ringR * 1.18f, cy - ringR * 1.18f,
                cx + ringR * 1.18f, cy + ringR * 1.18f);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_CYAN, 110));
        pStroke.setStrokeWidth(1.7f * s);
        c.drawArc(glow, -26f, 52f, false, pStroke);
        c.drawArc(glow, 154f, 52f, false, pStroke);

        pStroke.setColor(a(C_GOLD_DARK, 116));
        pStroke.setStrokeWidth(1.6f * s);
        c.drawCircle(cx, cy, ringR, pStroke);
        pStroke.setColor(a(C_CYAN, 58));
        pStroke.setStrokeWidth(1.0f * s);
        c.drawCircle(cx, cy, ringR * 0.78f, pStroke);

        pStroke.setColor(a(C_GOLD, 130));
        pStroke.setStrokeWidth(1.2f * s);
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(-90f + i * 90f);
            float x1 = cx + (float) Math.cos(rad) * ringR * 0.90f;
            float y1 = cy + (float) Math.sin(rad) * ringR * 0.90f;
            float x2 = cx + (float) Math.cos(rad) * ringR * 1.03f;
            float y2 = cy + (float) Math.sin(rad) * ringR * 1.03f;
            c.drawLine(x1, y1, x2, y2, pStroke);
        }

        if (!Float.isNaN(az)) {
            float showAz = az % 360f;
            if (showAz < 0) showAz += 360f;
            RectF azArc = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            pStroke.setColor(a(C_CYAN, 110));
            pStroke.setStrokeWidth(2.0f * s);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(azArc, -90f, showAz, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);

            float rad = (float) Math.toRadians(showAz - 90f);
            float px = cx + (float) Math.cos(rad) * ringR;
            float py = cy + (float) Math.sin(rad) * ringR;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(C_GOLD);
            c.drawCircle(px, py, 3.0f * s, pFill);
        }

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.rgb(14, 12, 9));
        c.drawCircle(cx, cy, diskR, pFill);
        pStroke.setColor(a(C_GOLD, 135));
        pStroke.setStrokeWidth(7f * s);
        c.drawCircle(cx, cy, diskR * 1.04f, pStroke);
        pStroke.setColor(C_GOLD);
        pStroke.setStrokeWidth(2.6f * s);
        c.drawCircle(cx, cy, diskR, pStroke);

        float taijiR = diskR * 0.88f;
        RectF taiji = new RectF(cx - taijiR, cy - taijiR, cx + taijiR, cy + taijiR);
        float taijiAz = Float.isNaN(az) ? 0f : az;
        c.save();
        c.rotate(-taijiAz, cx, cy);
        pFill.setColor(Color.rgb(197, 153, 40));
        c.drawCircle(cx, cy, taijiR, pFill);

        Path darkHalf = new Path();
        darkHalf.moveTo(cx, cy - taijiR);
        darkHalf.arcTo(taiji, -90f, 180f);
        darkHalf.close();
        pFill.setColor(Color.rgb(8, 7, 5));
        c.drawPath(darkHalf, pFill);
        c.drawCircle(cx, cy + taijiR / 2f, taijiR / 2f, pFill);

        pFill.setColor(Color.rgb(197, 153, 40));
        c.drawCircle(cx, cy - taijiR / 2f, taijiR / 2f, pFill);

        pStroke.setColor(a(C_GOLD, 155));
        pStroke.setStrokeWidth(1.6f * s);
        c.drawCircle(cx, cy, taijiR, pStroke);
        pStroke.setColor(a(C_GOLD, 62));
        pStroke.setStrokeWidth(1f * s);
        c.drawCircle(cx, cy, taijiR * 0.82f, pStroke);

        pFill.setColor(Color.rgb(9, 8, 6));
        c.drawCircle(cx, cy - taijiR / 2f, taijiR * 0.115f, pFill);
        pFill.setColor(Color.rgb(230, 188, 55));
        c.drawCircle(cx, cy + taijiR / 2f, taijiR * 0.115f, pFill);
        c.restore();
    }

    private void drawStatus(Canvas c, float cx, float cy, float rMax, float s) {
        if (status.isEmpty()) return;
        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(false);
        pText.setTextSize(15f * s);
        pText.setColor(C_TEXT);
        float maxW = Math.min(getWidth(), getHeight()) * 0.58f;
        String text = ellipsize(status, pText, maxW);
        float textW = pText.measureText(text);
        float padX = 20f * s;
        float y = detailMode ? cy + rMax * 0.61f : cy + rMax * 0.31f;
        RectF bg = new RectF(cx - textW / 2f - padX, y - 20f * s,
                cx + textW / 2f + padX, y + 17f * s);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(216, 12, 13, 13));
        c.drawRoundRect(bg, 18f * s, 18f * s, pFill);
        pStroke.setColor(a(C_CYAN, 104));
        pStroke.setStrokeWidth(1f * s);
        c.drawRoundRect(bg, 18f * s, 18f * s, pStroke);
        drawTextCentered(c, text, cx, y + 5f * s, pText);
    }

    private void drawDetail(Canvas c, float cx, float cy, float s) {
        if (detailDiagnostic) drawDetailDiagnostic(c, cx, cy, s);
        else drawEarthDetail(c, cx, cy, s);
    }

    private void drawEarthDetail(Canvas c, float cx, float cy, float s) {
        float rMax = RoundScreen.R(getWidth(), getHeight()) - 4f;
        float panelR = rMax * 0.51f;
        float instR = rMax * 0.30f;
        float instCy = cy - 6f * s;
        float az = Float.isNaN(hub.azimuth) ? 0f : normalized360(hub.azimuth);
        float pitch = Float.isNaN(hub.pitch) ? 0f : hub.pitch;
        float roll = Float.isNaN(hub.roll) ? 0f : hub.roll;
        float mag = magStrength();
        String pose = (Math.abs(pitch) < 8f && Math.abs(roll) < 8f) ? "设备平稳" : "姿态倾斜";
        String magnet = magneticStatus(mag);
        String motion = motionState();
        String summary = pose + " · " + magnet + " · " + motion;

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(210, 13, 11, 8));
        c.drawCircle(cx, cy, panelR, pFill);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(a(C_GOLD, 120));
        pStroke.setStrokeWidth(2.1f * s);
        c.drawCircle(cx, cy, panelR, pStroke);
        pStroke.setColor(a(C_CYAN, 42));
        pStroke.setStrokeWidth(1f * s);
        c.drawCircle(cx, cy, panelR * 0.84f, pStroke);

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(true);
        pText.setTextSize(24f * s);
        pText.setColor(C_GOLD);
        drawTextCentered(c, "坤 · 地盘", cx, cy - rMax * 0.36f, pText);
        pText.setFakeBoldText(false);
        pText.setTextSize(13f * s);
        pText.setColor(C_DIM);
        drawTextCentered(c, ellipsize(summary, pText, rMax * 1.05f), cx, cy - rMax * 0.30f, pText);

        drawKunTexture(c, cx, instCy, instR, s);
        drawEarthInstrument(c, cx, instCy, instR, az, pitch, roll, s);

        float chipW = 155f * s;
        float chipH = 42f * s;
        float gap = 14f * s;
        float row1 = cy + rMax * 0.31f;
        float row2 = cy + rMax * 0.43f;
        drawDetailChip(c, cx - chipW / 2f - gap / 2f, row1, chipW, chipH,
                "电量", hub.battery >= 0 ? hub.battery + "%" : "--", s);
        drawDetailChip(c, cx + chipW / 2f + gap / 2f, row1, chipW, chipH,
                "姿态", poseValue(pitch, roll), s);
        drawDetailChip(c, cx - chipW / 2f - gap / 2f, row2, chipW, chipH,
                "动势", motionValue(), s);
        drawDetailChip(c, cx + chipW / 2f + gap / 2f, row2, chipW, chipH,
                "磁场", mag > 0 ? String.format(Locale.US, "%.0fuT", mag) : "--", s);
    }

    private void drawKunTexture(Canvas c, float cx, float cy, float r, float s) {
        float barW = r * 1.18f;
        float gap = r * 0.30f;
        float barH = 7f * s;
        float cut = r * 0.18f;
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(a(C_GOLD, 42));
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * gap;
            c.drawRoundRect(new RectF(cx - barW / 2f, y - barH / 2f,
                    cx - cut, y + barH / 2f), 3f * s, 3f * s, pFill);
            c.drawRoundRect(new RectF(cx + cut, y - barH / 2f,
                    cx + barW / 2f, y + barH / 2f), 3f * s, 3f * s, pFill);
        }
    }

    private void drawEarthInstrument(Canvas c, float cx, float cy, float r, float az,
                                     float pitch, float roll, float s) {
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(a(C_GOLD_DARK, 150));
        pStroke.setStrokeWidth(2f * s);
        c.drawCircle(cx, cy, r, pStroke);
        pStroke.setColor(a(C_CYAN, 65));
        pStroke.setStrokeWidth(1f * s);
        c.drawCircle(cx, cy, r * 0.75f, pStroke);

        for (int i = 0; i < 12; i++) {
            float rad = (float) Math.toRadians(-90f + i * 30f);
            float inner = r * (i % 3 == 0 ? 0.86f : 0.91f);
            float outer = r * 0.98f;
            pStroke.setColor(i % 3 == 0 ? a(C_GOLD, 120) : a(C_GOLD_DIM, 82));
            pStroke.setStrokeWidth((i % 3 == 0 ? 1.3f : 0.8f) * s);
            c.drawLine(cx + (float) Math.cos(rad) * inner,
                    cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer,
                    cy + (float) Math.sin(rad) * outer,
                    pStroke);
        }

        RectF ring = new RectF(cx - r, cy - r, cx + r, cy + r);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_CYAN, 125));
        pStroke.setStrokeWidth(2.3f * s);
        c.drawArc(ring, -90f, az, false, pStroke);
        float ar = (float) Math.toRadians(az - 90f);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_GOLD);
        c.drawCircle(cx + (float) Math.cos(ar) * r,
                cy + (float) Math.sin(ar) * r, 3.8f * s, pFill);

        float shownRoll = clampF(roll, -28f, 28f);
        float pitchOffset = clampF(pitch / 45f, -1f, 1f) * r * 0.28f;
        c.save();
        c.rotate(shownRoll, cx, cy);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_GOLD, 190));
        pStroke.setStrokeWidth(3.2f * s);
        c.drawLine(cx - r * 0.58f, cy + pitchOffset,
                cx + r * 0.58f, cy + pitchOffset, pStroke);
        pStroke.setColor(a(C_CYAN, 82));
        pStroke.setStrokeWidth(1.2f * s);
        c.drawLine(cx - r * 0.42f, cy, cx + r * 0.42f, cy, pStroke);
        c.restore();

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(true);
        pText.setTextSize(30f * s);
        pText.setColor(C_GOLD);
        drawTextCentered(c, "坤", cx, cy + 10f * s, pText);
        pText.setFakeBoldText(false);

        pSmall.setStyle(Paint.Style.FILL);
        pSmall.setTextSize(12f * s);
        pSmall.setColor(C_DIM);
        drawTextCentered(c, String.format(Locale.US, "%.0f°", az), cx, cy + r * 0.57f, pSmall);
    }

    private void drawDetailChip(Canvas c, float centerX, float centerY, float w, float h,
                                String label, String value, float s) {
        RectF rr = new RectF(centerX - w / 2f, centerY - h / 2f,
                centerX + w / 2f, centerY + h / 2f);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(122, 17, 14, 10));
        c.drawRoundRect(rr, 13f * s, 13f * s, pFill);
        pStroke.setColor(a(C_GOLD_DIM, 88));
        pStroke.setStrokeWidth(0.9f * s);
        c.drawRoundRect(rr, 13f * s, 13f * s, pStroke);

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(false);
        pText.setTextSize(11f * s);
        pText.setColor(C_DIM);
        drawTextCentered(c, label, centerX, centerY - 6f * s, pText);
        pText.setTextSize(14f * s);
        pText.setColor(C_TEXT);
        drawTextCentered(c, ellipsize(value, pText, w - 16f * s), centerX, centerY + 12f * s, pText);
    }

    private void drawDetailDiagnostic(Canvas c, float cx, float cy, float s) {
        float rowGap = 29f * s;
        float y = cy - 158f * s;
        String[][] cells = {
                {"方位", Float.isNaN(hub.azimuth) ? "--" : String.format(Locale.US, "%.0f°", hub.azimuth)},
                {"俯仰", Float.isNaN(hub.pitch) ? "--" : String.format(Locale.US, "%.0f°", hub.pitch)},
                {"横滚", Float.isNaN(hub.roll) ? "--" : String.format(Locale.US, "%.0f°", hub.roll)},
                {"电量", hub.battery >= 0 ? hub.battery + "%" : "--"},
                {"动势", motionValue()},
                {"角速", String.format(Locale.US, "%.0f°/s", angularSpeedDeg())},
                {"加速", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.ax, hub.ay, hub.az)},
                {"陀螺", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.gx, hub.gy, hub.gz)},
                {"磁力", String.format(Locale.US, "%.1f / %.1f / %.1f", hub.mx, hub.my, hub.mz)},
        };

        pText.setStyle(Paint.Style.FILL);
        pText.setTextSize(17f * s);
        pText.setColor(C_GOLD);
        drawTextCentered(c, "坤 · 诊断", cx, y - 25f * s, pText);

        for (String[] cell : cells) {
            drawDetailRow(c, cx, y, cell[0], cell[1], s);
            y += rowGap;
        }
    }

    private void drawDetailRow(Canvas c, float cx, float baseline, String label, String value, float s) {
        float half = RoundScreen.safeHalfWidthAt(getWidth(), getHeight(), baseline);
        float rowW = Math.min(470f * s, half * 2f - 52f * s);
        if (rowW <= 0) return;
        float rowH = 23f * s;
        RectF rr = new RectF(cx - rowW / 2f, baseline - rowH + 7f * s,
                cx + rowW / 2f, baseline + 7f * s);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(86, 12, 12, 12));
        c.drawRoundRect(rr, 8f * s, 8f * s, pFill);
        pStroke.setColor(a(C_GOLD_DIM, 72));
        pStroke.setStrokeWidth(0.8f * s);
        c.drawRoundRect(rr, 8f * s, 8f * s, pStroke);

        float labelW = 66f * s;
        float startX = cx - rowW / 2f + 14f * s;
        pText.setStyle(Paint.Style.FILL);
        pText.setTextSize(12.5f * s);
        pText.setColor(a(C_GOLD, 210));
        c.drawText(label, startX, baseline, pText);
        pText.setTextSize(12.8f * s);
        pText.setColor(a(C_TEXT, 225));
        String shown = ellipsize(value, pText, rowW - labelW - 26f * s);
        c.drawText(shown, startX + labelW, baseline, pText);
    }

    private float normalized360(float v) {
        float out = v % 360f;
        return out < 0 ? out + 360f : out;
    }

    private float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private float ringTextSize(float preferred, float ringWidth, float fitRatio, float min, float s) {
        return Math.max(min * s, Math.min(preferred * s, ringWidth * fitRatio));
    }

    private float magStrength() {
        float v = (float) Math.sqrt(hub.mx * hub.mx + hub.my * hub.my + hub.mz * hub.mz);
        return Float.isNaN(v) || v < 1f ? 0f : v;
    }

    private String magneticStatus(float mag) {
        if (mag <= 0f) return "磁场未定";
        if (mag < 25f) return "磁场偏弱";
        if (mag > 85f) return "磁场偏强";
        return "磁场正常";
    }

    private String locationState() {
        if (!Prefs.getB(getContext(), Prefs.K_SHOW_LOC, true)) return "定位关闭";
        if (!Double.isNaN(hub.lat)) return "GPS";
        if (!Double.isNaN(hub.netLat)) {
            String src = hub.netSrc == null || hub.netSrc.trim().isEmpty() ? "网络" : hub.netSrc.trim();
            if (src.startsWith("IP")) return "粗定位";
            return src.length() > 5 ? "网络" : src;
        }
        return "定位未定";
    }

    private String locationValue() {
        if (!Prefs.getB(getContext(), Prefs.K_SHOW_LOC, true)) return "已关闭";
        if (!Double.isNaN(hub.lat)) return hub.sats > 0 ? ("GPS " + hub.sats + "星") : "GPS";
        if (!Double.isNaN(hub.netLat)) {
            String src = hub.netSrc == null || hub.netSrc.trim().isEmpty() ? "网络" : hub.netSrc.trim();
            float acc = hub.netAcc;
            String accText = acc >= 1000f
                    ? String.format(Locale.US, "±%.1fkm", acc / 1000f)
                    : (acc > 0 ? String.format(Locale.US, "±%.0fm", acc) : "");
            return (src.startsWith("IP") ? "粗略" : src) + (accText.isEmpty() ? "" : " " + accText);
        }
        return "未定";
    }

    private float accelMagnitude() {
        return (float) Math.sqrt(hub.ax * hub.ax + hub.ay * hub.ay + hub.az * hub.az);
    }

    private float linearAccel() {
        return Math.abs(accelMagnitude() - 9.80665f);
    }

    private float angularSpeedDeg() {
        return (float) Math.sqrt(hub.gx * hub.gx + hub.gy * hub.gy + hub.gz * hub.gz) * 57.29578f;
    }

    private String motionState() {
        if (hub.lastSensorMs <= 0) return "动势未定";
        float spin = angularSpeedDeg();
        float shake = linearAccel();
        if (spin > 35f || shake > 1.6f) return "动势明显";
        if (spin > 8f || shake > 0.45f) return "轻微移动";
        return "地盘安定";
    }

    private String motionValue() {
        if (hub.lastSensorMs <= 0) return "--";
        float spin = angularSpeedDeg();
        float shake = linearAccel();
        if (spin > 6f) return String.format(Locale.US, "%.0f°/s", spin);
        if (shake > 0.20f) return String.format(Locale.US, "%.1fm/s²", shake);
        return "静置";
    }

    private String poseValue(float pitch, float roll) {
        float tilt = Math.max(Math.abs(pitch), Math.abs(roll));
        if (tilt < 8f) return "平稳";
        return String.format(Locale.US, "倾斜 %.0f°", tilt);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        float dx = ev.getX() - cx, dy = ev.getY() - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                previewIdx = -1;
                postInvalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    float moved = Math.abs(ev.getX() - downX) + Math.abs(ev.getY() - downY);
                    float slop = 24f * (r / 400f);
                    // 移动超过阈值，或手指落在外圈区域，进入绕圈滑动模式
                    if (moved > slop || dist > r * 0.30f) dragging = true;
                }
                if (dragging) {
                    float deg = (float) Math.toDegrees(Math.atan2(dy, dx)); // -180..180, 0 = right
                    int idx = sectorIndex(deg);
                    if (idx != previewIdx) {
                        previewIdx = idx;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        postInvalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (dragging) {
                    int idx = previewIdx;
                    dragging = false;
                    previewIdx = -1;
                    postInvalidate();
                    if (idx >= 0) actions.onSector(idx);
                    return true;
                }
                // 详情页中央用于切换摘要/诊断，不触发语音。
                if (detailMode && dist < r * 0.30f) {
                    detailDiagnostic = !detailDiagnostic;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    postInvalidate();
                    return true;
                }
                // 普通点按：中央=语音，外圈=直接打开
                if (dist < r * 0.30f) {
                    actions.onCenterTap();
                    return true;
                }
                if (dist > r * 0.55f && dist <= r) {
                    float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
                    actions.onSector(sectorIndex(deg));
                }
                return true;
            }
        }
        return true;
    }

    /** 角度 -> 八卦扇区（0=乾/顶部，顺时针）。 */
    private int sectorIndex(float deg) {
        return ((int) Math.floor((deg + 90f) / 45f) + 8) % 8;
    }

    /** 滑动选择时在中央放大显示对应功能。 */
    private void drawCenterPreview(Canvas c, float cx, float cy, float rMax, float s) {
        int[] tri = TRIGRAMS[previewIdx];
        float diskR = rMax * 0.235f;
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(225, 13, 13, 12));
        c.drawCircle(cx, cy, diskR, pFill);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_GOLD, 95));
        pStroke.setStrokeWidth(8f * s);
        c.drawCircle(cx, cy, diskR * 1.02f, pStroke);
        pStroke.setColor(C_GOLD);
        pStroke.setStrokeWidth(2.2f * s);
        c.drawCircle(cx, cy, diskR, pStroke);

        RectF scan = new RectF(cx - diskR * 1.2f, cy - diskR * 1.2f, cx + diskR * 1.2f, cy + diskR * 1.2f);
        pStroke.setColor(a(C_CYAN, 125));
        pStroke.setStrokeWidth(1.5f * s);
        c.drawArc(scan, -48f, 96f, false, pStroke);
        c.drawArc(scan, 132f, 96f, false, pStroke);

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(true);
        pText.setTextSize(25f * s);
        pText.setColor(C_GOLD);
        drawTextCentered(c, SECTOR_NAMES[previewIdx], cx, cy - 82f * s, pText);
        pText.setFakeBoldText(false);

        float barW = 150f * s;
        float barH = 20f * s;
        float gapY = 17f * s;
        float yinGap = 50f * s;
        float totalH = 3 * barH + 2 * gapY;
        float topY = cy - totalH / 2f - 2f * s;

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_GOLD);
        for (int i = 0; i < 3; i++) {
            int yao = tri[2 - i];
            float y = topY + i * (barH + gapY);
            if (yao == 1) {
                c.drawRoundRect(new RectF(cx - barW / 2f, y, cx + barW / 2f, y + barH),
                        4f * s, 4f * s, pFill);
            } else {
                float halfW = (barW - yinGap) / 2f;
                c.drawRoundRect(new RectF(cx - barW / 2f, y, cx - barW / 2f + halfW, y + barH),
                        4f * s, 4f * s, pFill);
                c.drawRoundRect(new RectF(cx + barW / 2f - halfW, y, cx + barW / 2f, y + barH),
                        4f * s, 4f * s, pFill);
            }
        }

        pSmall.setStyle(Paint.Style.FILL);
        pSmall.setTextSize(27f * s);
        pSmall.setColor(C_TEXT);
        drawTextCentered(c, SECTOR_LABELS[previewIdx], cx, topY + totalH + 35f * s, pSmall);
    }
}
