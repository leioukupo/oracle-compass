package com.magneo.compass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.magneo.compass.ui.RoundScreen;
import com.magneo.compass.ui.Ui;
import com.magneo.compass.voice.VoiceVisualPhase;
import com.magneo.compass.voice.VoiceVisualState;

import java.util.Calendar;
import java.util.Locale;

/** 真理罗盘主视图：外圈八卦功能舱 + 罗盘/时钟 + 传感器读数 + 中央太极。 */
public class CompassView extends View {
    public interface Actions {
        void onSector(int index);
        void onCenterTap();
        void onOracleReading(OracleReading reading);
        void onOraclePageLeft();
    }

    public static final String[] SECTOR_NAMES = {"乾", "坎", "艮", "震", "巽", "离", "兑", "坤"};
    public static final String[] SECTOR_LABELS = {"应用", "网盘", "设置", "系统", "音乐", "灵眼", "浏览", "详情"};
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
    private boolean glMainMode = false;
    private boolean detailMode = false;
    private static final int DETAIL_EARTH = 0;
    private static final int DETAIL_SATELLITES = 1;
    private static final int DETAIL_CALIBRATION = 2;
    private static final int DETAIL_ORACLE = 3;
    private static final int DETAIL_DIAGNOSTIC = 4;
    private static final int DETAIL_PAGE_COUNT = 5;
    private int detailPage = DETAIL_EARTH;
    private boolean dragging = false;
    private int previewIdx = -1;
    private float downX, downY;
    private boolean magCalCollecting = false;
    private boolean magCalSaved = false;
    private int magCalSamples = 0;
    private float magCalMinX, magCalMinY, magCalMinZ, magCalMaxX, magCalMaxY, magCalMaxZ;
    private long magCalLastSampleMs = 0;
    private OracleReading oracleReading;
    private boolean oracleCollecting = false;
    private long oracleCollectStartMs = 0;
    private long oracleLastAccelMs = 0;
    private long oracleLastPeakMs = 0;
    private long oracleEntropy = 0;
    private int oraclePeakCount = 0;
    private float oraclePeakEnergy = 0f;
    private String oracleHint = "";
    private long oracleHintUntil = 0;
    private boolean effectFrameScheduled;
    private boolean attached;
    private long chargingEffectStartedAtMs;

    private final VoiceVisualState.Listener voiceVisualListener =
            new VoiceVisualState.Listener() {
                @Override public void onVoiceVisualStateChanged() {
                    post(new Runnable() {
                        @Override public void run() { wakeEffectAnimation(); }
                    });
                }
            };
    private final Runnable effectFrame = new Runnable() {
        @Override public void run() {
            effectFrameScheduled = false;
            if (!attached) return;
            postInvalidate();
            VoiceVisualState.Snapshot visual = VoiceVisualState.snapshot();
            if (!hasAnimatedEffect(visual)) return;
            scheduleEffectFrame(visual);
        }
    };

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBitmap = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Path tmpPath = new Path();
    private final RectF tmpRectA = new RectF();
    private final RectF tmpRectB = new RectF();
    private final RectF tmpRectC = new RectF();
    private final RectF calibrationResetHit = new RectF();
    private Bitmap staticLayer;
    private int staticW = -1;
    private int staticH = -1;
    private int staticHourZhi = -1;
    private int staticHourGan = -1;

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

    public void setGlMainMode(boolean enabled) {
        if (glMainMode == enabled) return;
        glMainMode = enabled;
        setWillNotDraw(false);
        postInvalidate();
        wakeEffectAnimation();
    }

    public void onBatteryStateChanged() {
        chargingEffectStartedAtMs = SystemClock.uptimeMillis();
        wakeEffectAnimation();
    }

    public void toggleDetail() {
        detailMode = !detailMode;
        enterDetailPage(DETAIL_EARTH);
        wakeEffectAnimation();
    }

    public void syncHardwareDemand() {
        applyDetailResourceDemand();
    }

    public boolean isOracleDetailActive() {
        return detailMode && detailPage == DETAIL_ORACLE;
    }

    public boolean isOracleCollecting() {
        return oracleCollecting;
    }

    public void setOracleAiResult(long readingId, String text, String status) {
        if (oracleReading == null || oracleReading.id != readingId) return;
        oracleReading.aiText = text == null ? "" : text.trim();
        oracleReading.aiStatus = status == null ? "" : status.trim();
        postInvalidate();
    }

    private void enterDetailPage(int page) {
        boolean leavingOracle = detailPage == DETAIL_ORACLE && page != DETAIL_ORACLE;
        detailPage = page;
        calibrationResetHit.setEmpty();
        if (page == DETAIL_CALIBRATION) beginMagCalibration();
        else magCalCollecting = false;
        if (page != DETAIL_ORACLE) {
            stopOracleCollecting();
            if (leavingOracle && actions != null) actions.onOraclePageLeft();
        }
        applyDetailResourceDemand();
    }

    private void applyDetailResourceDemand() {
        boolean needGps = Prefs.locSourceGpsDiag(getContext())
                || (detailMode && detailPage == DETAIL_SATELLITES);
        hub.setGpsEnabled(needGps);
        boolean needGyro = detailMode && (detailPage == DETAIL_EARTH || detailPage == DETAIL_DIAGNOSTIC);
        boolean needRawDiagnostic = detailMode && detailPage == DETAIL_DIAGNOSTIC;
        hub.setSensorDemand(needGyro, needRawDiagnostic);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = RoundScreen.cx(w), cy = RoundScreen.cy(h);
        float scale = RoundScreen.scale800(w, h);
        float rMax = RoundScreen.R(w, h) - 4f;  // 安全圆内缩 4px

        VoiceVisualState.Snapshot visual = VoiceVisualState.snapshot();
        long effectNow = SystemClock.uptimeMillis();
        if (glMainMode && !dragging && !detailMode) {
            drawChargingEffect(canvas, cx, cy, rMax, scale, effectNow);
            drawVoiceEffect(canvas, cx, cy, rMax, scale, visual, effectNow);
            drawStatus(canvas, cx, cy, rMax, scale);
            return;
        }

        float az = Float.isNaN(hub.azimuth) ? 0 : hub.azimuth;
        int highlight = dragging ? previewIdx : -1;
        Calendar now = Calendar.getInstance();
        int hourZhi = currentHourZhi(now);
        int hourGan = currentHourGan(now, hourZhi);
        drawStaticLayer(canvas, cx, cy, rMax, scale, hourZhi, hourGan);
        if (highlight >= 0) drawSectorHighlight(canvas, cx, cy, rMax, scale, highlight);
        drawBatteryRing(canvas, cx, cy, rMax, scale);
        drawCompassRing(canvas, cx, cy, rMax * 0.55f, az, scale);
        drawClock(canvas, cx, cy, rMax * 0.36f, scale, now);

        if (dragging && previewIdx >= 0) drawCenterPreview(canvas, cx, cy, rMax, scale);
        else if (detailMode) drawDetail(canvas, cx, cy, scale);
        else drawCenter(canvas, cx, cy, rMax * 0.235f, scale, hub.azimuth);

        drawChargingEffect(canvas, cx, cy, rMax, scale, effectNow);
        if (!dragging && !detailMode) {
            drawVoiceEffect(canvas, cx, cy, rMax, scale, visual, effectNow);
        }
        drawStatus(canvas, cx, cy, rMax, scale);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (chargingEffectStartedAtMs == 0L) {
            chargingEffectStartedAtMs = SystemClock.uptimeMillis();
        }
        VoiceVisualState.addListener(voiceVisualListener);
        wakeEffectAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recycleStaticLayer();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(effectFrame);
        effectFrameScheduled = false;
        VoiceVisualState.removeListener(voiceVisualListener);
        detailMode = false;
        stopOracleCollecting();
        applyDetailResourceDemand();
        recycleStaticLayer();
        super.onDetachedFromWindow();
    }

    private void wakeEffectAnimation() {
        postInvalidate();
        VoiceVisualState.Snapshot visual = VoiceVisualState.snapshot();
        if (hasAnimatedEffect(visual)) scheduleEffectFrame(visual);
        else if (effectFrameScheduled) {
            removeCallbacks(effectFrame);
            effectFrameScheduled = false;
        }
    }

    private void scheduleEffectFrame(VoiceVisualState.Snapshot visual) {
        if (!attached || effectFrameScheduled || !hasAnimatedEffect(visual)) return;
        effectFrameScheduled = true;
        postDelayed(effectFrame, effectFrameDelayMs(visual));
    }

    private boolean hasAnimatedEffect(VoiceVisualState.Snapshot visual) {
        return isChargingAnimationActive()
                || (!dragging && !detailMode && visual != null
                && visual.phase != VoiceVisualPhase.IDLE);
    }

    private boolean isChargingAnimationActive() {
        return hub.batteryCharging && !hub.batteryFull
                && hub.battery >= 0 && hub.battery < 100;
    }

