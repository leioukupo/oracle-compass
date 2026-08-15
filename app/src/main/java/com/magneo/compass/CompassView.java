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

    // 天干地支
    private static final String[] TIAN_GAN = {"甲","乙","丙","丁","戊","己","庚","辛","壬","癸"};
    private static final String[] DI_ZHI = {"子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥"};

    private static final int C_GOLD = Color.rgb(212, 175, 55);
    private static final int C_RED = Color.rgb(139, 30, 30);
    private static final int C_TEXT = Color.rgb(232, 220, 192);
    private static final int C_DIM = Color.rgb(120, 114, 98);
    private static final int C_BG = Color.rgb(10, 10, 10);

    private final SensorHub hub;
    private final Actions actions;
    private String status = "";
    private boolean detailMode = false;
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
        pSmall.setColor(C_DIM);
    }

    public void setStatus(String s) { status = s == null ? "" : s; postInvalidate(); }
    public void toggleDetail() { detailMode = !detailMode; postInvalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = com.magneo.compass.ui.RoundScreen.cx(w), cy = com.magneo.compass.ui.RoundScreen.cy(h);
        float scale = com.magneo.compass.ui.RoundScreen.scale800(w, h);
        float rMax = com.magneo.compass.ui.RoundScreen.R(w, h) - 4f;  // 安全圆内缩 4px

        canvas.drawColor(C_BG);
        canvas.drawCircle(cx, cy, rMax, pStroke(pStroke, C_GOLD, 2f * scale));

        float az = Float.isNaN(hub.azimuth) ? 0 : hub.azimuth;
        drawSectors(canvas, cx, cy, rMax, scale, dragging ? previewIdx : -1);
        drawTianganDizhi(canvas, cx, cy, rMax, scale);
        drawBatteryRing(canvas, cx, cy, rMax, scale);
        drawCompassRing(canvas, cx, cy, rMax * 0.54f, az, scale);
        drawClock(canvas, cx, cy, rMax * 0.42f, scale);
        drawNeedle(canvas, cx, cy, rMax * 0.35f, az, scale);

        if (dragging && previewIdx >= 0) drawCenterPreview(canvas, cx, cy, scale);
        else if (detailMode) drawDetail(canvas, cx, cy, scale);
        else drawCenter(canvas, cx, cy, rMax * 0.22f, scale);

        drawReadouts(canvas, cx, cy, rMax * 0.42f, scale);
        drawStatus(canvas, cx, cy, scale);
    }

    private Paint pStroke(Paint p, int color, float width) {
        p.setColor(color); p.setStrokeWidth(width); return p;
    }

    private void drawSectors(Canvas c, float cx, float cy, float r, float s, int highlight) {
        RectF ring = new RectF(cx - r, cy - r, cx + r, cy + r);
        float inner = r * 0.86f;  // 八卦环恢复最宽（0.80→0.86），天干地支在 0.74~0.84 独立分区
        for (int i = 0; i < 8; i++) {
            float start = i * 45f - 90f;
            Path seg = new Path();
            RectF rr = new RectF(cx - inner, cy - inner, cx + inner, cy + inner);
            seg.arcTo(ring, start, 45f);
            seg.arcTo(rr, start + 45f, -45f);
            seg.close();
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(i == highlight ? Color.rgb(74, 56, 20) : (i % 2 == 0 ? Color.rgb(16, 16, 16) : Color.rgb(22, 22, 22)));
            c.drawPath(seg, pFill);
            pStroke.setColor(i == highlight ? C_GOLD : Color.rgb(150, 122, 42));
            pStroke.setStrokeWidth((i == highlight ? 4f : 2f) * s); pStroke.setStyle(Paint.Style.STROKE);
            c.drawPath(seg, pStroke);

            float mid = (float) Math.toRadians(start + 22.5f);
            float mr = (r + inner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save();
            c.rotate(start + 22.5f + 90f, mx, my);
            pText.setTextSize(28f * s); pText.setColor(C_GOLD);
            c.drawText(SECTOR_NAMES[i], mx - pText.measureText(SECTOR_NAMES[i]) / 2f, my - 4f * s, pText);
            pSmall.setTextSize(16f * s);
            c.drawText(SECTOR_LABELS[i], mx - pSmall.measureText(SECTOR_LABELS[i]) / 2f, my + 14f * s, pSmall);
            c.restore();
        }
    }

    private void drawCompassRing(Canvas c, float cx, float cy, float r, float az, float s) {
        c.save();
        c.rotate(-az, cx, cy);
        pStroke.setColor(C_DIM); pStroke.setStrokeWidth(1.5f * s);
        for (int d = 0; d < 360; d += 15) {
            float rad = (float) Math.toRadians(d - 90);
            float x1 = cx + (float) Math.cos(rad) * r * 0.92f;
            float y1 = cy + (float) Math.sin(rad) * r * 0.92f;
            float x2 = cx + (float) Math.cos(rad) * r;
            float y2 = cy + (float) Math.sin(rad) * r;
            c.drawLine(x1, y1, x2, y2, pStroke);
        }
        String[] dirs = {"北", "东", "南", "西"};
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(i * 90 - 90);
            float tx = cx + (float) Math.cos(rad) * r * 0.78f;
            float ty = cy + (float) Math.sin(rad) * r * 0.78f;
            pText.setTextSize(22f * s); pText.setColor(i == 0 ? C_RED : C_GOLD);
            c.drawText(dirs[i], tx - pText.measureText(dirs[i]) / 2f, ty + 8f * s, pText);
        }
        c.restore();
    }

    /** 天干地支环：参考外圈八卦，12地支+10天干各画成弧段扇区，当前年/月/日/时高亮。 */
    private void drawTianganDizhi(Canvas c, float cx, float cy, float rMax, float s) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int year = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);

        int yearGan = ((year - 4) % 10 + 10) % 10;
        int yearZhi = ((year - 4) % 12 + 12) % 12;
        int monthZhi = ((month + 1) % 12 + 12) % 12;
        int a = (14 - month) / 12;
        int y2 = year + 4800 - a;
        int m = month + 12 * a - 3;
        int jdn = day + (153 * m + 2) / 5 + 365 * y2 + y2 / 4 - y2 / 100 + y2 / 400 - 32045;
        int dayGan = ((jdn + 9) % 10 + 10) % 10;
        int dayZhi = ((jdn + 1) % 12 + 12) % 12;
        int hourZhi = ((hour + 1) / 2) % 12;
        int hourGan = ((dayGan % 5) * 2 + hourZhi) % 10;

        // 12 地支扇区（外圈）：rMax*0.73~0.85，宽0.12≈八卦0.14×0.86，每段 30°
        float zhiOuter = rMax * 0.85f, zhiInner = rMax * 0.73f;
        RectF zhiRingO = new RectF(cx - zhiOuter, cy - zhiOuter, cx + zhiOuter, cy + zhiOuter);
        RectF zhiRingI = new RectF(cx - zhiInner, cy - zhiInner, cx + zhiInner, cy + zhiInner);
        for (int i = 0; i < 12; i++) {
            float start = i * 30f - 90f;
            Path seg = new Path();
            seg.arcTo(zhiRingO, start, 30f);
            seg.arcTo(zhiRingI, start + 30f, -30f);
            seg.close();
            boolean hl = (i == yearZhi || i == monthZhi || i == dayZhi || i == hourZhi);
            pFill.setColor(hl ? Color.rgb(40, 34, 18) : Color.rgb(12, 12, 12));
            c.drawPath(seg, pFill);
            pStroke.setColor(hl ? C_GOLD : Color.rgb(60, 52, 30));
            pStroke.setStrokeWidth(1.5f * s); pStroke.setStyle(Paint.Style.STROKE);
            c.drawPath(seg, pStroke);
            // 文字居中
            float mid = (float) Math.toRadians(start + 15f);
            float mr = (zhiOuter + zhiInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save(); c.rotate(start + 15f + 90f, mx, my);
            pText.setTextSize(20f * s); pText.setColor(hl ? C_GOLD : Color.rgb(80, 72, 48));
            c.drawText(DI_ZHI[i], mx - pText.measureText(DI_ZHI[i]) / 2f, my + 7f * s, pText);
            c.restore();
        }

        // 10 天干扇区（内圈）：rMax*0.61~0.73，宽0.12，每段 36°，与地支紧邻
        float ganOuter = rMax * 0.73f, ganInner = rMax * 0.61f;
        RectF ganRingO = new RectF(cx - ganOuter, cy - ganOuter, cx + ganOuter, cy + ganOuter);
        RectF ganRingI = new RectF(cx - ganInner, cy - ganInner, cx + ganInner, cy + ganInner);
        for (int i = 0; i < 10; i++) {
            float start = i * 36f - 90f;
            Path seg = new Path();
            seg.arcTo(ganRingO, start, 36f);
            seg.arcTo(ganRingI, start + 36f, -36f);
            seg.close();
            boolean hl = (i == yearGan || i == dayGan || i == hourGan);
            pFill.setColor(hl ? Color.rgb(36, 30, 14) : Color.rgb(10, 10, 10));
            c.drawPath(seg, pFill);
            pStroke.setColor(hl ? C_GOLD : Color.rgb(50, 44, 26));
            pStroke.setStrokeWidth(1f * s); pStroke.setStyle(Paint.Style.STROKE);
            c.drawPath(seg, pStroke);
            float mid = (float) Math.toRadians(start + 18f);
            float mr = (ganOuter + ganInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save(); c.rotate(start + 18f + 90f, mx, my);
            pText.setTextSize(16f * s); pText.setColor(hl ? C_GOLD : Color.rgb(70, 62, 40));
            c.drawText(TIAN_GAN[i], mx - pText.measureText(TIAN_GAN[i]) / 2f, my + 5.5f * s, pText);
            c.restore();
        }
    }

    private void drawClock(Canvas c, float cx, float cy, float r, float s) {
        // 只保留 12 刻度线，时间/日期/方位/电量已移走
        for (int i = 0; i < 12; i++) {
            float rad = (float) Math.toRadians(i * 30 - 90);
            float x1 = cx + (float) Math.cos(rad) * r * 0.95f;
            float y1 = cy + (float) Math.sin(rad) * r * 0.95f;
            float x2 = cx + (float) Math.cos(rad) * r;
            float y2 = cy + (float) Math.sin(rad) * r;
            pStroke.setColor(i % 3 == 0 ? C_GOLD : C_DIM);
            pStroke.setStrokeWidth(i % 3 == 0 ? 3f * s : 1.5f * s);
            c.drawLine(x1, y1, x2, y2, pStroke);
        }
    }

    /** 电量环：紧挨天干内侧，金色弧按电量比例填充。 */
    private void drawBatteryRing(Canvas c, float cx, float cy, float rMax, float s) {
        float outer = rMax * 0.61f, inner = rMax * 0.57f;
        // 暗色轨道
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(Color.rgb(30, 26, 16)); pStroke.setStrokeWidth((outer - inner));
        float mid = (outer + inner) / 2f;
        c.drawCircle(cx, cy, mid, pStroke);
        // 金色弧（按电量%）
        if (hub.battery >= 0 && hub.battery <= 100) {
            float sweep = 360f * hub.battery / 100f;
            RectF arc = new RectF(cx - mid, cy - mid, cx + mid, cy + mid);
            pStroke.setColor(C_GOLD); pStroke.setStrokeWidth((outer - inner));
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(arc, -90f, sweep, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
        }
        // 电量数字在环下方
        if (hub.battery >= 0) {
            pSmall.setTextSize(11f * s); pSmall.setColor(C_DIM);
            String b = hub.battery + "%";
            c.drawText(b, cx - pSmall.measureText(b) / 2f, cy + outer + 14f * s, pSmall);
        }
    }

    private void drawNeedle(Canvas c, float cx, float cy, float r, float az, float s) {
        c.save();
        c.rotate(-az, cx, cy);
        pStroke.setColor(C_RED); pStroke.setStrokeWidth(4f * s);
        c.drawLine(cx, cy + r * 0.15f, cx, cy - r, pStroke);
        Path tri = new Path();
        tri.moveTo(cx - 8f * s, cy - r * 0.85f);
        tri.lineTo(cx + 8f * s, cy - r * 0.85f);
        tri.lineTo(cx, cy - r * 1.02f);
        tri.close();
        pFill.setStyle(Paint.Style.FILL); pFill.setColor(C_RED);
        c.drawPath(tri, pFill);
        c.restore();
    }

private void drawCenter(Canvas c, float cx, float cy, float r, float s) {
        // 太极背景
        pFill.setStyle(Paint.Style.FILL); pFill.setColor(Color.rgb(14, 14, 14));
        c.drawCircle(cx, cy, r, pFill);
        pStroke.setColor(C_GOLD); pStroke.setStrokeWidth(3f * s);
        c.drawCircle(cx, cy, r, pStroke);
        // 鱼 + 两点整体转 90°
        c.save();
        c.rotate(90f, cx, cy);
        Path p = new Path();
        p.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), 90, 180);
        p.addArc(new RectF(cx - r / 2f, cy - r, cx + r / 2f, cy + r), 270, 180);
        p.addArc(new RectF(cx - r / 2f, cy - r, cx + r / 2f, cy + r), 90, -180);
        p.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), 270, -180);
        p.close();
        pFill.setColor(Color.argb(160, 139, 30, 30));
        c.drawPath(p, pFill);
        pFill.setColor(C_GOLD);
        c.drawCircle(cx, cy - r / 2f, r * 0.11f, pFill);
        c.drawCircle(cx, cy + r / 2f, r * 0.11f, pFill);
        c.restore();
        // 方位度数在正中心
        pText.setTextSize(24f * s); pText.setColor(C_GOLD);
        String azs = Float.isNaN(hub.azimuth) ? "--" : String.format(Locale.US, "%.0f°", hub.azimuth);
        c.drawText(azs, cx - pText.measureText(azs) / 2f, cy + 8f * s, pText);
    }

    private void drawReadouts(Canvas c, float cx, float cy, float y0, float s) {
        // 方位/电量已移入 drawClock 区域，光/距设备无数据已删除
    }

    private void drawStatus(Canvas c, float cx, float cy, float s) {
        if (status.isEmpty()) return;
        pText.setTextSize(17f * s); pText.setColor(C_GOLD);
        float w = pText.measureText(status);
        float maxW = Math.min(getWidth(), getHeight()) * 0.7f;
        if (w > maxW) { pText.setTextSize(14f * s); w = pText.measureText(status); }
        c.drawText(status, cx - w / 2f, cy + 8f * s, pText);
    }

    private void drawDetail(Canvas c, float cx, float cy, float s) {
        float gapV = 42f * s;
        float topY = cy - 130f * s;
        float sw = getResources().getDisplayMetrics().widthPixels;
        float sh = getResources().getDisplayMetrics().heightPixels;
        String[][] cells = {
                {"方位", Float.isNaN(hub.azimuth) ? "--" : String.format(Locale.US, "%.0f°", hub.azimuth)},
                {"俯仰", Float.isNaN(hub.pitch) ? "--" : String.format(Locale.US, "%.0f°", hub.pitch)},
                {"横滚", Float.isNaN(hub.roll) ? "--" : String.format(Locale.US, "%.0f°", hub.roll)},
                {"电量", hub.battery >= 0 ? hub.battery + "%" : "--"},
                {"光线", hub.light >= 0 ? String.format(Locale.US, "%.0flx", hub.light) : "--"},
                {"距离", hub.prox >= 0 ? String.format(Locale.US, "%.1fcm", hub.prox) : "--"},
                {"加速", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.ax, hub.ay, hub.az)},
                {"陀螺", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.gx, hub.gy, hub.gz)},
                {"磁力", String.format(Locale.US, "%.1f / %.1f / %.1f", hub.mx, hub.my, hub.mz)},
        };
        float y = topY;
        // GPS 行
        if (Prefs.getB(getContext(), Prefs.K_SHOW_LOC, true)) {
            String gps = null;
            if (!Double.isNaN(hub.lat)) gps = String.format(Locale.US, "GPS %.4f, %.4f", hub.lat, hub.lon);
            else if (!Double.isNaN(hub.netLat)) gps = String.format(Locale.US, "%s %.4f, %.4f ±%.0fm", hub.netSrc, hub.netLat, hub.netLon, hub.netAcc);
            if (gps != null) {
                float halfW = com.magneo.compass.ui.RoundScreen.safeHalfWidthAt(sw, sh, y);
                pText.setTextSize(14f * s);
                if (pText.measureText(gps) > halfW * 2 - 8 * s)
                    gps = gps.substring(0, Math.max(8, (int) (gps.length() * (halfW * 2 - 8 * s) / pText.measureText(gps)))) + "…";
                pText.setColor(C_GOLD);
                c.drawText(gps, cx - pText.measureText(gps) / 2f, y, pText);
                y += gapV;
            }
        }
        // 单列居中：每行 label(左，金色描边) + value(右)
        for (String[] cell : cells) {
            if (cell[0].isEmpty()) continue;
            String lbl = cell[0] + "  ";
            // 先量宽度
            pText.setTextSize(18f * s);
            float lw = pText.measureText(lbl);
            pText.setTextSize(20f * s);
            float vw = pText.measureText(cell[1]);
            float startX = cx - (lw + vw) / 2f;
            // label：白色描边底 + 金色填充
            pText.setTextSize(18f * s);
            pText.setStyle(Paint.Style.STROKE); pText.setStrokeWidth(3f * s); pText.setColor(Color.WHITE);
            c.drawText(lbl, startX, y, pText);
            pText.setStyle(Paint.Style.FILL); pText.setColor(C_GOLD);
            c.drawText(lbl, startX, y, pText);
            // value
            pText.setTextSize(20f * s); pText.setColor(C_TEXT);
            c.drawText(cell[1], startX + lw, y, pText);
            y += gapV;
        }
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
    private void drawCenterPreview(Canvas c, float cx, float cy, float s) {
        int[] tri = TRIGRAMS[previewIdx];
        float barW = 170f * s;       // 爻长（110→170）
        float barH = 26f * s;        // 爻厚（18→26）
        float gapY = 20f * s;        // 爻间距（14→20）
        float yinGap = 56f * s;      // 阴爻断口（38→56）
        float totalH = 3 * barH + 2 * gapY;
        float topY = cy - totalH / 2f - 6f * s;

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_GOLD);
        for (int i = 0; i < 3; i++) {
            int yao = tri[2 - i];
            float y = topY + i * (barH + gapY);
            if (yao == 1) {
                c.drawRect(cx - barW / 2f, y, cx + barW / 2f, y + barH, pFill);
            } else {
                float halfW = (barW - yinGap) / 2f;
                c.drawRect(cx - barW / 2f, y, cx - barW / 2f + halfW, y + barH, pFill);
                c.drawRect(cx + barW / 2f - halfW, y, cx + barW / 2f, y + barH, pFill);
            }
        }

        pSmall.setTextSize(28f * s);
        pSmall.setColor(C_TEXT);
        String lb = SECTOR_LABELS[previewIdx];
        c.drawText(lb, cx - pSmall.measureText(lb) / 2f, topY + totalH + 34f * s, pSmall);

        pSmall.setTextSize(15f * s);
        pSmall.setColor(C_DIM);
        String hint = "松手打开";
        c.drawText(hint, cx - pSmall.measureText(hint) / 2f, topY + totalH + 58f * s, pSmall);
    }
}
