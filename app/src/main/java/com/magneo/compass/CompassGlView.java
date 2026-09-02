package com.magneo.compass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import com.magneo.compass.ui.RoundScreen;
import com.magneo.compass.ui.Ui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Calendar;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/** OpenGL ES 2.0 主罗盘渲染层。详情/拖拽仍由 CompassView 的 Canvas 覆盖层负责。 */
public class CompassGlView extends GLSurfaceView {
    private final CompassRenderer renderer;
    private volatile boolean ritualFrameScheduled;
    private final Runnable ritualFrame = new Runnable() {
        @Override public void run() {
            ritualFrameScheduled = false;
            requestRenderSafe();
        }
    };

    public CompassGlView(Context context, SensorHub hub, Runnable fallback) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(new MsaaConfigChooser());
        getHolder().setFormat(PixelFormat.RGBA_8888);
        try { setPreserveEGLContextOnPause(true); } catch (Throwable ignored) {}
        renderer = new CompassRenderer(context.getApplicationContext(), hub, fallback,
                new Runnable() {
                    @Override public void run() { requestRitualFrame(); }
                });
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void requestRenderSafe() {
        try { requestRender(); } catch (Throwable ignored) {}
    }

    private void requestRitualFrame() {
        if (ritualFrameScheduled) return;
        ritualFrameScheduled = true;
        postDelayed(ritualFrame, 83L);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(ritualFrame);
        ritualFrameScheduled = false;
        super.onDetachedFromWindow();
    }

    private static final class MsaaConfigChooser implements EGLConfigChooser {
        private static final int EGL_OPENGL_ES2_BIT = 4;
        private static final int EGL_RENDERABLE_TYPE = 0x3040;

        @Override public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            EGLConfig c = choose(egl, display, true);
            if (c == null) c = choose(egl, display, false);
            if (c == null) throw new IllegalArgumentException("No EGL config for compass GL");
            return c;
        }

