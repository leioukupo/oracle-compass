package com.magneo.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import com.magneo.compass.ui.Ui;

import java.lang.reflect.Method;

/** Short bridge from the final boot-animation frame into the live compass. */
public final class StartupRevealView extends View {
    private static final long DURATION_MS = 950;
    private static final long FOCUS_SETTLE_MS = 20;
    private static final long BOOT_POLL_MS = 50;
    private static final long BOOT_INTRO_MS = 64L * 1000L / 12L;
    private static final long BOOT_LOOP_MS = 3000L;
    private static final float CLOCK_TICK_MS = 10f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petal = new Path();
    private long startedAt;
    private Runnable onFinished;
    private Runnable onFirstDraw;
    private boolean armed;
    private boolean released;
    private boolean startPosted;
    private boolean finished;
    private boolean firstDrawReported;
    private float initialLoopCycle = Float.NaN;

    public StartupRevealView(Context context) {
        super(context);
        setClickable(true);
    }

    public void start(Runnable finishedCallback) {
        hold(finishedCallback);
        release();
    }

    public void setOnFirstDraw(Runnable callback) {
        onFirstDraw = callback;
    }

    public void hold(Runnable finishedCallback) {
        onFinished = finishedCallback;
        armed = true;
        released = false;
        invalidate();
    }

