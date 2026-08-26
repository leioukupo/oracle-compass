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

/** Short bridge from the final boot-animation frame into the live compass. */
public final class StartupRevealView extends View {
    private static final long DURATION_MS = 900;
    private static final long FOCUS_SETTLE_MS = 80;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path blade = new Path();
    private long startedAt;
    private Runnable onFinished;
    private boolean armed;
    private boolean startPosted;
    private boolean finished;

    public StartupRevealView(Context context) {
        super(context);
        setClickable(true);
    }

    public void start(Runnable finishedCallback) {
        onFinished = finishedCallback;
        armed = true;
        beginWhenVisible();
        invalidate();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) beginWhenVisible();
    }

    private void beginWhenVisible() {
        if (!armed || finished || startedAt != 0 || startPosted || !hasWindowFocus()) return;
        startPosted = true;
        postDelayed(() -> {
            startPosted = false;
            if (!armed || finished || startedAt != 0 || !hasWindowFocus()) return;
            startedAt = SystemClock.uptimeMillis();
            invalidate();
        }, FOCUS_SETTLE_MS);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!armed && startedAt == 0) return;
        float progress = startedAt == 0 ? 0f : Math.min(1f,
                (SystemClock.uptimeMillis() - startedAt) / (float) DURATION_MS);
        float eased = smooth(progress);
        float fade = 1f - smooth(clamp((progress - 0.34f) / 0.66f));
        int alpha = Math.max(0, Math.min(255, Math.round(255f * fade)));

        float w = getWidth();
        float h = getHeight();
        float side = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        float outer = side * 0.46f;
        float iris = side * (0.245f - 0.055f * eased);
        float taiji = side * 0.112f;

        canvas.drawColor(Color.argb(alpha, 7, 6, 4));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Ui.dpF(getContext(), 1));
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha * 3 / 4));
        canvas.drawCircle(cx, cy, outer, paint);
        canvas.drawCircle(cx, cy, iris, paint);

        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45 - 90 + eased * 7);
            float inner = outer - side * (i % 2 == 0 ? 0.052f : 0.034f);
            paint.setStrokeWidth(Ui.dpF(getContext(), i % 2 == 0 ? 2f : 1f));
            paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha * (i % 2 == 0 ? 4 : 2) / 5));
            canvas.drawLine(cx + (float) Math.cos(angle) * inner,
                    cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * outer,
                    cy + (float) Math.sin(angle) * outer, paint);
        }

        drawAperture(canvas, cx, cy, iris, taiji * 0.94f,
                eased * 28f, Math.round(alpha * (1f - eased * 0.75f)));
        drawTaiji(canvas, cx, cy, taiji, alpha);

        if (progress < 1f) {
            postInvalidateDelayed(16);
        } else if (!finished) {
            finished = true;
            armed = false;
            Runnable callback = onFinished;
            if (callback != null) post(callback);
        }
    }

    private void drawAperture(Canvas canvas, float cx, float cy, float iris, float pupil,
                              float rotation, int alpha) {
        for (int i = 0; i < 6; i++) {
            int save = canvas.save();
            canvas.rotate(i * 60f + rotation, cx, cy);
            blade.reset();
            blade.moveTo(cx + pupil, cy);
            blade.cubicTo(cx + iris * 0.38f, cy - iris * 0.34f,
                    cx + iris * 0.72f, cy - iris * 0.24f, cx + iris * 0.86f, cy);
            blade.cubicTo(cx + iris * 0.70f, cy + iris * 0.12f,
                    cx + iris * 0.45f, cy + iris * 0.18f, cx + pupil, cy);
            blade.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha / 5, 6, 5, 3));
            canvas.drawPath(blade, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dpF(getContext(), 1));
            paint.setColor(withAlpha(Ui.COLOR_GOLD, alpha / 2));
            canvas.drawPath(blade, paint);
            canvas.restoreToCount(save);
        }
    }

    private void drawTaiji(Canvas canvas, float cx, float cy, float radius, int alpha) {
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
        paint.setStrokeWidth(Ui.dpF(getContext(), 1.5f));
        paint.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smooth(float value) {
        float v = clamp(value);
        return v * v * (3f - 2f * v);
    }
}