        private EGLConfig choose(EGL10 egl, EGLDisplay display, boolean msaa) {
            int[] attrs = msaa
                    ? new int[] {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SAMPLE_BUFFERS, 1,
                    EGL10.EGL_SAMPLES, 4,
                    EGL10.EGL_NONE
            }
                    : new int[] {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_NONE
            };
            int[] num = new int[1];
            if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null;
            EGLConfig[] configs = new EGLConfig[num[0]];
            if (!egl.eglChooseConfig(display, attrs, configs, configs.length, num)) return null;
            return configs.length > 0 ? configs[0] : null;
        }
    }

    private static final class CompassRenderer implements Renderer {
        private static final String[] SECTOR_NAMES = {"乾", "坎", "艮", "震", "巽", "离", "兑", "坤"};
        private static final int[][] TRIGRAMS = {
                {1, 1, 1}, {0, 1, 0}, {0, 0, 1}, {1, 0, 0},
                {0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {0, 0, 0}
        };
        private static final String[] TIAN_GAN = {"甲","乙","丙","丁","戊","己","庚","辛","壬","癸"};
        private static final String[] DI_ZHI = {"子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥"};
        private static final String[] DIRS = {"北", "东", "南", "西"};
        private static final int FULL_RING_STEPS = 2048;
        private static final int HALF_RING_STEPS = 1024;
        private static final int DOT_STEPS = 160;

        private static final int C_GOLD = Ui.COLOR_GOLD;
        private static final int C_GOLD_DARK = Ui.COLOR_GOLD_DARK;
        private static final int C_GOLD_DIM = Ui.COLOR_GOLD_DIM;
        private static final int C_RED = Ui.COLOR_RED;
        private static final int C_TEXT = Ui.COLOR_TEXT;
        private static final int C_DIM = Ui.COLOR_TEXT_DIM;
        private static final int C_MUTED = Ui.COLOR_TEXT_MUTED;
        private static final int C_CYAN = Ui.COLOR_AETHER;
        private static final int C_BG_DEEP = Ui.COLOR_BG_DEEP;

        private final Context context;
        private final SensorHub hub;
        private final Runnable fallback;
        private final Runnable frameRequester;
        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rectA = new RectF();
        private final RectF rectB = new RectF();
        private final RectF rectC = new RectF();
        private final float[] verts = new float[8192];
        private final float[] texVerts = new float[8];
        private final float[] texUvs = new float[8];
        private final FloatBuffer vertBuf = ByteBuffer.allocateDirect(8192 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer texVertBuf = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer texUvBuf = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        private int w;
        private int h;
        private float cx;
        private float cy;
        private float rMax;
        private float scale;
        private int colorProgram;
        private int colorPos;
        private int colorSize;
        private int colorUniform;
        private int texProgram;
        private int texPos;
        private int texCoord;
        private int texSize;
        private int texSampler;
        private int staticTexture;
        private int staticW = -1;
        private int staticH = -1;
        private int staticHourGan = -1;
        private int staticHourZhi = -1;
        private final int[] dirTextures = new int[4];
        private final int[] dirTextureSizes = new int[4];
        private boolean surfaceReady;
        private long timeRitualSlot = Long.MIN_VALUE;
        private long timeRitualStartedAtMs;

        CompassRenderer(Context context, SensorHub hub, Runnable fallback, Runnable frameRequester) {
            this.context = context;
            this.hub = hub;
            this.fallback = fallback;
            this.frameRequester = frameRequester;
            pStroke.setStyle(Paint.Style.STROKE);
            pText.setSubpixelText(true);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            try {
                colorProgram = makeProgram(
                        "attribute vec2 aPos;uniform vec2 uSize;"
                                + "void main(){vec2 p=vec2(aPos.x/uSize.x*2.0-1.0,1.0-aPos.y/uSize.y*2.0);"
                                + "gl_Position=vec4(p,0.0,1.0);}",
                        "precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}");
                texProgram = makeProgram(
                        "attribute vec2 aPos;attribute vec2 aTex;uniform vec2 uSize;varying vec2 vTex;"
                                + "void main(){vec2 p=vec2(aPos.x/uSize.x*2.0-1.0,1.0-aPos.y/uSize.y*2.0);"
                                + "gl_Position=vec4(p,0.0,1.0);vTex=aTex;}",
                        "precision mediump float;uniform sampler2D uTex;varying vec2 vTex;"
                                + "void main(){gl_FragColor=texture2D(uTex,vTex);}");
                colorPos = GLES20.glGetAttribLocation(colorProgram, "aPos");
                colorSize = GLES20.glGetUniformLocation(colorProgram, "uSize");
                colorUniform = GLES20.glGetUniformLocation(colorProgram, "uColor");
                texPos = GLES20.glGetAttribLocation(texProgram, "aPos");
                texCoord = GLES20.glGetAttribLocation(texProgram, "aTex");
                texSize = GLES20.glGetUniformLocation(texProgram, "uSize");
                texSampler = GLES20.glGetUniformLocation(texProgram, "uTex");
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                surfaceReady = true;
            } catch (Throwable t) {
                fail();
            }
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            w = Math.max(1, width);
            h = Math.max(1, height);
            cx = RoundScreen.cx(w);
            cy = RoundScreen.cy(h);
            scale = RoundScreen.scale800(w, h);
            rMax = RoundScreen.R(w, h) - 4f;
            GLES20.glViewport(0, 0, w, h);
            staticW = -1;
            deleteDirTextures();
        }

        @Override public void onDrawFrame(GL10 gl) {
            if (!surfaceReady || w <= 0 || h <= 0) return;
            try {
                GLES20.glClearColor(Color.red(C_BG_DEEP) / 255f, Color.green(C_BG_DEEP) / 255f,
                        Color.blue(C_BG_DEEP) / 255f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                Calendar now = Calendar.getInstance();
                int hourZhi = currentHourZhi(now);
                int hourGan = currentHourGan(now, hourZhi);
                updateTimeRitual(now);
                ensureStaticTexture(hourZhi, hourGan);
                drawTexture(staticTexture, 0f, 0f, w, h);
                drawNightDim(TimeRitual.isNight(now));
                float az = Float.isNaN(hub.azimuth) ? 0f : hub.azimuth;
                drawBatteryRing();
                drawCompassRing(az);
                drawClock(now);
                drawTimeRitualPulse(hourZhi, hourGan, TimeRitual.isNight(now));
                drawCenter(az);
                if (isTimeRitualActive() && frameRequester != null) frameRequester.run();
            } catch (Throwable t) {
                fail();
            }
        }

        private void ensureStaticTexture(int hourZhi, int hourGan) {
            if (staticTexture != 0 && staticW == w && staticH == h
                    && staticHourZhi == hourZhi && staticHourGan == hourGan) return;
            Bitmap bm = null;
            try {
                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                c.drawColor(C_BG_DEEP);
                drawStaticCanvas(c, hourZhi, hourGan);
                if (staticTexture == 0) {
                    int[] ids = new int[1];
                    GLES20.glGenTextures(1, ids, 0);
                    staticTexture = ids[0];
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticTexture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                staticW = w;
                staticH = h;
                staticHourZhi = hourZhi;
                staticHourGan = hourGan;
            } finally {
                if (bm != null) bm.recycle();
            }
        }

        private void drawStaticCanvas(Canvas c, int hourZhi, int hourGan) {
            drawMysticField(c);
            drawSectors(c);
            drawTianganDizhi(c, hourZhi, hourGan);
        }

        private void drawMysticField(Canvas c) {
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(C_BG_DEEP);
            c.drawCircle(cx, cy, rMax, pFill);
            for (int i = 14; i >= 0; i--) {
                float k = i / 14f;
                float rr = rMax * (0.18f + 0.82f * k);
                pFill.setColor(blend(Color.rgb(28, 15, 8), Color.rgb(24, 24, 18), 1f - k));
                c.drawCircle(cx, cy, rr, pFill);
            }
            pFill.setColor(Color.argb(72, 70, 42, 18));
            c.drawCircle(cx, cy, rMax * 0.92f, pFill);
            pFill.setColor(Color.argb(58, 10, 36, 38));
            c.drawCircle(cx, cy, rMax * 0.56f, pFill);

            float[] rings = {0.23f, 0.36f, 0.50f, 0.57f, 0.61f, 0.72f, 0.86f};
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < rings.length; i++) {
                boolean important = i >= 3;
                pStroke.setColor(i % 2 == 0 ? a(C_GOLD, important ? 96 : 62)
                        : a(C_CYAN, important ? 62 : 42));
                pStroke.setStrokeWidth((important ? 1.8f : 1.15f) * scale);
                c.drawCircle(cx, cy, rMax * rings[i], pStroke);
            }
            pStroke.setColor(C_GOLD);
            pStroke.setStrokeWidth(2.6f * scale);
            c.drawCircle(cx, cy, rMax, pStroke);
        }

        private void drawSectors(Canvas c) {
            float outer = rMax;
            float inner = rMax * 0.865f;
            rectA.set(cx - outer, cy - outer, cx + outer, cy + outer);
            rectB.set(cx - inner, cy - inner, cx + inner, cy + inner);
            for (int i = 0; i < 8; i++) {
                float start = i * 45f - 90f;
                path.reset();
                path.arcTo(rectA, start, 45f);
                path.arcTo(rectB, start + 45f, -45f);
                path.close();
                pFill.setStyle(Paint.Style.FILL);
                pFill.setColor(i % 2 == 0 ? Color.rgb(39, 25, 13) : Color.rgb(30, 22, 15));
                c.drawPath(path, pFill);
                drawSectorTexture(c, outer, inner, start, i);
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setColor(a(C_GOLD_DARK, 235));
                pStroke.setStrokeWidth(1.9f * scale);
                c.drawPath(path, pStroke);

                float midDeg = start + 22.5f;
                float mid = (float) Math.toRadians(midDeg);
                float mr = (outer + inner) / 2f;
                float mx = cx + (float) Math.cos(mid) * mr;
                float my = cy + (float) Math.sin(mid) * mr;
                c.save();
                c.rotate(midDeg + 90f, mx, my);
                pText.setStyle(Paint.Style.FILL);
                pText.setTextSize(ringTextSize(28f, outer - inner, 0.54f, 22f));
                pText.setFakeBoldText(true);
                pText.setColor(C_GOLD);
                drawTextCenteredOnPoint(c, SECTOR_NAMES[i], mx, my, pText);
                pText.setFakeBoldText(false);
                c.restore();
            }
        }

        private void drawSectorTexture(Canvas c, float outer, float inner, float start, int idx) {
            float gap = outer - inner;
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeCap(Paint.Cap.BUTT);
            pStroke.setStrokeWidth(1.05f * scale);
            pStroke.setColor(a(C_GOLD, 52));
            float texR1 = inner + gap * 0.32f;
            float texR2 = inner + gap * 0.68f;
            rectC.set(cx - texR1, cy - texR1, cx + texR1, cy + texR1);
            c.drawArc(rectC, start + 6f, 33f, false, pStroke);
            rectC.set(cx - texR2, cy - texR2, cx + texR2, cy + texR2);
            c.drawArc(rectC, start + 6f, 33f, false, pStroke);

            float midDeg = start + 22.5f;
            float mid = (float) Math.toRadians(midDeg);
            float tx = cx + (float) Math.cos(mid) * (inner + gap * 0.50f);
            float ty = cy + (float) Math.sin(mid) * (inner + gap * 0.50f);
            drawTrigramBars(c, tx, ty, midDeg + 90f, idx,
                    50f * scale, 5f * scale, 6f * scale, 17f * scale,
                    a(C_GOLD, 52), 2f * scale);
        }

        private void drawTianganDizhi(Canvas c, int hourZhi, int hourGan) {
            float zhiOuter = rMax * 0.865f;
            float zhiInner = rMax * 0.715f;
            rectA.set(cx - zhiOuter, cy - zhiOuter, cx + zhiOuter, cy + zhiOuter);
            rectB.set(cx - zhiInner, cy - zhiInner, cx + zhiInner, cy + zhiInner);
            for (int i = 0; i < 12; i++) {
                float start = i * 30f - 90f;
                path.reset();
                path.arcTo(rectA, start, 30f);
                path.arcTo(rectB, start + 30f, -30f);
                path.close();
                boolean hot = i == hourZhi;
                pFill.setStyle(Paint.Style.FILL);
                pFill.setColor(hot ? Color.rgb(86, 58, 18) : Color.rgb(35, 27, 14));
                c.drawPath(path, pFill);
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setColor(hot ? a(C_GOLD, 255) : a(C_GOLD, 150));
                pStroke.setStrokeWidth((hot ? 1.9f : 1.35f) * scale);
                c.drawPath(path, pStroke);
                drawRingText(c, DI_ZHI[i], start + 15f, (zhiOuter + zhiInner) / 2f,
                        ringTextSize(23f, zhiOuter - zhiInner, 0.50f, 17f),
                        hot ? Color.rgb(255, 226, 92) : Color.rgb(211, 176, 74),
                        true, hot ? 7f * scale : 0f);
            }

            float ganOuter = zhiInner;
            float ganInner = rMax * 0.610f;
            rectA.set(cx - ganOuter, cy - ganOuter, cx + ganOuter, cy + ganOuter);
            rectB.set(cx - ganInner, cy - ganInner, cx + ganInner, cy + ganInner);
            for (int i = 0; i < 10; i++) {
                float start = i * 36f - 90f;
                path.reset();
                path.arcTo(rectA, start, 36f);
                path.arcTo(rectB, start + 36f, -36f);
                path.close();
                boolean hot = i == hourGan;
                pFill.setStyle(Paint.Style.FILL);
                pFill.setColor(hot ? Color.rgb(72, 48, 17) : Color.rgb(31, 25, 14));
                c.drawPath(path, pFill);
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setColor(hot ? a(C_GOLD, 245) : a(C_GOLD, 132));
                pStroke.setStrokeWidth((hot ? 1.5f : 1.15f) * scale);
                c.drawPath(path, pStroke);
                drawRingText(c, TIAN_GAN[i], start + 18f, (ganOuter + ganInner) / 2f,
                        ringTextSize(19f, ganOuter - ganInner, 0.50f, 15.5f),
                        hot ? Color.rgb(255, 220, 86) : Color.rgb(202, 168, 72),
                        true, hot ? 5.5f * scale : 0f);
            }
        }

        private void drawRingText(Canvas c, String text, float deg, float radius,
                                  float size, int color, boolean bold, float glow) {
            float rad = (float) Math.toRadians(deg);
            float x = cx + (float) Math.cos(rad) * radius;
            float y = cy + (float) Math.sin(rad) * radius;
            c.save();
            c.rotate(deg + 90f, x, y);
            pText.setStyle(Paint.Style.FILL);
            pText.setTextSize(size);
            pText.setFakeBoldText(bold);
            if (glow > 0f) pText.setShadowLayer(glow, 0f, 0f, a(C_GOLD, 165));
            pText.setColor(color);
            drawTextCenteredOnPoint(c, text, x, y, pText);
            pText.clearShadowLayer();
            pText.setFakeBoldText(false);
            c.restore();
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
                    rectC.set(x - barW / 2f, yy, x + barW / 2f, yy + barH);
                    c.drawRoundRect(rectC, radius, radius, pFill);
                } else {
                    float halfW = (barW - yinGap) / 2f;
                    rectC.set(x - barW / 2f, yy, x - barW / 2f + halfW, yy + barH);
                    c.drawRoundRect(rectC, radius, radius, pFill);
                    rectC.set(x + barW / 2f - halfW, yy, x + barW / 2f, yy + barH);
                    c.drawRoundRect(rectC, radius, radius, pFill);
                }
            }
            c.restore();
        }

        private void drawBatteryRing() {
            float outer = rMax * 0.595f;
            float inner = rMax * 0.565f;
            float mid = (outer + inner) / 2f;
            int steps = FULL_RING_STEPS;
            drawSoftArcRing(inner, outer, -90f, 360f, Color.rgb(74, 46, 24), steps);
            drawArcRing(cx, cy, outer - 0.65f * scale, outer + 0.15f * scale,
                    0f, 360f, a(C_GOLD, 42), steps);
            drawArcRing(cx, cy, inner - 0.15f * scale, inner + 0.65f * scale,
                    0f, 360f, a(C_GOLD, 42), steps);
            if (hub.battery >= 0 && hub.battery <= 100) {
                int remain = hub.battery < 20 ? Color.rgb(183, 47, 37) : Color.rgb(246, 204, 70);
                float sweep = 360f * hub.battery / 100f;
                drawSoftArcRing(inner - 0.7f * scale, outer + 0.7f * scale,
                        -90f, sweep, a(remain, 72), stepsForSweep(sweep, 240, FULL_RING_STEPS));
                drawSoftArcRing(inner + 0.7f * scale, outer - 0.7f * scale,
                        -90f, sweep, remain, stepsForSweep(sweep, 240, FULL_RING_STEPS));
                if (sweep > 0.5f && sweep < 359.5f) {
                    drawBatteryCap(mid, (outer - inner) * 0.5f - 0.7f * scale, -90f, remain);
                    drawBatteryCap(mid, (outer - inner) * 0.5f - 0.7f * scale,
                            -90f + sweep, remain);
                }
            }
        }

        private int stepsForSweep(float sweep, int min, int max) {
            return Math.max(min, Math.min(max, Math.round(Math.abs(sweep) * 0.65f)));
        }

        private void drawSoftArcRing(float inner, float outer, float start, float sweep,
                                     int color, int steps) {
            if (outer <= inner) return;
            float feather = Math.min((outer - inner) * 0.24f, Math.max(0.9f * scale, 0.9f));
            float solidInner = inner + feather;
            float solidOuter = outer - feather;
            if (solidOuter > solidInner) {
                drawArcRing(cx, cy, solidInner, solidOuter, start, sweep, color, steps);
            } else {
                drawArcRing(cx, cy, inner, outer, start, sweep, a(color, Color.alpha(color) * 3 / 4), steps);
                return;
            }
            int edgeAlpha = Color.alpha(color) * 7 / 16;
            drawArcRing(cx, cy, inner, solidInner, start, sweep, a(color, edgeAlpha), steps);
            drawArcRing(cx, cy, solidOuter, outer, start, sweep, a(color, edgeAlpha), steps);
        }

        private void drawBatteryCap(float radius, float capR, float deg, int color) {
            float rad = (float) Math.toRadians(deg);
            float x = cx + (float) Math.cos(rad) * radius;
            float y = cy + (float) Math.sin(rad) * radius;
            drawCircle(x, y, capR + 0.75f * scale, a(color, 70), DOT_STEPS);
            drawCircle(x, y, capR, color, DOT_STEPS);
        }

        private void drawCompassRing(float az) {
            float r = rMax * 0.55f;
            drawArcRing(cx, cy, r * 1.015f, r * 1.025f, 0f, 360f, a(C_GOLD_DARK, 130), FULL_RING_STEPS);
            drawArcRing(cx, cy, r * 0.755f, r * 0.765f, 0f, 360f, a(C_CYAN, 70), FULL_RING_STEPS);
            for (int d = 0; d < 360; d += 5) {
                boolean cardinal = d % 90 == 0;
                boolean major = d % 30 == 0;
                float rad = (float) Math.toRadians(d - 90f - az);
                float len = cardinal ? 0.78f : (major ? 0.84f : 0.91f);
                float baseTick = 2.05f * scale;
                int col = cardinal ? a(C_GOLD, 250) : (major ? a(C_GOLD, 220) : a(C_GOLD, 172));
                float width = cardinal ? baseTick * 2.05f : (major ? baseTick * 1.45f : baseTick);
                drawLine(cx + (float) Math.cos(rad) * r * len,
                        cy + (float) Math.sin(rad) * r * len,
                        cx + (float) Math.cos(rad) * r,
                        cy + (float) Math.sin(rad) * r,
                        width, col);
            }
            ensureDirTextures();
            for (int i = 0; i < 4; i++) {
                float rad = (float) Math.toRadians(i * 90f - 90f - az);
                float tx = cx + (float) Math.cos(rad) * r * 0.70f;
                float ty = cy + (float) Math.sin(rad) * r * 0.70f;
                float size = dirTextureSizes[i];
                drawTexture(dirTextures[i], tx - size / 2f, ty - size / 2f, size, size);
            }
        }

        private void drawClock(Calendar cal) {
            float r = rMax * 0.36f;
            for (int i = 0; i < 12; i++) {
                float rad = (float) Math.toRadians(i * 30f - 90f);
                float inner = r * (i % 3 == 0 ? 0.94f : 0.95f);
                drawLine(cx + (float) Math.cos(rad) * inner,
                        cy + (float) Math.sin(rad) * inner,
                        cx + (float) Math.cos(rad) * r,
                        cy + (float) Math.sin(rad) * r,
                        (i % 3 == 0 ? 2.4f : 1.1f) * scale,
                        i % 3 == 0 ? a(C_GOLD, 170) : a(C_DIM, 125));
            }
            float min = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f;
            float hour = (cal.get(Calendar.HOUR) % 12) + min / 60f;
            drawHand(r * 0.54f, hour * 30f, 3.0f * scale, a(C_GOLD, 135));
            drawHand(r * 0.76f, min * 6f, 2.0f * scale, a(C_GOLD, 165));
            drawSecondComet(r, cal.get(Calendar.SECOND) * 6f);
        }

        private void updateTimeRitual(Calendar now) {
            long slot = TimeRitual.slotKey(now);
            if (timeRitualSlot == Long.MIN_VALUE) {
                timeRitualSlot = slot;
            } else if (slot != timeRitualSlot) {
                timeRitualSlot = slot;
                timeRitualStartedAtMs = SystemClock.uptimeMillis();
            }
        }

        private boolean isTimeRitualActive() {
            return timeRitualStartedAtMs > 0L
                    && SystemClock.uptimeMillis() - timeRitualStartedAtMs < TimeRitual.PULSE_MS;
        }

        private float timeRitualProgress() {
            if (timeRitualStartedAtMs <= 0L) return 1f;
            long elapsed = SystemClock.uptimeMillis() - timeRitualStartedAtMs;
            return Math.max(0f, Math.min(1f, elapsed / (float) TimeRitual.PULSE_MS));
        }

        private void drawNightDim(boolean night) {
            if (night) drawCircle(cx, cy, rMax, Color.argb(24, 0, 0, 0), FULL_RING_STEPS);
        }

        private void drawTimeRitualPulse(int hourZhi, int hourGan, boolean night) {
            if (!isTimeRitualActive()) return;
            float progress = timeRitualProgress();
            float glow = (float) Math.sin(Math.PI * progress);
            if (night) glow *= 0.88f;

            float zhiOuter = rMax * 0.865f;
            float zhiInner = rMax * 0.715f;
            float zhiRadius = (zhiOuter + zhiInner) * 0.5f;
            drawArcRing(cx, cy, zhiRadius - (1.15f + glow) * scale,
                    zhiRadius + (1.15f + glow) * scale,
                    hourZhi * 30f - 88f, 26f,
                    a(C_GOLD, (int) (76f + 150f * glow)), 96);

            float ganOuter = zhiInner;
            float ganInner = rMax * 0.610f;
            float ganRadius = (ganOuter + ganInner) * 0.5f;
            drawArcRing(cx, cy, ganRadius - (0.75f + 0.55f * glow) * scale,
                    ganRadius + (0.75f + 0.55f * glow) * scale,
                    hourGan * 36f - 87f, 30f,
                    a(C_GOLD, (int) (48f + 112f * glow)), 96);

            float ringRadius = rMax * (0.31f + 0.07f * progress);
            drawArcRing(cx, cy, ringRadius - 0.72f * scale, ringRadius + 0.72f * scale,
                    0f, 360f, a(C_GOLD, (int) (88f * (1f - progress))), FULL_RING_STEPS);
        }

        private void drawSecondComet(float r, float deg) {
            float trailR = r * 0.82f;
            float end = deg - 90f;
            float span = 38f;
            float step = span / 8f;
            for (int i = 0; i < 8; i++) {
                float t = (i + 1f) / 8f;
                float half = (1.0f + 1.55f * t) * scale;
                drawArcRing(cx, cy, trailR - half, trailR + half,
                        end - span + i * step, step * 0.82f,
                        a(C_GOLD, (int) (18 + 118 * t)), 18);
            }
            drawHand(trailR, deg, 7.4f * scale, a(C_GOLD, 105));
            drawHand(trailR, deg, 3.7f * scale, Color.rgb(255, 220, 86));
            float rad = (float) Math.toRadians(deg - 90f);
            float tx = cx + (float) Math.cos(rad) * trailR;
            float ty = cy + (float) Math.sin(rad) * trailR;
            drawCircle(tx, ty, 5.8f * scale, a(C_GOLD, 72), DOT_STEPS);
            drawCircle(tx, ty, 3.0f * scale, Color.rgb(255, 226, 92), DOT_STEPS);
        }

        private void drawHand(float len, float deg, float width, int color) {
            float rad = (float) Math.toRadians(deg - 90f);
            drawLine(cx, cy, cx + (float) Math.cos(rad) * len,
                    cy + (float) Math.sin(rad) * len, width, color);
        }

        private void drawCenter(float az) {
            float r = rMax * 0.235f;
            float ringR = r * 1.14f;
            float diskR = r * 0.93f;
            drawArcRing(cx, cy, ringR - 0.8f * scale, ringR + 0.8f * scale,
                    0f, 360f, a(C_GOLD_DARK, 116), FULL_RING_STEPS);
            drawArcRing(cx, cy, ringR * 0.78f - 0.5f * scale, ringR * 0.78f + 0.5f * scale,
                    0f, 360f, a(C_CYAN, 58), FULL_RING_STEPS);
            if (!Float.isNaN(az)) {
                float showAz = az % 360f;
                if (showAz < 0f) showAz += 360f;
                drawArcRing(cx, cy, ringR - 1f * scale, ringR + 1f * scale,
                        -90f, showAz, a(C_CYAN, 110), stepsForSweep(showAz, 180, FULL_RING_STEPS));
                float rad = (float) Math.toRadians(showAz - 90f);
                drawCircle(cx + (float) Math.cos(rad) * ringR,
                        cy + (float) Math.sin(rad) * ringR, 3.0f * scale, C_GOLD, DOT_STEPS);
            }

            drawCircle(cx, cy, diskR, Color.rgb(10, 9, 7), FULL_RING_STEPS);
            drawArcRing(cx, cy, diskR * 1.04f - 2.1f * scale, diskR * 1.04f + 2.1f * scale,
                    0f, 360f, a(C_GOLD, 92), FULL_RING_STEPS);
            drawArcRing(cx, cy, diskR - 1.5f * scale, diskR + 1.5f * scale,
                    0f, 360f, C_GOLD, FULL_RING_STEPS);
            drawTaiji(diskR, Float.isNaN(az) ? 0f : -az * 0.18f);
        }

        private void drawTaiji(float r, float rot) {
            drawCircle(cx, cy, r, Color.rgb(217, 169, 44), FULL_RING_STEPS);
            drawLocalSector(0f, 0f, r, -90f, 180f, rot, Color.rgb(4, 4, 3), HALF_RING_STEPS);
            drawLocalCircle(0f, r / 2f, r / 2f, rot, Color.rgb(4, 4, 3), HALF_RING_STEPS);
            drawLocalCircle(0f, -r / 2f, r / 2f, rot, Color.rgb(217, 169, 44), HALF_RING_STEPS);
            drawArcRing(cx, cy, r - 0.95f * scale, r + 0.95f * scale, 0f, 360f, a(C_GOLD, 205), FULL_RING_STEPS);
            drawLocalCircle(0f, -r / 2f, r * 0.115f, rot, Color.rgb(4, 4, 3), DOT_STEPS);
            drawLocalCircle(0f, r / 2f, r * 0.115f, rot, Color.rgb(245, 201, 62), DOT_STEPS);
        }

        private void drawLocalCircle(float lx, float ly, float r, float rot, int color, int steps) {
            double rr = Math.toRadians(rot);
            float cs = (float) Math.cos(rr);
            float sn = (float) Math.sin(rr);
            drawCircle(cx + lx * cs - ly * sn, cy + lx * sn + ly * cs, r, color, steps);
        }

        private void drawLocalSector(float lx, float ly, float r, float start, float sweep,
                                     float rot, int color, int steps) {
            int n = Math.min(steps, (verts.length / 2) - 2);
            int k = 0;
            double rr = Math.toRadians(rot);
            float cs = (float) Math.cos(rr);
            float sn = (float) Math.sin(rr);
            verts[k++] = cx + lx * cs - ly * sn;
            verts[k++] = cy + lx * sn + ly * cs;
            for (int i = 0; i <= n; i++) {
                float a = start + sweep * i / n;
                float rad = (float) Math.toRadians(a);
                float px = lx + (float) Math.cos(rad) * r;
                float py = ly + (float) Math.sin(rad) * r;
                verts[k++] = cx + px * cs - py * sn;
                verts[k++] = cy + px * sn + py * cs;
            }
            drawColorVerts(GLES20.GL_TRIANGLE_FAN, (n + 2), color);
        }

        private void drawArcRing(float x, float y, float inner, float outer,
                                 float start, float sweep, int color, int steps) {
            if (Math.abs(sweep) <= 0.01f || outer <= inner) return;
            int n = Math.max(3, Math.min(steps, (verts.length / 4) - 1));
            int k = 0;
            for (int i = 0; i <= n; i++) {
                float a = start + sweep * i / n;
                float rad = (float) Math.toRadians(a);
                float cs = (float) Math.cos(rad);
                float sn = (float) Math.sin(rad);
                verts[k++] = x + cs * outer;
                verts[k++] = y + sn * outer;
                verts[k++] = x + cs * inner;
                verts[k++] = y + sn * inner;
            }
            drawColorVerts(GLES20.GL_TRIANGLE_STRIP, (n + 1) * 2, color);
        }

        private void drawCircle(float x, float y, float r, int color, int steps) {
            int n = Math.max(8, Math.min(steps, (verts.length / 2) - 2));
            int k = 0;
            verts[k++] = x;
            verts[k++] = y;
            for (int i = 0; i <= n; i++) {
                float rad = (float) Math.toRadians(i * 360f / n);
                verts[k++] = x + (float) Math.cos(rad) * r;
                verts[k++] = y + (float) Math.sin(rad) * r;
            }
            drawColorVerts(GLES20.GL_TRIANGLE_FAN, n + 2, color);
        }

        private void drawLine(float x1, float y1, float x2, float y2, float width, int color) {
            float dx = x2 - x1;
            float dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01f) return;
            float nx = -dy / len * width / 2f;
            float ny = dx / len * width / 2f;
            verts[0] = x1 + nx; verts[1] = y1 + ny;
            verts[2] = x1 - nx; verts[3] = y1 - ny;
            verts[4] = x2 + nx; verts[5] = y2 + ny;
            verts[6] = x2 - nx; verts[7] = y2 - ny;
            drawColorVerts(GLES20.GL_TRIANGLE_STRIP, 4, color);
        }

        private void drawColorVerts(int mode, int count, int color) {
            if (count <= 0) return;
            GLES20.glUseProgram(colorProgram);
            GLES20.glUniform2f(colorSize, w, h);
            GLES20.glUniform4f(colorUniform, Color.red(color) / 255f, Color.green(color) / 255f,
                    Color.blue(color) / 255f, Color.alpha(color) / 255f);
            vertBuf.clear();
            vertBuf.put(verts, 0, count * 2);
            vertBuf.position(0);
            GLES20.glEnableVertexAttribArray(colorPos);
            GLES20.glVertexAttribPointer(colorPos, 2, GLES20.GL_FLOAT, false, 0, vertBuf);
            GLES20.glDrawArrays(mode, 0, count);
            GLES20.glDisableVertexAttribArray(colorPos);
        }

        private void drawTexture(int tex, float x, float y, float ww, float hh) {
            if (tex == 0 || ww <= 0f || hh <= 0f) return;
            texVerts[0] = x;      texVerts[1] = y;
            texVerts[2] = x + ww; texVerts[3] = y;
            texVerts[4] = x;      texVerts[5] = y + hh;
            texVerts[6] = x + ww; texVerts[7] = y + hh;
            texUvs[0] = 0f; texUvs[1] = 0f;
            texUvs[2] = 1f; texUvs[3] = 0f;
            texUvs[4] = 0f; texUvs[5] = 1f;
            texUvs[6] = 1f; texUvs[7] = 1f;
            texVertBuf.clear();
            texVertBuf.put(texVerts);
            texVertBuf.position(0);
            texUvBuf.clear();
            texUvBuf.put(texUvs);
            texUvBuf.position(0);
            GLES20.glUseProgram(texProgram);
            GLES20.glUniform2f(texSize, w, h);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(texSampler, 0);
            GLES20.glEnableVertexAttribArray(texPos);
            GLES20.glVertexAttribPointer(texPos, 2, GLES20.GL_FLOAT, false, 0, texVertBuf);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, texUvBuf);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(texPos);
            GLES20.glDisableVertexAttribArray(texCoord);
        }

        private void ensureDirTextures() {
            if (dirTextures[0] != 0) return;
            int size = Math.max(48, Math.round(54f * scale));
            int[] ids = new int[4];
            GLES20.glGenTextures(4, ids, 0);
            for (int i = 0; i < 4; i++) {
                dirTextures[i] = ids[i];
                dirTextureSizes[i] = size;
                Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                pText.setStyle(Paint.Style.FILL);
                pText.setTextSize(22f * scale);
                pText.setFakeBoldText(true);
                pText.setColor(Color.rgb(232, 194, 72));
                drawTextCenteredOnPoint(c, DIRS[i], size / 2f, size / 2f, pText);
                pText.setFakeBoldText(false);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dirTextures[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                bm.recycle();
            }
        }

        private void deleteDirTextures() {
            if (dirTextures[0] == 0) return;
            GLES20.glDeleteTextures(4, dirTextures, 0);
            for (int i = 0; i < dirTextures.length; i++) {
                dirTextures[i] = 0;
                dirTextureSizes[i] = 0;
            }
        }

        private int makeProgram(String vertexSrc, String fragmentSrc) {
            int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(p));
            return p;
        }

        private int compile(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
            return shader;
        }

        private void fail() {
            surfaceReady = false;
            if (fallback != null) fallback.run();
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

        private void drawTextCenteredOnPoint(Canvas c, String text, float x, float y, Paint paint) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(text, x - paint.measureText(text) / 2f,
                    y - (fm.ascent + fm.descent) / 2f, paint);
        }

        private float ringTextSize(float preferred, float ringWidth, float fitRatio, float min) {
            return Math.max(min * scale, Math.min(preferred * scale, ringWidth * fitRatio));
        }

        private int currentHourZhi(Calendar cal) {
            return TimeRitual.hourZhi(cal);
        }

        private int currentHourGan(Calendar cal, int hourZhi) {
            return TimeRitual.hourGan(cal, hourZhi);
        }
    }
}