    private long effectFrameDelayMs(VoiceVisualState.Snapshot visual) {
        if (animationPowerCapped()) return 125L;
        if (visual != null && (visual.phase == VoiceVisualPhase.THINKING
                || visual.phase == VoiceVisualPhase.SPEAKING
                || visual.phase == VoiceVisualPhase.ERROR)) {
            return 83L;
        }
        return 100L;
    }

    private boolean animationPowerCapped() {
        if (!glMainMode || dragging || detailMode
                || Prefs.MAIN_FPS_POWER.equals(Prefs.mainFpsMode(getContext()))) {
            return true;
        }
        if (!hub.batteryCharging && hub.battery >= 0 && hub.battery < 15) return true;
        return com.magneo.compass.web.ScreenStreamer.isActive()
                || com.magneo.compass.web.H264Streamer.isActive()
                || com.magneo.compass.web.H264SurfaceStreamer.isActive();
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

    private Path ringSegment(Path path, RectF outer, RectF inner, float start, float sweep) {
        path.reset();
        path.arcTo(outer, start, sweep);
        path.arcTo(inner, start + sweep, -sweep);
        path.close();
        return path;
    }

    private void drawStaticLayer(Canvas c, float cx, float cy, float rMax, float s, int hourZhi, int hourGan) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (staticLayer == null || staticLayer.isRecycled() || staticW != w || staticH != h
                || staticHourZhi != hourZhi || staticHourGan != hourGan) {
            rebuildStaticLayer(w, h, cx, cy, rMax, s, hourZhi, hourGan);
        }
        if (staticLayer != null && !staticLayer.isRecycled()) {
            c.drawBitmap(staticLayer, 0f, 0f, pBitmap);
        } else {
            c.drawColor(C_BG_DEEP);
            drawMysticField(c, cx, cy, rMax, s);
            drawSectors(c, cx, cy, rMax, s, -1);
            drawTianganDizhi(c, cx, cy, rMax, s, hourZhi, hourGan);
        }
    }

    private void rebuildStaticLayer(int w, int h, float cx, float cy, float rMax, float s,
                                    int hourZhi, int hourGan) {
        Bitmap next = null;
        try {
            next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas off = new Canvas(next);
            off.drawColor(C_BG_DEEP);
            drawMysticField(off, cx, cy, rMax, s);
            drawSectors(off, cx, cy, rMax, s, -1);
            drawTianganDizhi(off, cx, cy, rMax, s, hourZhi, hourGan);
            Bitmap old = staticLayer;
            staticLayer = next;
            staticW = w;
            staticH = h;
            staticHourZhi = hourZhi;
            staticHourGan = hourGan;
            if (old != null && old != next && !old.isRecycled()) old.recycle();
        } catch (OutOfMemoryError oom) {
            if (next != null && !next.isRecycled()) next.recycle();
            recycleStaticLayer();
        }
    }

    private void recycleStaticLayer() {
        if (staticLayer != null && !staticLayer.isRecycled()) staticLayer.recycle();
        staticLayer = null;
        staticW = -1;
        staticH = -1;
        staticHourZhi = -1;
        staticHourGan = -1;
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

        tmpRectA.set(cx - r * 0.53f, cy - r * 0.53f, cx + r * 0.53f, cy + r * 0.53f);
        pStroke.setColor(a(C_CYAN, 98));
        pStroke.setStrokeWidth(2.2f * s);
        c.drawArc(tmpRectA, -30f, 58f, false, pStroke);
        c.drawArc(tmpRectA, 152f, 48f, false, pStroke);

        pStroke.setColor(C_GOLD);
        pStroke.setStrokeWidth(2.6f * s);
        c.drawCircle(cx, cy, r, pStroke);
    }

    private void drawSectors(Canvas c, float cx, float cy, float r, float s, int highlight) {
        tmpRectA.set(cx - r, cy - r, cx + r, cy + r);
        float innerR = r * 0.865f;
        tmpRectB.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
        for (int i = 0; i < 8; i++) {
            float start = i * 45f - 90f;
            drawSectorSegment(c, cx, cy, r, innerR, start, i, i == highlight, s);
        }
    }

    private void drawSectorHighlight(Canvas c, float cx, float cy, float r, float s, int highlight) {
        if (highlight < 0 || highlight >= 8) return;
        tmpRectA.set(cx - r, cy - r, cx + r, cy + r);
        float innerR = r * 0.865f;
        tmpRectB.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
        drawSectorSegment(c, cx, cy, r, innerR, highlight * 45f - 90f, highlight, true, s);
    }

    private void drawSectorSegment(Canvas c, float cx, float cy, float r, float innerR,
                                   float start, int idx, boolean hot, float s) {
        Path seg = ringSegment(tmpPath, tmpRectA, tmpRectB, start, 45f);

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(hot
                ? Color.rgb(86, 58, 18)
                : (idx % 2 == 0 ? Color.rgb(39, 25, 13) : Color.rgb(30, 22, 15)));
        c.drawPath(seg, pFill);
        drawSectorTexture(c, cx, cy, r, innerR, start, idx, hot, s);

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
        drawTextCenteredOnPoint(c, SECTOR_NAMES[idx], mx, my, pText);
        pText.setFakeBoldText(false);
        c.restore();
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
        tmpRectC.set(cx - texR1, cy - texR1, cx + texR1, cy + texR1);
        c.drawArc(tmpRectC, start + 6f, 33f, false, pStroke);
        tmpRectC.set(cx - texR2, cy - texR2, cx + texR2, cy + texR2);
        c.drawArc(tmpRectC, start + 6f, 33f, false, pStroke);

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
                tmpRectC.set(x - barW / 2f, yy, x + barW / 2f, yy + barH);
                c.drawRoundRect(tmpRectC, radius, radius, pFill);
            } else {
                float halfW = (barW - yinGap) / 2f;
                tmpRectC.set(x - barW / 2f, yy, x - barW / 2f + halfW, yy + barH);
                c.drawRoundRect(tmpRectC, radius, radius, pFill);
                tmpRectC.set(x + barW / 2f - halfW, yy, x + barW / 2f, yy + barH);
                c.drawRoundRect(tmpRectC, radius, radius, pFill);
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
            float baseTick = 2.05f * s;
            pStroke.setColor(cardinal ? a(C_GOLD, 250) : (major ? a(C_GOLD, 220) : a(C_GOLD, 172)));
            pStroke.setStrokeWidth(cardinal ? baseTick * 2.05f : (major ? baseTick * 1.45f : baseTick));
            c.drawLine(x1, y1, x2, y2, pStroke);
        }

