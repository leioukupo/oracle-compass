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
        float cx = w / 2f, cy = h / 2f;
        float scale = Math.min(w, h) / 800f;
        float rMax = Math.min(w, h) / 2f - 4f;

        canvas.drawColor(C_BG);
        canvas.drawCircle(cx, cy, rMax, pStroke(pStroke, C_GOLD, 2f * scale));

        float az = Float.isNaN(hub.azimuth) ? 0 : hub.azimuth;
        drawSectors(canvas, cx, cy, rMax, scale, dragging ? previewIdx : -1);
        drawCompassRing(canvas, cx, cy, rMax * 0.76f, az, scale);
        drawClock(canvas, cx, cy, rMax * 0.62f, scale);
        drawNeedle(canvas, cx, cy, rMax * 0.52f, az, scale);

        if (dragging && previewIdx >= 0) drawCenterPreview(canvas, cx, cy, scale);
        else if (detailMode) drawDetail(canvas, cx, cy, scale);
        else drawCenter(canvas, cx, cy, rMax * 0.26f, scale);

        drawReadouts(canvas, cx, cy, rMax * 0.42f, scale);
        drawStatus(canvas, cx, cy, scale);
    }

    private Paint pStroke(Paint p, int color, float width) {
        p.setColor(color); p.setStrokeWidth(width); return p;
    }

    private void drawSectors(Canvas c, float cx, float cy, float r, float s, int highlight) {
        RectF ring = new RectF(cx - r, cy - r, cx + r, cy + r);
        float inner = r * 0.86f;
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
            pText.setTextSize(26f * s); pText.setColor(C_GOLD);
            c.drawText(SECTOR_NAMES[i], mx - pText.measureText(SECTOR_NAMES[i]) / 2f, my - 4f * s, pText);
            pSmall.setTextSize(13f * s);
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

    private void drawClock(Canvas c, float cx, float cy, float r, float s) {
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
        java.util.Calendar now = java.util.Calendar.getInstance();
        String time = String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        pText.setTextSize(40f * s); pText.setColor(C_TEXT);
        c.drawText(time, cx - pText.measureText(time) / 2f, cy - r * 1.35f, pText);
        String date = String.format(Locale.US, "%d-%02d-%02d", now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
        pSmall.setTextSize(16f * s);
        c.drawText(date, cx - pSmall.measureText(date) / 2f, cy - r * 1.35f + 24f * s, pSmall);
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
        pFill.setStyle(Paint.Style.FILL); pFill.setColor(Color.rgb(14, 14, 14));
        c.drawCircle(cx, cy, r, pFill);
        pStroke.setColor(C_GOLD); pStroke.setStrokeWidth(3f * s);
        c.drawCircle(cx, cy, r, pStroke);
        Path p = new Path();
        p.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), 90, 180);
        p.addArc(new RectF(cx - r / 2f, cy - r, cx + r / 2f, cy + r), 270, 180);
        p.addArc(new RectF(cx - r / 2f, cy - r, cx + r / 2f, cy + r), 90, -180);
        p.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), 270, -180);
        p.close();
        pFill.setColor(C_RED);
        c.drawPath(p, pFill);
        pFill.setColor(C_GOLD);
        c.drawCircle(cx, cy - r / 2f, r * 0.11f, pFill);
        c.drawCircle(cx, cy + r / 2f, r * 0.11f, pFill);
    }

    private void drawReadouts(Canvas c, float cx, float cy, float y0, float s) {
        pSmall.setTextSize(16f * s);
        pSmall.setColor(C_TEXT);
        float y = cy + y0 * 0.55f;
        String azs = Float.isNaN(hub.azimuth) ? "--" : String.format(Locale.US, "%.0f°", hub.azimuth);
        String lines[] = {
                String.format(Locale.US, "加速度(m/s²) %.2f %.2f %.2f", hub.ax, hub.ay, hub.az),
                String.format(Locale.US, "陀螺仪(°/s) %.2f %.2f %.2f", hub.gx, hub.gy, hub.gz),
                String.format(Locale.US, "磁力(µT) %.1f %.1f %.1f", hub.mx, hub.my, hub.mz),
                String.format(Locale.US, "光 %slux  距 %scm  电 %d%%  向 %s",
                        hub.light < 0 ? "--" : String.format(Locale.US, "%.0f", hub.light),
                        hub.prox < 0 ? "--" : String.format(Locale.US, "%.1f", hub.prox),
                        hub.battery, azs)
        };
        for (String l : lines) {
            c.drawText(l, cx - pSmall.measureText(l) / 2f, y, pSmall);
            y += 22f * s;
        }
        String gps = Double.isNaN(hub.lat)
                ? "GPS 未定位"
                : String.format(Locale.US, "GPS %.5f,%.5f 海拔%.0fm %s", hub.lat, hub.lon, hub.alt, hub.gpsStatus);
        c.drawText(gps, cx - pSmall.measureText(gps) / 2f, y, pSmall);
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
        pText.setTextSize(19f * s); pText.setColor(C_TEXT);
        float y = cy - 90f * s;
        String[] lines = {
                String.format(Locale.US, "加速 %.2f/%.2f/%.2f", hub.ax, hub.ay, hub.az),
                String.format(Locale.US, "陀螺 %.2f/%.2f/%.2f", hub.gx, hub.gy, hub.gz),
                String.format(Locale.US, "磁力 %.1f/%.1f/%.1f", hub.mx, hub.my, hub.mz),
                String.format(Locale.US, "方位 %.0f° 俯仰 %.0f° 横滚 %.0f°", hub.azimuth, hub.pitch, hub.roll),
                String.format(Locale.US, "光 %.0f  距离 %.2f", hub.light, hub.prox),
                String.format(Locale.US, "电量 %d%%", hub.battery),
                Double.isNaN(hub.lat) ? "GPS 未定位" :
                        String.format(Locale.US, "GPS %.5f,%.5f", hub.lat, hub.lon)
        };
        for (String l : lines) {
            c.drawText(l, cx - pText.measureText(l) / 2f, y, pText);
            y += 26f * s;
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
        pText.setTextSize(88f * s);
        pText.setColor(C_GOLD);
        String ch = SECTOR_NAMES[previewIdx];
        c.drawText(ch, cx - pText.measureText(ch) / 2f, cy - 8f * s, pText);

        pSmall.setTextSize(26f * s);
        pSmall.setColor(C_TEXT);
        String lb = SECTOR_LABELS[previewIdx];
        c.drawText(lb, cx - pSmall.measureText(lb) / 2f, cy + 44f * s, pSmall);

        pSmall.setTextSize(15f * s);
        pSmall.setColor(C_DIM);
        String hint = "松手打开";
        c.drawText(hint, cx - pSmall.measureText(hint) / 2f, cy + 70f * s, pSmall);
    }
}