    public void release() {
        released = true;
        beginWhenVisible();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) beginWhenVisible();
    }

    private void beginWhenVisible() {
        if (!armed || !released || finished || startedAt != 0 || startPosted
                || !hasWindowFocus()) return;
        startPosted = true;
        postDelayed(() -> {
            startPosted = false;
            if (!armed || !released || finished || startedAt != 0 || !hasWindowFocus()) return;
            if (!bootAnimationStopped()) {
                beginWhenVisible();
                return;
            }
            startedAt = SystemClock.uptimeMillis();
            initialLoopCycle = bootLoopCycle();
            invalidate();
        }, bootAnimationStopped() ? FOCUS_SETTLE_MS : BOOT_POLL_MS);
    }

    private static boolean bootAnimationStopped() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            return !"running".equals(get.invoke(null, "init.svc.bootanim", "stopped"));
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!armed && startedAt == 0) return;
        float progress = startedAt == 0 ? 0f : Math.min(1f,
                (SystemClock.uptimeMillis() - startedAt) / (float) DURATION_MS);
        float eased = smooth(progress);
        float fade = 1f - smooth(clamp((progress - 0.18f) / 0.82f));
        int alpha = Math.max(0, Math.min(255, Math.round(255f * fade)));

        float w = getWidth();
        float h = getHeight();
        float side = Math.min(w, h);
        float scale = side / 800f;
        float cx = w / 2f;
        float cy = h / 2f;
        float lotusAmount = 1f - smooth(clamp(progress / 0.78f));
        float cycle = Float.isNaN(initialLoopCycle) ? bootLoopCycle() : initialLoopCycle;
        float motion = cycle + progress * DURATION_MS / (float) BOOT_LOOP_MS;

        canvas.drawColor(Color.argb(alpha, 7, 6, 4));
        drawInstrument(canvas, cx, cy, scale, alpha,
                7.5f * motion, -22.5f * motion, 15f * motion);
        drawBagua(canvas, cx, cy, scale, alpha, -45f * motion);
        drawLotus(canvas, cx, cy, scale, lotusAmount, alpha,
                30f * motion, -60f * motion, 90f * motion);
        drawFlowerBoundary(canvas, cx, cy, scale, alpha);
        drawCoreMedallion(canvas, cx, cy, scale, alpha);

        if (!firstDrawReported) {
            firstDrawReported = true;
            Runnable callback = onFirstDraw;
            if (callback != null) post(callback);
        }

        if (startedAt == 0) {
            return;
        } else if (progress < 1f) {
            postInvalidateDelayed(16);
        } else if (!finished) {
            finished = true;
            armed = false;
            Runnable callback = onFinished;
            if (callback != null) post(callback);
        }
    }

    private void drawInstrument(Canvas canvas, float cx, float cy, float scale, int alpha,
                                float outerRotation, float middleRotation,
                                float innerRotation) {
        float outer = 382f * scale;
        float middle = 338f * scale;
        float inner = 266f * scale;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 7 / 10));
        canvas.drawCircle(cx, cy, outer, paint);
        for (int i = 0; i < 48; i++) {
            double angle = Math.toRadians(i * 7.5 - 90 + outerRotation);
            paint.setStrokeWidth(1.4f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 5 / 10));
            radialLine(canvas, cx, cy, angle, outer - 13f * scale, outer);
        }
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45 - 90);
            paint.setStrokeWidth(3f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 9 / 10));
            radialLine(canvas, cx, cy, angle, outer - 28f * scale, outer);
        }

        paint.setStrokeWidth(2f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 6 / 10));
        canvas.drawCircle(cx, cy, middle, paint);
        canvas.drawCircle(cx, cy, middle - 18f * scale, paint);
        RectF middleArc = new RectF(cx - middle + 9f * scale, cy - middle + 9f * scale,
                cx + middle - 9f * scale, cy + middle - 9f * scale);
        for (int i = 0; i < 16; i++) {
            paint.setStrokeWidth(2f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 6 / 10));
            canvas.drawArc(middleArc, i * 22.5f - 90f + middleRotation,
                    12.5f, false, paint);
        }
        for (int i = 0; i < 4; i++) {
            paint.setStrokeWidth(3f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 8 / 10));
            canvas.drawArc(middleArc, i * 90f - 90f, 12.5f, false, paint);
        }

        paint.setStrokeWidth(2f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 7 / 10));
        canvas.drawCircle(cx, cy, inner, paint);
        for (int i = 0; i < 24; i++) {
            double angle = Math.toRadians(i * 15 - 90 + innerRotation);
            paint.setStrokeWidth(1.3f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 5 / 10));
            radialLine(canvas, cx, cy, angle, inner - 6f * scale, inner);
        }
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45 - 90);
            paint.setStrokeWidth(2.6f * scale);
            paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 9 / 10));
            radialLine(canvas, cx, cy, angle, inner - 10f * scale, inner);
        }
    }

    private void drawLotus(Canvas canvas, float cx, float cy, float scale,
                           float amount, int alpha,
                           float largeRotation, float mediumRotation,
                           float smallRotation) {
        drawBootLotus(canvas, paint, petal, cx, cy, scale, amount, alpha,
                largeRotation, mediumRotation, smallRotation);
    }

    /** Shared boot-lotus geometry for transition surfaces such as the Vision HUD. */
    public static void drawBootLotus(Canvas canvas, Paint paint, Path petal,
                                     float cx, float cy, float scale, float amount, int alpha,
                                     float largeRotation, float mediumRotation,
                                     float smallRotation) {
        drawPetalLayer(canvas, paint, petal, cx, cy, scale, amount, alpha, largeRotation, 12,
                72f, 227f, 64f, Ui.COLOR_GOLD_DARK, 0.24f, 0.86f);
        drawPetalLayer(canvas, paint, petal, cx, cy, scale, amount, alpha, 10f + mediumRotation, 12,
                72f, 188f, 48f, Color.rgb(178, 143, 50), 0.30f, 0.92f);
        drawPetalLayer(canvas, paint, petal, cx, cy, scale, amount, alpha, 20f + smallRotation, 12,
                72f, 160f, 34f, Ui.COLOR_GOLD, 0.38f, 0.98f);
    }

    private void drawBagua(Canvas canvas, float cx, float cy, float scale,
                           int alpha, float rotation) {
        int[] trigrams = {7, 3, 5, 1, 0, 4, 2, 6};
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3.4f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 9 / 10));
        for (int i = 0; i < 8; i++) {
            int save = canvas.save();
            canvas.rotate(i * 45f - 90f + rotation, cx, cy);
            int pattern = trigrams[i];
            for (int row = 0; row < 3; row++) {
                float x = cx + (292f + (row - 1) * 11f) * scale;
                boolean solid = ((pattern >> row) & 1) != 0;
                if (solid) {
                    canvas.drawLine(x, cy - 16f * scale, x, cy + 16f * scale, paint);
                } else {
                    canvas.drawLine(x, cy - 16f * scale, x, cy - 4f * scale, paint);
                    canvas.drawLine(x, cy + 4f * scale, x, cy + 16f * scale, paint);
                }
            }
            canvas.restoreToCount(save);
        }
    }

    private static void drawPetalLayer(Canvas canvas, Paint paint, Path petal,
                                float cx, float cy, float scale, float amount, int alpha, float rotation,
                                int count,
                                float rootPx, float tipPx, float widthPx,
                                int color, float fillAlpha, float strokeAlpha) {
        if (amount <= 0f || alpha <= 0) return;
        float root = rootPx * scale;
        float extension = (tipPx - rootPx) * scale * amount;
        float tip = root + extension;
        float width = Math.max(2f * scale, widthPx * scale * amount);
        for (int i = 0; i < count; i++) {
            int save = canvas.save();
            canvas.rotate(i * (360f / count) - 90f + rotation, cx, cy);
            petal.reset();
            petal.moveTo(cx + root, cy);
            petal.cubicTo(cx + root + extension * 0.10f, cy - width * 0.42f,
                    cx + root + extension * 0.54f, cy - width, cx + tip, cy);
            petal.cubicTo(cx + root + extension * 0.54f, cy + width,
                    cx + root + extension * 0.10f, cy + width * 0.42f, cx + root, cy);
            petal.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, Math.round(alpha * fillAlpha * amount)));
            canvas.drawPath(petal, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2f * scale);
            paint.setColor(withAlpha(color, Math.round(alpha * strokeAlpha * amount)));
            canvas.drawPath(petal, paint);
            paint.setStrokeWidth(scale);
            paint.setColor(withAlpha(color, Math.round(alpha * 0.28f * amount)));
            canvas.drawLine(cx + root + 8f * scale, cy,
                    Math.max(cx + root + 8f * scale, cx + tip - 14f * scale), cy, paint);
            canvas.restoreToCount(save);
        }
    }

    private void drawFlowerBoundary(Canvas canvas, float cx, float cy, float scale, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 6 / 10));
        canvas.drawCircle(cx, cy, 247f * scale, paint);
    }

    private void drawCoreMedallion(Canvas canvas, float cx, float cy, float scale, int alpha) {
        drawBootCoreMedallion(canvas, paint, cx, cy, scale, alpha);
    }

    /** Shared gold-and-ink Taiji core used with {@link #drawBootLotus}. */
    public static void drawBootCoreMedallion(Canvas canvas, Paint paint,
                                             float cx, float cy, float scale, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(alpha, 7, 6, 4));
        canvas.drawCircle(cx, cy, 112f * scale, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha));
        canvas.drawCircle(cx, cy, 112f * scale, paint);
        paint.setStrokeWidth(1.4f * scale);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 6 / 10));
        canvas.drawCircle(cx, cy, 104f * scale, paint);
        drawTaiji(canvas, paint, cx, cy, 90f * scale, alpha, 1.5f * scale);
    }

    private void radialLine(Canvas canvas, float cx, float cy, double angle,
                            float inner, float outer) {
        canvas.drawLine(cx + (float) Math.cos(angle) * inner,
                cy + (float) Math.sin(angle) * inner,
                cx + (float) Math.cos(angle) * outer,
                cy + (float) Math.sin(angle) * outer, paint);
    }

    private static void drawTaiji(Canvas canvas, Paint paint, float cx, float cy, float radius,
                                  int alpha, float strokeWidth) {
        int save = canvas.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        canvas.drawRect(cx, cy - radius, cx + radius, cy + radius, paint);
        canvas.drawCircle(cx, cy - radius / 2f, radius / 2f, paint);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha));
        canvas.drawCircle(cx, cy + radius / 2f, radius / 2f, paint);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha));
        canvas.drawCircle(cx, cy - radius / 2f, radius * 0.105f, paint);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        canvas.drawCircle(cx, cy + radius / 2f, radius * 0.105f, paint);
        canvas.restoreToCount(save);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static float bootLoopCycle() {
        String raw = systemProperty("oracle.bootanim.start_ticks", "");
        try {
            long ticks = Long.parseLong(raw.trim());
            long processStartMs = Math.round(ticks * CLOCK_TICK_MS);
            long loopElapsed = SystemClock.uptimeMillis() - processStartMs - BOOT_INTRO_MS;
            if (loopElapsed < 0) return 0f;
            return (loopElapsed % BOOT_LOOP_MS) / (float) BOOT_LOOP_MS;
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private static String systemProperty(String key, String fallback) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smooth(float value) {
        float v = clamp(value);
        return v * v * (3f - 2f * v);
    }
}