        String[] dirs = {"北", "东", "南", "西"};
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(i * 90 - 90);
            float tx = cx + (float) Math.cos(rad) * r * 0.70f;
            float ty = cy + (float) Math.sin(rad) * r * 0.70f;
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(22f * s);
            pText.setFakeBoldText(true);
            pText.setColor(Color.rgb(232, 194, 72));
            drawTextCentered(c, dirs[i], tx, ty + 8f * s, pText);
        }
        pText.setFakeBoldText(false);
        c.restore();
    }

    private int currentHourZhi(Calendar cal) {
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return ((hour + 1) / 2) % 12;
    }

    private int currentHourGan(Calendar cal, int hourZhi) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int a = (14 - month) / 12;
        int y2 = year + 4800 - a;
        int m = month + 12 * a - 3;
        int jdn = day + (153 * m + 2) / 5 + 365 * y2 + y2 / 4 - y2 / 100 + y2 / 400 - 32045;
        int dayGan = ((jdn + 9) % 10 + 10) % 10;
        return ((dayGan % 5) * 2 + hourZhi) % 10;
    }

    /** 天干地支环：保留术数信息，但压低对比度，让它成为外圈导航和中心罗盘之间的辅助纹理。 */
    private void drawTianganDizhi(Canvas c, float cx, float cy, float rMax, float s,
                                  int hourZhi, int hourGan) {
        float zhiOuter = rMax * 0.865f, zhiInner = rMax * 0.715f;
        tmpRectA.set(cx - zhiOuter, cy - zhiOuter, cx + zhiOuter, cy + zhiOuter);
        tmpRectB.set(cx - zhiInner, cy - zhiInner, cx + zhiInner, cy + zhiInner);
        for (int i = 0; i < 12; i++) {
            float start = i * 30f - 90f;
            Path seg = ringSegment(tmpPath, tmpRectA, tmpRectB, start, 30f);
            boolean hl = i == hourZhi;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(hl ? Color.rgb(86, 58, 18) : Color.rgb(35, 27, 14));
            c.drawPath(seg, pFill);
            c.drawPath(seg, pStroke(pStroke, hl ? a(C_GOLD, 255) : a(C_GOLD, 150),
                    (hl ? 1.9f : 1.35f) * s));
            float mid = (float) Math.toRadians(start + 15f);
            float mr = (zhiOuter + zhiInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save();
            c.rotate(start + 15f + 90f, mx, my);
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(ringTextSize(23f, zhiOuter - zhiInner, 0.50f, 17f, s));
            pText.setFakeBoldText(true);
            if (hl) pText.setShadowLayer(7f * s, 0f, 0f, a(C_GOLD, 170));
            pText.setColor(hl ? Color.rgb(255, 226, 92) : Color.rgb(211, 176, 74));
            drawTextCenteredOnPoint(c, DI_ZHI[i], mx, my, pText);
            pText.clearShadowLayer();
            pText.setFakeBoldText(false);
            c.restore();
        }

        float ganOuter = zhiInner, ganInner = rMax * 0.610f;
        tmpRectA.set(cx - ganOuter, cy - ganOuter, cx + ganOuter, cy + ganOuter);
        tmpRectB.set(cx - ganInner, cy - ganInner, cx + ganInner, cy + ganInner);
        for (int i = 0; i < 10; i++) {
            float start = i * 36f - 90f;
            Path seg = ringSegment(tmpPath, tmpRectA, tmpRectB, start, 36f);
            boolean hl = i == hourGan;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(hl ? Color.rgb(72, 48, 17) : Color.rgb(31, 25, 14));
            c.drawPath(seg, pFill);
            c.drawPath(seg, pStroke(pStroke, hl ? a(C_GOLD, 245) : a(C_GOLD, 132),
                    (hl ? 1.5f : 1.15f) * s));

            float mid = (float) Math.toRadians(start + 18f);
            float mr = (ganOuter + ganInner) / 2f;
            float mx = cx + (float) Math.cos(mid) * mr;
            float my = cy + (float) Math.sin(mid) * mr;
            c.save();
            c.rotate(start + 18f + 90f, mx, my);
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(ringTextSize(19f, ganOuter - ganInner, 0.50f, 15.5f, s));
            pText.setFakeBoldText(true);
            if (hl) pText.setShadowLayer(5.5f * s, 0f, 0f, a(C_GOLD, 140));
            pText.setColor(hl ? Color.rgb(255, 220, 86) : Color.rgb(202, 168, 72));
            drawTextCenteredOnPoint(c, TIAN_GAN[i], mx, my, pText);
            pText.clearShadowLayer();
            pText.setFakeBoldText(false);
            c.restore();
        }

        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(a(C_GOLD, 135));
        pStroke.setStrokeWidth(1.35f * s);
        c.drawCircle(cx, cy, zhiOuter, pStroke);
        c.drawCircle(cx, cy, zhiInner, pStroke);
        pStroke.setColor(a(C_GOLD, 96));
        pStroke.setStrokeWidth(1.0f * s);
        c.drawCircle(cx, cy, ganInner, pStroke);
    }

    private void drawClock(Canvas c, float cx, float cy, float r, float s, Calendar cal) {
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

        float min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f;
        float hour = (cal.get(Calendar.HOUR) % 12) + min / 60f;
        drawClockHand(c, cx, cy, r * 0.54f, hour * 30f, a(C_GOLD, 135), 3.0f * s);
        drawClockHand(c, cx, cy, r * 0.76f, min * 6f, a(C_GOLD, 165), 2.0f * s);
        drawSecondComet(c, cx, cy, r, cal.get(Calendar.SECOND) * 6f, s);
    }

    private void drawSecondComet(Canvas c, float cx, float cy, float r, float deg, float s) {
        float trailR = r * 0.82f;
        float end = deg - 90f;
        float span = 38f;
        float step = span / 8f;
        tmpRectC.set(cx - trailR, cy - trailR, cx + trailR, cy + trailR);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            float t = (i + 1f) / 8f;
            pStroke.setColor(a(C_GOLD, (int) (18 + 118 * t)));
            pStroke.setStrokeWidth((1.7f + 1.35f * t) * s);
            c.drawArc(tmpRectC, end - span + i * step, step * 0.82f, false, pStroke);
        }
        drawClockHand(c, cx, cy, trailR, deg, a(C_GOLD, 105), 7.4f * s);
        drawClockHand(c, cx, cy, trailR, deg, Color.rgb(255, 220, 86), 3.7f * s);
        float rad = (float) Math.toRadians(deg - 90f);
        float tx = cx + (float) Math.cos(rad) * trailR;
        float ty = cy + (float) Math.sin(rad) * trailR;
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(a(C_GOLD, 72));
        c.drawCircle(tx, ty, 5.8f * s, pFill);
        pFill.setColor(Color.rgb(255, 226, 92));
        c.drawCircle(tx, ty, 3.0f * s, pFill);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
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
        pStroke.setColor(a(usedColor, 224));
        pStroke.setStrokeWidth(outer - inner - 1.4f * s);
        c.drawCircle(cx, cy, mid, pStroke);
        pStroke.setStrokeWidth(1.0f * s);
        pStroke.setColor(a(C_GOLD, 46));
        c.drawCircle(cx, cy, outer - 0.5f * s, pStroke);
        c.drawCircle(cx, cy, inner + 0.5f * s, pStroke);

        if (hub.battery >= 0 && hub.battery <= 100) {
            float sweep = 360f * hub.battery / 100f;
            tmpRectA.set(cx - mid, cy - mid, cx + mid, cy + mid);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            pStroke.setColor(a(remainColor, 72));
            pStroke.setStrokeWidth(outer - inner + 1.2f * s);
            c.drawArc(tmpRectA, -90f, sweep, false, pStroke);
            pStroke.setColor(remainColor);
            pStroke.setStrokeWidth(outer - inner - 1.6f * s);
            c.drawArc(tmpRectA, -90f, sweep, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    private void drawChargingEffect(Canvas c, float cx, float cy, float rMax, float s,
                                    long now) {
        if (!hub.batteryCharging || hub.battery < 0 || hub.battery > 100) return;
        float outer = rMax * 0.595f;
        float inner = rMax * 0.565f;
        float mid = (outer + inner) / 2f;
        float width = outer - inner - 2.2f * s;
        tmpRectA.set(cx - mid, cy - mid, cx + mid, cy + mid);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);

        if (hub.batteryFull || hub.battery >= 100) {
            pStroke.setColor(a(Color.rgb(255, 220, 82), 190));
            pStroke.setStrokeWidth(width);
            c.drawArc(tmpRectA, -90f, 360f, false, pStroke);
            pStroke.setColor(a(C_GOLD, 70));
            pStroke.setStrokeWidth(width + 3.6f * s);
            c.drawArc(tmpRectA, -90f, 360f, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
            return;
        }

        float filled = hub.battery * 3.6f;
        float remaining = 360f - filled;
        float start = -90f + filled;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now * Math.PI * 2.0 / 1200.0);
        float targetRad = (float) Math.toRadians(270f);
        float targetX = cx + (float) Math.cos(targetRad) * mid;
        float targetY = cy + (float) Math.sin(targetRad) * mid;
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(a(Color.rgb(255, 222, 88), (int) (78 + pulse * 96)));
        c.drawCircle(targetX, targetY, (3.8f + pulse * 2.0f) * s, pFill);
        pFill.setColor(Color.rgb(255, 231, 110));
        c.drawCircle(targetX, targetY, 2.2f * s, pFill);

        if (remaining < 12f) {
            pStroke.setColor(a(Color.rgb(255, 218, 74), (int) (100 + pulse * 110)));
            pStroke.setStrokeWidth(width + pulse * 1.6f * s);
            c.drawArc(tmpRectA, start, remaining, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
            return;
        }

        long duration = Math.max(100L, Math.round(remaining / 110f * 1000f));
        long elapsed = Math.max(0L, now - chargingEffectStartedAtMs);
        float progress = (elapsed % duration) / (float) duration;
        float head = start + remaining * progress;
        float tail = Math.min(28f, Math.max(10f, remaining * 0.22f));
        int pieces = 8;
        for (int i = pieces - 1; i >= 0; i--) {
            float segStart = head - tail * (i + 1f) / pieces;
            float segEnd = head - tail * i / pieces;
            if (segEnd <= start) continue;
            segStart = Math.max(start, segStart);
            float strength = 1f - i / (float) pieces;
            pStroke.setColor(a(Color.rgb(255, 211, 58),
                    (int) (30 + strength * 190)));
            pStroke.setStrokeWidth(width * (0.48f + strength * 0.48f));
            c.drawArc(tmpRectA, segStart, Math.max(0.2f, segEnd - segStart), false, pStroke);
        }

        float headRad = (float) Math.toRadians(head);
        float headX = cx + (float) Math.cos(headRad) * mid;
        float headY = cy + (float) Math.sin(headRad) * mid;
        pFill.setColor(a(Color.rgb(255, 221, 82), 88));
        c.drawCircle(headX, headY, 6.2f * s, pFill);
        pFill.setColor(Color.rgb(255, 236, 132));
        c.drawCircle(headX, headY, 3.0f * s, pFill);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawVoiceEffect(Canvas c, float cx, float cy, float rMax, float s,
                                 VoiceVisualState.Snapshot visual, long now) {
        float r = rMax * 0.235f;
        float ringR = r * 1.14f;
        VoiceVisualPhase phase = visual == null ? VoiceVisualPhase.IDLE : visual.phase;
        if (phase == VoiceVisualPhase.IDLE) {
            drawStaticVoiceOrnaments(c, cx, cy, ringR, s, true, true);
        } else if (phase == VoiceVisualPhase.LISTENING) {
            drawStaticVoiceOrnaments(c, cx, cy, ringR, s, false, true);
            drawListeningEffect(c, cx, cy, ringR, s, visual.inputLevel, now);
        } else if (phase == VoiceVisualPhase.THINKING) {
            drawThinkingEffect(c, cx, cy, ringR, s, visual.phaseStartedAtMs, now);
        } else if (phase == VoiceVisualPhase.SPEAKING) {
            drawStaticVoiceOrnaments(c, cx, cy, ringR, s, true, true);
            drawSpeakingEffect(c, cx, cy, r * 0.93f, s, visual.outputLevel);
        } else if (phase == VoiceVisualPhase.ERROR) {
            drawVoiceErrorEffect(c, cx, cy, ringR, s, visual.phaseStartedAtMs, now);
        }
    }

    private void drawStaticVoiceOrnaments(Canvas c, float cx, float cy, float ringR, float s,
                                          boolean cyanArcs, boolean goldMarkers) {
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        if (cyanArcs) {
            float radius = ringR * 1.17f;
            tmpRectA.set(cx - radius, cy - radius, cx + radius, cy + radius);
            pStroke.setColor(a(C_CYAN, 110));
            pStroke.setStrokeWidth(1.7f * s);
            c.drawArc(tmpRectA, -26f, 52f, false, pStroke);
            c.drawArc(tmpRectA, 154f, 52f, false, pStroke);
        }
        if (goldMarkers) {
            pStroke.setColor(a(C_GOLD, 130));
            pStroke.setStrokeWidth(1.2f * s);
            for (int i = 0; i < 4; i++) {
                float rad = (float) Math.toRadians(-90f + i * 90f);
                c.drawLine(cx + (float) Math.cos(rad) * ringR * 0.90f,
                        cy + (float) Math.sin(rad) * ringR * 0.90f,
                        cx + (float) Math.cos(rad) * ringR * 1.03f,
                        cy + (float) Math.sin(rad) * ringR * 1.03f, pStroke);
            }
        }
    }

    private void drawListeningEffect(Canvas c, float cx, float cy, float ringR, float s,
                                     float inputLevel, long now) {
        float level = Math.max(0f, Math.min(1f, inputLevel));
        float idleBreath = 0.5f + 0.5f * (float) Math.sin(now * Math.PI * 2.0 / 1800.0);
        float energy = Math.max(0.10f + idleBreath * 0.08f, level);
        float radius = ringR * (1.17f + energy * 0.018f);
        float span = 52f + energy * 10f;
        tmpRectA.set(cx - radius, cy - radius, cx + radius, cy + radius);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_CYAN, (int) (34 + energy * 72)));
        pStroke.setStrokeWidth((4.8f + energy * 2.0f) * s);
        c.drawArc(tmpRectA, -26f - (span - 52f) / 2f, span, false, pStroke);
        c.drawArc(tmpRectA, 154f - (span - 52f) / 2f, span, false, pStroke);
        pStroke.setColor(a(C_CYAN, (int) (92 + energy * 150)));
        pStroke.setStrokeWidth((1.5f + energy * 2.3f) * s);
        c.drawArc(tmpRectA, -26f - (span - 52f) / 2f, span, false, pStroke);
        c.drawArc(tmpRectA, 154f - (span - 52f) / 2f, span, false, pStroke);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawThinkingEffect(Canvas c, float cx, float cy, float ringR, float s,
                                    long startedAt, long now) {
        float elapsed = Math.max(0L, now - startedAt) / 1000f;
        float cyanRot = (elapsed * 72f) % 360f;
        float goldRot = -(elapsed * 43f) % 360f;
        float radius = ringR * 1.17f;
        tmpRectA.set(cx - radius, cy - radius, cx + radius, cy + radius);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_CYAN, 68));
        pStroke.setStrokeWidth(5.0f * s);
        c.drawArc(tmpRectA, -26f + cyanRot, 52f, false, pStroke);
        c.drawArc(tmpRectA, 154f + cyanRot, 52f, false, pStroke);
        pStroke.setColor(a(C_CYAN, 225));
        pStroke.setStrokeWidth(2.0f * s);
        c.drawArc(tmpRectA, -26f + cyanRot, 52f, false, pStroke);
        c.drawArc(tmpRectA, 154f + cyanRot, 52f, false, pStroke);

        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_GOLD, 225));
        pStroke.setStrokeWidth(1.8f * s);
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(-90f + i * 90f + goldRot);
            c.drawLine(cx + (float) Math.cos(rad) * ringR * 0.90f,
                    cy + (float) Math.sin(rad) * ringR * 0.90f,
                    cx + (float) Math.cos(rad) * ringR * 1.03f,
                    cy + (float) Math.sin(rad) * ringR * 1.03f, pStroke);
        }
        pStroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSpeakingEffect(Canvas c, float cx, float cy, float diskR, float s,
                                    float outputLevel) {
        float level = Math.max(0f, Math.min(1f, outputLevel));
        float response = (float) Math.sqrt(level);
        float scaleFactor = 1f + response * 0.045f;
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_GOLD, (int) (58 + response * 92)));
        pStroke.setStrokeWidth((5.0f + response * 4.0f) * s);
        c.drawCircle(cx, cy, diskR * scaleFactor * 1.04f, pStroke);

        float az = Float.isNaN(hub.azimuth) ? 0f : hub.azimuth;
        float rotation = glMainMode ? -az * 0.18f : -az;
        drawAnimatedTaiji(c, cx, cy, diskR, s, rotation, scaleFactor, response);
    }

    private void drawAnimatedTaiji(Canvas c, float cx, float cy, float r, float s,
                                   float rotation, float scaleFactor, float response) {
        c.save();
        c.scale(scaleFactor, scaleFactor, cx, cy);
        c.rotate(rotation, cx, cy);
        tmpRectA.set(cx - r, cy - r, cx + r, cy + r);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.rgb(217, 169, 44));
        c.drawCircle(cx, cy, r, pFill);

        tmpPath.reset();
        tmpPath.moveTo(cx, cy - r);
        tmpPath.arcTo(tmpRectA, -90f, 180f);
        tmpPath.close();
        pFill.setColor(Color.rgb(4, 4, 3));
        c.drawPath(tmpPath, pFill);
        c.drawCircle(cx, cy + r / 2f, r / 2f, pFill);
        pFill.setColor(Color.rgb(217, 169, 44));
        c.drawCircle(cx, cy - r / 2f, r / 2f, pFill);

        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(Color.rgb(255, 218, 76), (int) (205 + response * 50)));
        pStroke.setStrokeWidth((1.9f + response * 1.4f) * s);
        c.drawCircle(cx, cy, r, pStroke);
        pFill.setColor(Color.rgb(4, 4, 3));
        c.drawCircle(cx, cy - r / 2f, r * 0.115f, pFill);
        pFill.setColor(Color.rgb(245, 201, 62));
        c.drawCircle(cx, cy + r / 2f, r * 0.115f, pFill);
        c.restore();
    }

    private void drawVoiceErrorEffect(Canvas c, float cx, float cy, float ringR, float s,
                                      long startedAt, long now) {
        float progress = Math.max(0f, Math.min(1f, (now - startedAt) / 1400f));
        float eased = 1f - (1f - progress) * (1f - progress);
        float alpha = 1f - progress;
        float radius = ringR * (1.30f - eased * 0.22f);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_RED, (int) (alpha * 155)));
        pStroke.setStrokeWidth(7.0f * s);
        c.drawCircle(cx, cy, radius, pStroke);
        pStroke.setColor(a(Color.rgb(242, 70, 53), (int) (alpha * 245)));
        pStroke.setStrokeWidth(2.4f * s);
        c.drawCircle(cx, cy, radius, pStroke);

        float flash = 0.55f + 0.45f * Math.abs((float) Math.sin(progress * Math.PI * 4.0));
        pStroke.setColor(a(Color.rgb(246, 64, 48), (int) (alpha * flash * 255)));
        pStroke.setStrokeWidth(4.0f * s);
        for (int i = 0; i < 4; i++) {
            float rad = (float) Math.toRadians(-90f + i * 90f);
            c.drawLine(cx + (float) Math.cos(rad) * ringR * 0.88f,
                    cy + (float) Math.sin(rad) * ringR * 0.88f,
                    cx + (float) Math.cos(rad) * ringR * 1.10f,
                    cy + (float) Math.sin(rad) * ringR * 1.10f, pStroke);
        }
        pStroke.setStrokeCap(Paint.Cap.BUTT);
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

        tmpPath.reset();
        tmpPath.moveTo(cx - 8f * s, cy - r * 0.82f);
        tmpPath.lineTo(cx + 8f * s, cy - r * 0.82f);
        tmpPath.lineTo(cx, cy - r * 1.03f);
        tmpPath.close();
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(C_RED);
        c.drawPath(tmpPath, pFill);
        c.restore();
    }

    private void drawCenter(Canvas c, float cx, float cy, float r, float s, float az) {
        float ringR = r * 1.14f;
        float diskR = r * 0.93f;
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_GOLD_DARK, 116));
        pStroke.setStrokeWidth(1.6f * s);
        c.drawCircle(cx, cy, ringR, pStroke);
        pStroke.setColor(a(C_CYAN, 58));
        pStroke.setStrokeWidth(1.0f * s);
        c.drawCircle(cx, cy, ringR * 0.78f, pStroke);

        if (!Float.isNaN(az)) {
            float showAz = az % 360f;
            if (showAz < 0) showAz += 360f;
            tmpRectA.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            pStroke.setColor(a(C_CYAN, 110));
            pStroke.setStrokeWidth(2.0f * s);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(tmpRectA, -90f, showAz, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);

            float rad = (float) Math.toRadians(showAz - 90f);
            float px = cx + (float) Math.cos(rad) * ringR;
            float py = cy + (float) Math.sin(rad) * ringR;
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(C_GOLD);
            c.drawCircle(px, py, 3.0f * s, pFill);
        }

        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.rgb(10, 9, 7));
        c.drawCircle(cx, cy, diskR, pFill);
        pStroke.setColor(a(C_GOLD, 92));
        pStroke.setStrokeWidth(4.2f * s);
        c.drawCircle(cx, cy, diskR * 1.04f, pStroke);
        pStroke.setColor(C_GOLD);
        pStroke.setStrokeWidth(3.0f * s);
        c.drawCircle(cx, cy, diskR, pStroke);

        float taijiR = diskR;
        tmpRectA.set(cx - taijiR, cy - taijiR, cx + taijiR, cy + taijiR);
        float taijiAz = Float.isNaN(az) ? 0f : az;
        c.save();
        c.rotate(-taijiAz, cx, cy);
        pFill.setColor(Color.rgb(217, 169, 44));
        c.drawCircle(cx, cy, taijiR, pFill);

        tmpPath.reset();
        tmpPath.moveTo(cx, cy - taijiR);
        tmpPath.arcTo(tmpRectA, -90f, 180f);
        tmpPath.close();
        pFill.setColor(Color.rgb(4, 4, 3));
        c.drawPath(tmpPath, pFill);
        c.drawCircle(cx, cy + taijiR / 2f, taijiR / 2f, pFill);

        pFill.setColor(Color.rgb(217, 169, 44));
        c.drawCircle(cx, cy - taijiR / 2f, taijiR / 2f, pFill);

        pStroke.setColor(a(C_GOLD, 205));
        pStroke.setStrokeWidth(1.9f * s);
        c.drawCircle(cx, cy, taijiR, pStroke);

        pFill.setColor(Color.rgb(4, 4, 3));
        c.drawCircle(cx, cy - taijiR / 2f, taijiR * 0.115f, pFill);
        pFill.setColor(Color.rgb(245, 201, 62));
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
        tmpRectB.set(cx - textW / 2f - padX, y - 20f * s,
                cx + textW / 2f + padX, y + 17f * s);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(216, 12, 13, 13));
        c.drawRoundRect(tmpRectB, 18f * s, 18f * s, pFill);
        pStroke.setColor(a(C_CYAN, 104));
        pStroke.setStrokeWidth(1f * s);
        c.drawRoundRect(tmpRectB, 18f * s, 18f * s, pStroke);
        drawTextCentered(c, text, cx, y + 5f * s, pText);
    }

    private void drawDetail(Canvas c, float cx, float cy, float s) {
        if (detailPage == DETAIL_SATELLITES) drawSatelliteDetail(c, cx, cy, s);
        else if (detailPage == DETAIL_CALIBRATION) drawCalibrationDetail(c, cx, cy, s);
        else if (detailPage == DETAIL_ORACLE) drawOracleDetail(c, cx, cy, s);
        else if (detailPage == DETAIL_DIAGNOSTIC) drawDetailDiagnostic(c, cx, cy, s);
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
        String sats = satelliteState();
        String summary = pose + " · " + magnet + " · " + sats;

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
                "磁场", mag > 0 ? String.format(Locale.US, "%.0fuT", mag) : "--", s);
        drawDetailChip(c, cx + chipW / 2f + gap / 2f, row2, chipW, chipH,
                "卫星", satelliteValue(), s);
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
            tmpRectC.set(cx - barW / 2f, y - barH / 2f, cx - cut, y + barH / 2f);
            c.drawRoundRect(tmpRectC, 3f * s, 3f * s, pFill);
            tmpRectC.set(cx + cut, y - barH / 2f, cx + barW / 2f, y + barH / 2f);
            c.drawRoundRect(tmpRectC, 3f * s, 3f * s, pFill);
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

        tmpRectA.set(cx - r, cy - r, cx + r, cy + r);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(a(C_CYAN, 125));
        pStroke.setStrokeWidth(2.3f * s);
        c.drawArc(tmpRectA, -90f, az, false, pStroke);
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

    private void drawSatelliteDetail(Canvas c, float cx, float cy, float s) {
        float rMax = RoundScreen.R(getWidth(), getHeight()) - 4f;
        float panelR = rMax * 0.51f;
        float skyR = rMax * 0.245f;
        float skyCy = cy - 18f * s;
        SensorHub.SatelliteInfo[] sats = hub.satelliteInfos;
        int visible = Math.max(hub.visibleSats, sats == null ? 0 : sats.length);
        String sub = "可见 " + visible + " · 定位 " + hub.usedSats
                + " · 最强 " + (hub.maxSnr > 0f ? String.format(Locale.US, "%.0f", hub.maxSnr) : "--");

        drawDetailPanel(c, cx, cy, panelR, rMax, "坤 · 星图", sub, s);

        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < 3; i++) {
            pStroke.setColor(i == 0 ? a(C_GOLD, 150) : a(C_GOLD_DIM, 72));
            pStroke.setStrokeWidth((i == 0 ? 1.5f : 0.9f) * s);
            c.drawCircle(cx, skyCy, skyR * (3 - i) / 3f, pStroke);
        }
        pStroke.setColor(a(C_CYAN, 42));
        pStroke.setStrokeWidth(0.9f * s);
        c.drawLine(cx - skyR, skyCy, cx + skyR, skyCy, pStroke);
        c.drawLine(cx, skyCy - skyR, cx, skyCy + skyR, pStroke);

        if (sats == null || sats.length == 0) {
            pText.setStyle(Paint.Style.FILL);
            pText.setFakeBoldText(false);
            pText.setTextSize(14f * s);
            pText.setColor(C_DIM);
            drawTextCentered(c, "等待卫星数据", cx, skyCy + 4f * s, pText);
        } else {
            int max = Math.min(24, sats.length);
            for (int i = 0; i < max; i++) {
                SensorHub.SatelliteInfo si = sats[i];
                float elev = clampF(si.elevation, 0f, 90f);
                float rr = skyR * (1f - elev / 90f);
                float rad = (float) Math.toRadians(si.azimuth - 90f);
                float x = cx + (float) Math.cos(rad) * rr;
                float y = skyCy + (float) Math.sin(rad) * rr;
                float dot = clampF(3f * s + si.snr * 0.08f * s, 3f * s, 7f * s);
                pFill.setStyle(Paint.Style.FILL);
                pFill.setColor(si.usedInFix ? C_GOLD : a(C_CYAN, si.snr > 18f ? 185 : 105));
                c.drawCircle(x, y, dot, pFill);
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setColor(si.usedInFix ? a(C_GOLD, 210) : a(C_GOLD_DIM, 90));
                pStroke.setStrokeWidth(0.8f * s);
                c.drawCircle(x, y, dot + 1.5f * s, pStroke);
                pSmall.setStyle(Paint.Style.FILL);
                pSmall.setTextSize(8.5f * s);
                pSmall.setColor(C_TEXT);
                drawTextCentered(c, String.valueOf(si.prn), x, y + 3f * s, pSmall);
            }
        }

        drawSnrBars(c, cx, cy + rMax * 0.33f, rMax * 0.78f, 68f * s, sats, s);
    }

    private void drawSnrBars(Canvas c, float cx, float cy, float w, float h,
                             SensorHub.SatelliteInfo[] sats, float s) {
        if (sats == null || sats.length == 0) {
            drawDetailChip(c, cx, cy, Math.min(w, 260f * s), 42f * s, "信噪比", "暂无", s);
            return;
        }
        int count = Math.min(10, sats.length);
        boolean[] picked = new boolean[sats.length];
        float gap = 4f * s;
        float barW = (w - gap * (count - 1)) / count;
        float left = cx - w / 2f;
        float base = cy + h / 2f - 12f * s;
        pSmall.setTextSize(8.5f * s);
        for (int i = 0; i < count; i++) {
            int idx = bestSnrIndex(sats, picked);
            if (idx < 0) break;
            picked[idx] = true;
            SensorHub.SatelliteInfo si = sats[idx];
            float x = left + i * (barW + gap);
            float fillH = clampF(si.snr / 45f, 0.04f, 1f) * (h - 20f * s);
            tmpRectB.set(x, base - (h - 20f * s), x + barW, base);
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(Color.argb(80, 15, 12, 8));
            c.drawRoundRect(tmpRectB, 4f * s, 4f * s, pFill);
            tmpRectC.set(x, base - fillH, x + barW, base);
            pFill.setColor(si.usedInFix ? a(C_GOLD, 210) : a(C_CYAN, 145));
            c.drawRoundRect(tmpRectC, 4f * s, 4f * s, pFill);
            pSmall.setColor(C_DIM);
            drawTextCentered(c, String.valueOf(si.prn), x + barW / 2f, base + 12f * s, pSmall);
        }
    }

    private int bestSnrIndex(SensorHub.SatelliteInfo[] sats, boolean[] picked) {
        int best = -1;
        float bestSnr = -1f;
        for (int i = 0; i < sats.length; i++) {
            if (picked[i]) continue;
            float snr = sats[i] == null ? 0f : sats[i].snr;
            if (snr > bestSnr) {
                best = i;
                bestSnr = snr;
            }
        }
        return best;
    }

    private void drawCalibrationDetail(Canvas c, float cx, float cy, float s) {
        updateMagCalibrationSample();
        float rMax = RoundScreen.R(getWidth(), getHeight()) - 4f;
        float panelR = rMax * 0.51f;
        float spanX = magCalCollecting ? magCalMaxX - magCalMinX : 0f;
        float spanY = magCalCollecting ? magCalMaxY - magCalMinY : 0f;
        float spanZ = magCalCollecting ? magCalMaxZ - magCalMinZ : 0f;
        float score = calibrationScore(spanX, spanY);
        String quality = calibrationQuality(score, magCalSamples);
        String sub = quality + " · 样本 " + magCalSamples + " · " + hub.magCalQuality;

        drawDetailPanel(c, cx, cy, panelR, rMax, "坤 · 校准", sub, s);

        float gaugeR = rMax * 0.25f;
        float gaugeCy = cy - 12f * s;
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeCap(Paint.Cap.BUTT);
        pStroke.setColor(a(C_GOLD_DIM, 110));
        pStroke.setStrokeWidth(2f * s);
        c.drawCircle(cx, gaugeCy, gaugeR, pStroke);
        tmpRectA.set(cx - gaugeR, gaugeCy - gaugeR, cx + gaugeR, gaugeCy + gaugeR);
        pStroke.setStrokeCap(Paint.Cap.ROUND);
        pStroke.setColor(score >= 0.72f ? a(C_GOLD, 220) : a(C_CYAN, 145));
        pStroke.setStrokeWidth(6f * s);
        c.drawArc(tmpRectA, -90f, 360f * clampF(score, 0f, 1f), false, pStroke);
        drawKunTexture(c, cx, gaugeCy, gaugeR * 0.62f, s);

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(true);
        pText.setTextSize(26f * s);
        pText.setColor(C_GOLD);
        drawTextCentered(c, String.format(Locale.US, "%.0f%%", clampF(score, 0f, 1f) * 100f),
                cx, gaugeCy + 8f * s, pText);
        pText.setFakeBoldText(false);
        pText.setTextSize(12f * s);
        pText.setColor(C_DIM);
        drawTextCentered(c, "水平旋转两到三圈", cx, gaugeCy + gaugeR * 0.58f, pText);

        float chipW = 138f * s;
        float chipH = 38f * s;
        float gap = 10f * s;
        float rowY = cy + rMax * 0.31f;
        drawDetailChip(c, cx - chipW - gap, rowY, chipW, chipH, "X/Y",
                String.format(Locale.US, "%.0f / %.0f", spanX, spanY), s);
        drawDetailChip(c, cx, rowY, chipW, chipH, "Z", String.format(Locale.US, "%.0f", spanZ), s);
        drawDetailChip(c, cx + chipW + gap, rowY, chipW, chipH, "偏移",
                String.format(Locale.US, "%.0f/%.0f/%.0f", hub.magOffsetX, hub.magOffsetY, hub.magOffsetZ), s);

        float btnW = 146f * s;
        float btnH = 34f * s;
        calibrationResetHit.set(cx - btnW / 2f, cy + rMax * 0.43f - btnH / 2f,
                cx + btnW / 2f, cy + rMax * 0.43f + btnH / 2f);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(118, 54, 24, 18));
        c.drawRoundRect(calibrationResetHit, 14f * s, 14f * s, pFill);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setColor(a(C_RED, 135));
        pStroke.setStrokeWidth(1f * s);
        c.drawRoundRect(calibrationResetHit, 14f * s, 14f * s, pStroke);
        pText.setStyle(Paint.Style.FILL);
        pText.setTextSize(13f * s);
        pText.setColor(C_TEXT);
        drawTextCentered(c, "重置校准", cx, calibrationResetHit.centerY() + 5f * s, pText);

        if (!magCalSaved && score >= 0.72f && magCalSamples >= 36) {
            saveMagCalibration(score);
        }
    }

    private void drawOracleDetail(Canvas c, float cx, float cy, float s) {
        updateOracleShake();
        float rMax = RoundScreen.R(getWidth(), getHeight()) - 4f;
        float panelR = rMax * 0.51f;
        String sub;
        if (oracleCollecting) {
            float progress = clampF((System.currentTimeMillis() - oracleCollectStartMs)
                    / (float) oracleCollectDurationMs(), 0f, 1f);
            sub = "摇动中 " + String.format(Locale.US, "%.0f%%", progress * 100f);
        } else if (oracleReading == null) {
            sub = "待起卦 · " + oracleShakeLabel() + "摇动";
        } else {
            sub = "第" + oracleReading.primary.number + "卦 · "
                    + (oracleReading.hasMovingLines() ? "动爻 " + oracleReading.movingLabel() : "无动爻");
        }
        drawDetailPanel(c, cx, cy, panelR, rMax, "坤 · 占卜", sub, s);

        float hexCy = cy - rMax * 0.065f;
        float hexW = 138f * s;
        float lineGap = 19f * s;
        drawOracleHexagram(c, cx, hexCy, hexW, lineGap, oracleReading == null ? null : oracleReading.lines, s);

        if (oracleCollecting) {
            float rr = 118f * s;
            float progress = clampF((System.currentTimeMillis() - oracleCollectStartMs)
                    / (float) oracleCollectDurationMs(), 0f, 1f);
            tmpRectA.set(cx - rr, hexCy - rr, cx + rr, hexCy + rr);
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            pStroke.setColor(a(C_CYAN, 145));
            pStroke.setStrokeWidth(3f * s);
            c.drawArc(tmpRectA, -90f, 360f * progress, false, pStroke);
        }

        float chipW = 132f * s;
        float chipH = 38f * s;
        float gap = 9f * s;
        float rowY = cy + rMax * 0.215f;
        drawDetailChip(c, cx - chipW - gap, rowY, chipW, chipH, "本卦",
                oracleReading == null ? "--" : oracleReading.primary.name, s);
        drawDetailChip(c, cx, rowY, chipW, chipH, "动爻",
                oracleReading == null ? "--" : oracleReading.movingLabel(), s);
        drawDetailChip(c, cx + chipW + gap, rowY, chipW, chipH, "变卦",
                oracleReading == null ? "--" : (oracleReading.hasMovingLines() ? oracleReading.changed.name : "不变"), s);

        pText.setStyle(Paint.Style.FILL);
        pText.setFakeBoldText(false);
        pText.setTextSize(13f * s);
        pText.setColor(C_TEXT);
        float textW = rMax * 0.92f;
        float textY = cy + rMax * 0.335f;
        if (oracleReading == null) {
            String hint = System.currentTimeMillis() < oracleHintUntil && !oracleHint.isEmpty()
                    ? oracleHint : "罗盘静候一问，摇动后离线出卦。";
            drawTextCentered(c, hint, cx, textY, pText);
        } else {
            String local = oracleReading.primary.summary + " " + oracleReading.primary.advice;
            drawWrappedCentered(c, local, cx, textY, textW, 18f * s, 2, pText);
            String ai = oracleReading.aiText == null ? "" : oracleReading.aiText.trim();
            String aiStatus = oracleReading.aiStatus == null ? "" : oracleReading.aiStatus.trim();
            pText.setTextSize(12.3f * s);
            pText.setColor(ai.isEmpty() ? C_DIM : a(C_CYAN, 220));
            drawWrappedCentered(c, ai.isEmpty() ? aiStatus : ai, cx,
                    cy + rMax * 0.435f, textW, 16f * s, 2, pText);
        }
        if (oracleCollecting) postInvalidateDelayed(80);
    }

    private void drawOracleHexagram(Canvas c, float cx, float cy, float w, float gap,
                                    int[] lines, float s) {
        float h = 7f * s;
        float split = 18f * s;
        for (int pos = 0; pos < 6; pos++) {
            int idx = 5 - pos;
            int v = lines == null ? 8 : lines[idx];
            boolean yang = v == 7 || v == 9;
            boolean moving = v == 6 || v == 9;
            float y = cy - gap * 2.5f + pos * gap;
            int col = lines == null ? a(C_GOLD_DIM, 58) : (moving ? a(C_CYAN, 215) : a(C_GOLD, 220));
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(col);
            if (yang) {
                tmpRectB.set(cx - w / 2f, y - h / 2f, cx + w / 2f, y + h / 2f);
                c.drawRoundRect(tmpRectB, 3f * s, 3f * s, pFill);
            } else {
                tmpRectB.set(cx - w / 2f, y - h / 2f, cx - split / 2f, y + h / 2f);
                c.drawRoundRect(tmpRectB, 3f * s, 3f * s, pFill);
                tmpRectC.set(cx + split / 2f, y - h / 2f, cx + w / 2f, y + h / 2f);
                c.drawRoundRect(tmpRectC, 3f * s, 3f * s, pFill);
            }
            if (moving) {
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setColor(a(C_CYAN, 150));
                pStroke.setStrokeWidth(1f * s);
                c.drawCircle(cx + w / 2f + 14f * s, y, 4f * s, pStroke);
            }
        }
        if (lines == null) {
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(38f * s);
            pText.setFakeBoldText(true);
            pText.setColor(a(C_GOLD, 84));
            drawTextCentered(c, "坤", cx, cy + 12f * s, pText);
            pText.setFakeBoldText(false);
        }
    }

    private void updateOracleShake() {
        if (!detailMode || detailPage != DETAIL_ORACLE) return;
        if (hub.lastAccelMs <= 0 || hub.lastAccelMs == oracleLastAccelMs) return;
        oracleLastAccelMs = hub.lastAccelMs;
        long now = System.currentTimeMillis();
        float energy = linearAccel();
        if (!oracleCollecting) {
            if (energy >= oracleStartThreshold()) beginOracleCollecting(now, energy);
            return;
        }
        mixOracleEntropy(now, energy);
        oraclePeakEnergy = Math.max(oraclePeakEnergy, energy);
        if (energy >= oraclePeakThreshold() && now - oracleLastPeakMs >= 120) {
            oraclePeakCount++;
            oracleLastPeakMs = now;
        }
        if (now - oracleCollectStartMs >= oracleCollectDurationMs()) {
            if (oraclePeakCount >= oracleMinPeaks()) finishOracleReading(now);
            else cancelWeakOracle(now);
        }
        else postInvalidateDelayed(80);
    }

    private void beginOracleCollecting(long now, float energy) {
        oracleCollecting = true;
        oracleCollectStartMs = now;
        oracleLastPeakMs = now;
        oraclePeakCount = 1;
        oraclePeakEnergy = energy;
        oracleEntropy = now ^ System.nanoTime();
        oracleReading = null;
        oracleHint = "";
        oracleHintUntil = 0;
        mixOracleEntropy(now, energy);
        postInvalidateDelayed(80);
    }

    private void finishOracleReading(long now) {
        oracleCollecting = false;
        long seed = oracleEntropy ^ (now << 21) ^ ((long) Float.floatToIntBits(oraclePeakEnergy) << 7)
                ^ (oraclePeakCount * 0x9E3779B97F4A7C15L);
        final OracleReading reading = OracleBook.cast(seed, now);
        reading.aiStatus = "AI 解读中";
        oracleReading = reading;
        post(new Runnable() {
            @Override public void run() {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (actions != null && isOracleDetailActive()) actions.onOracleReading(reading);
            }
        });
        postInvalidate();
    }

    private void cancelWeakOracle(long now) {
        oracleCollecting = false;
        oracleHint = "摇动不足 · 需要连续 " + oracleMinPeaks() + " 次";
        oracleHintUntil = now + 1400;
        postInvalidate();
    }

    private void stopOracleCollecting() {
        oracleCollecting = false;
        oracleCollectStartMs = 0;
        oracleLastPeakMs = 0;
        oraclePeakCount = 0;
        oraclePeakEnergy = 0f;
    }

    private int oracleShakeForce() {
        if (Prefs.contains(getContext(), Prefs.K_ORACLE_SHAKE_FORCE)) {
            return Math.max(0, Math.min(100, Prefs.getI(getContext(),
                    Prefs.K_ORACLE_SHAKE_FORCE, Prefs.DEFAULT_ORACLE_SHAKE_FORCE)));
        }
        int old = Math.max(1, Math.min(5, Prefs.getI(getContext(),
                Prefs.K_ORACLE_SHAKE_LEVEL, Prefs.DEFAULT_ORACLE_SHAKE_LEVEL)));
        return Math.max(0, Math.min(100, old * 18 - 2));
    }

    private float oracleForceT() {
        return oracleShakeForce() / 100f;
    }

    private float oracleStartThreshold() {
        float t = oracleForceT();
        return 1.35f + 3.05f * (float) Math.pow(t, 1.15f);
    }

    private float oraclePeakThreshold() {
        float t = oracleForceT();
        return 0.80f + 2.05f * (float) Math.pow(t, 1.08f);
    }

    private int oracleMinPeaks() {
        return 2 + Math.round(oracleShakeForce() / 25f);
    }

    private long oracleCollectDurationMs() {
        return 1000L + Math.round(900f * oracleForceT());
    }

    private String oracleShakeLabel() {
        int force = oracleShakeForce();
        if (force < 18) return "轻";
        if (force < 38) return "稍轻";
        if (force < 62) return "正常";
        if (force < 82) return "较重";
        return "用力";
    }

    private void mixOracleEntropy(long now, float energy) {
        long v = Float.floatToIntBits(hub.ax);
        v = (v << 11) ^ Float.floatToIntBits(hub.ay);
        v = (v << 13) ^ Float.floatToIntBits(hub.az);
        v ^= ((long) Float.floatToIntBits(hub.mx) << 17)
                ^ ((long) Float.floatToIntBits(hub.my) << 31)
                ^ ((long) Float.floatToIntBits(hub.mz) << 43)
                ^ now ^ Float.floatToIntBits(energy);
        oracleEntropy = Long.rotateLeft(oracleEntropy ^ v, 9) + 0x9E3779B97F4A7C15L;
    }

    private void drawWrappedCentered(Canvas c, String text, float cx, float baseline,
                                     float maxW, float lineH, int maxLines, Paint paint) {
        if (text == null || text.trim().isEmpty() || maxLines <= 0) return;
        String src = text.replace('\n', ' ').replace('\r', ' ').trim();
        int line = 0;
        while (!src.isEmpty() && line < maxLines) {
            int n = src.length();
            while (n > 1 && paint.measureText(src.substring(0, n)) > maxW) n--;
            String shown = src.substring(0, n).trim();
            src = src.substring(n).trim();
            if (line == maxLines - 1 && !src.isEmpty()) shown = ellipsize(shown + src, paint, maxW);
            drawTextCentered(c, shown, cx, baseline + line * lineH, paint);
            line++;
        }
    }

    private void drawDetailPanel(Canvas c, float cx, float cy, float panelR, float rMax,
                                 String title, String sub, float s) {
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
        drawTextCentered(c, title, cx, cy - rMax * 0.36f, pText);
        pText.setFakeBoldText(false);
        pText.setTextSize(13f * s);
        pText.setColor(C_DIM);
        drawTextCentered(c, ellipsize(sub, pText, rMax * 1.05f), cx, cy - rMax * 0.30f, pText);
    }

    private void drawDetailChip(Canvas c, float centerX, float centerY, float w, float h,
                                String label, String value, float s) {
        tmpRectB.set(centerX - w / 2f, centerY - h / 2f,
                centerX + w / 2f, centerY + h / 2f);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(122, 17, 14, 10));
        c.drawRoundRect(tmpRectB, 13f * s, 13f * s, pFill);
        pStroke.setColor(a(C_GOLD_DIM, 88));
        pStroke.setStrokeWidth(0.9f * s);
        c.drawRoundRect(tmpRectB, 13f * s, 13f * s, pStroke);

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
        float rowGap = 25f * s;
        float y = cy - 166f * s;
        String[][] cells = {
                {"方位", Float.isNaN(hub.azimuth) ? "--" : String.format(Locale.US, "%.0f°", hub.azimuth)},
                {"姿态", String.format(Locale.US, "俯 %.0f° / 横 %.0f°", hub.pitch, hub.roll)},
                {"电量", hub.battery >= 0 ? hub.battery + "%" : "--"},
                {"动势", motionValue()},
                {"角速", String.format(Locale.US, "%.0f°/s", angularSpeedDeg())},
                {"加速", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.ax, hub.ay, hub.az)},
                {"陀螺", String.format(Locale.US, "%.2f / %.2f / %.2f", hub.gx, hub.gy, hub.gz)},
                {"磁力", String.format(Locale.US, "%.1f / %.1f / %.1f", hub.mx, hub.my, hub.mz)},
                {"卫星", hub.gpsStatus + " " + hub.usedSats + "/" + hub.visibleSats},
                {"光线", sensorReading(hub.hasLightSensor, hub.lastLightMs, hub.light, "lx", true)},
                {"近距", sensorReading(hub.hasProximitySensor, hub.lastProximityMs, hub.proximity, "cm", true)},
                {"气压", sensorReading(hub.hasPressureSensor, hub.lastPressureMs, hub.pressure, "hPa", false)},
                {"校准", hub.magCalQuality},
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
        tmpRectB.set(cx - rowW / 2f, baseline - rowH + 7f * s,
                cx + rowW / 2f, baseline + 7f * s);
        pFill.setStyle(Paint.Style.FILL);
        pFill.setColor(Color.argb(86, 12, 12, 12));
        c.drawRoundRect(tmpRectB, 8f * s, 8f * s, pFill);
        pStroke.setColor(a(C_GOLD_DIM, 72));
        pStroke.setStrokeWidth(0.8f * s);
        c.drawRoundRect(tmpRectB, 8f * s, 8f * s, pStroke);

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
        if (Prefs.locSourceOff(getContext())) return "定位关闭";
        if (hub.gpsEnabled && !Double.isNaN(hub.lat)) return "GPS";
        if (!Double.isNaN(hub.netLat)) {
            String src = hub.netSrc == null || hub.netSrc.trim().isEmpty() ? "网络" : hub.netSrc.trim();
            if (src.startsWith("IP")) return "粗定位";
            return src.length() > 5 ? "网络" : src;
        }
        if (Prefs.locSourceWifiIp(getContext())) return "粗定位中";
        return "定位未定";
    }

    private String locationValue() {
        if (Prefs.locSourceOff(getContext())) return "已关闭";
        if (hub.gpsEnabled && !Double.isNaN(hub.lat)) return hub.sats > 0 ? ("GPS " + hub.sats + "星") : "GPS";
        if (!Double.isNaN(hub.netLat)) {
            String src = hub.netSrc == null || hub.netSrc.trim().isEmpty() ? "网络" : hub.netSrc.trim();
            float acc = hub.netAcc;
            String accText = acc >= 1000f
                    ? String.format(Locale.US, "±%.1fkm", acc / 1000f)
                    : (acc > 0 ? String.format(Locale.US, "±%.0fm", acc) : "");
            return (src.startsWith("IP") ? "粗略" : src) + (accText.isEmpty() ? "" : " " + accText);
        }
        if (Prefs.locSourceWifiIp(getContext())) return "等待网络";
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

    private String satelliteState() {
        if (!hub.gpsEnabled) return "卫星关闭";
        if (hub.visibleSats <= 0) return hub.gpsStatus == null ? "搜索卫星" : hub.gpsStatus;
        if (hub.usedSats > 0) return "卫星可用";
        if (hub.maxSnr >= 25f) return "有星未定";
        return "信号偏弱";
    }

    private String satelliteValue() {
        if (!hub.gpsEnabled) return "已关闭";
        if (hub.visibleSats <= 0) return "0 星";
        String snr = hub.maxSnr > 0f ? String.format(Locale.US, " %.0f", hub.maxSnr) : "";
        return hub.usedSats + "/" + hub.visibleSats + " 星" + snr;
    }

    private String sensorReading(boolean exists, long lastMs, float value, String unit, boolean zeroUntrusted) {
        if (!exists) return "无硬件";
        if (lastMs <= 0) return "无事件";
        if (Float.isNaN(value)) return "无数据";
        String v = Math.abs(value) >= 10f
                ? String.format(Locale.US, "%.0f%s", value, unit)
                : String.format(Locale.US, "%.1f%s", value, unit);
        if (zeroUntrusted && Math.abs(value) < 0.001f) return v + " 不可信";
        return v;
    }

    private void beginMagCalibration() {
        magCalCollecting = true;
        magCalSaved = false;
        magCalSamples = 0;
        magCalLastSampleMs = 0;
        magCalMinX = magCalMinY = magCalMinZ = Float.POSITIVE_INFINITY;
        magCalMaxX = magCalMaxY = magCalMaxZ = Float.NEGATIVE_INFINITY;
    }

    private void updateMagCalibrationSample() {
        if (!magCalCollecting || hub.lastMagMs <= 0 || hub.lastMagMs == magCalLastSampleMs) return;
        magCalLastSampleMs = hub.lastMagMs;
        float x = hub.rawMx;
        float y = hub.rawMy;
        float z = hub.rawMz;
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) return;
        magCalMinX = Math.min(magCalMinX, x);
        magCalMinY = Math.min(magCalMinY, y);
        magCalMinZ = Math.min(magCalMinZ, z);
        magCalMaxX = Math.max(magCalMaxX, x);
        magCalMaxY = Math.max(magCalMaxY, y);
        magCalMaxZ = Math.max(magCalMaxZ, z);
        magCalSamples++;
    }

    private float calibrationScore(float spanX, float spanY) {
        if (magCalSamples < 2) return 0f;
        return (clampF(spanX / 70f, 0f, 1f) + clampF(spanY / 70f, 0f, 1f)) / 2f;
    }

    private String calibrationQuality(float score, int samples) {
        if (!hub.hasMagSensor) return "无磁力计";
        if (samples < 12) return "采集中";
        if (score >= 0.72f) return "覆盖良好";
        if (score >= 0.38f) return "继续旋转";
        return "覆盖不足";
    }

    private void saveMagCalibration(float score) {
        float ox = (magCalMinX + magCalMaxX) / 2f;
        float oy = (magCalMinY + magCalMaxY) / 2f;
        float oz = (magCalMinZ + magCalMaxZ) / 2f;
        String quality = score >= 0.86f ? "良好" : "可用";
        hub.setMagCalibration(ox, oy, oz, quality);
        magCalSaved = true;
        postInvalidate();
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
                    if (moved > slop || dist > r * 0.30f) {
                        dragging = true;
                        wakeEffectAnimation();
                    }
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
                    wakeEffectAnimation();
                    if (idx >= 0) actions.onSector(idx);
                    return true;
                }
                if (detailMode && detailPage == DETAIL_CALIBRATION
                        && calibrationResetHit.contains(ev.getX(), ev.getY())) {
                    hub.resetMagCalibration();
                    beginMagCalibration();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    postInvalidate();
                    return true;
                }
                // 详情页中央用于循环硬件自检页，不触发语音。
                if (detailMode && dist < r * 0.30f) {
                    enterDetailPage((detailPage + 1) % DETAIL_PAGE_COUNT);
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
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                previewIdx = -1;
                wakeEffectAnimation();
                return true;
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

        tmpRectA.set(cx - diskR * 1.2f, cy - diskR * 1.2f, cx + diskR * 1.2f, cy + diskR * 1.2f);
        pStroke.setColor(a(C_CYAN, 125));
        pStroke.setStrokeWidth(1.5f * s);
        c.drawArc(tmpRectA, -48f, 96f, false, pStroke);
        c.drawArc(tmpRectA, 132f, 96f, false, pStroke);

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
                tmpRectB.set(cx - barW / 2f, y, cx + barW / 2f, y + barH);
                c.drawRoundRect(tmpRectB, 4f * s, 4f * s, pFill);
            } else {
                float halfW = (barW - yinGap) / 2f;
                tmpRectB.set(cx - barW / 2f, y, cx - barW / 2f + halfW, y + barH);
                c.drawRoundRect(tmpRectB, 4f * s, 4f * s, pFill);
                tmpRectB.set(cx + barW / 2f - halfW, y, cx + barW / 2f, y + barH);
                c.drawRoundRect(tmpRectB, 4f * s, 4f * s, pFill);
            }
        }

        pSmall.setStyle(Paint.Style.FILL);
        pSmall.setTextSize(27f * s);
        pSmall.setColor(C_TEXT);
        drawTextCentered(c, SECTOR_LABELS[previewIdx], cx, topY + totalH + 35f * s, pSmall);
    }
}
