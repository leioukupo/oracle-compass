package com.magneo.compass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.magneo.compass.ui.RoundScreen;
import com.magneo.compass.ui.Ui;

/**
 * Touch controller overlay for the TALOS rover.  The view deliberately owns all
 * pointer ids so two fingers can control both sticks without relying on pointer
 * indexes, which change whenever a finger is lifted.
 */
public final class RoverControlView extends View {
    public interface Listener {
        void onJoyChanged(int lx, int ly, int rx, int ry, boolean swL, boolean swR);
        void onStopRequested();
    }

    private static final int NONE = -1;
    private static final float DESIGN_R = 400f;
    private static final float STICK_R = 130f;
    private static final float LEFT_X = 215f;
    private static final float RIGHT_X = 585f;
    private static final float STICK_Y = 505f;
    // With the enlarged 59px touch target, y=680 keeps both corner buttons
    // entirely inside the 800px circular glass (including their hit areas).
    private static final float BUTTON_Y = 680f;
    private static final float BUTTON_R = 36f;
    private static final float STOP_R = 43f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Listener listener;

    private int leftPointer = NONE;
    private int rightPointer = NONE;
    private int swLeftPointer = NONE;
    private int swRightPointer = NONE;
    private int stopPointer = NONE;
    private int lx, ly, rx, ry;
    private boolean swL, swR;
    private boolean leftYInvert, rightYInvert;
    private String targetText = "等待 K230";
    private String transportText = "控制链路启动中";
    private String modeText = "模式未知";
    private String videoText = "视频等待中";
    private String statusText = "";

    public RoverControlView(Context context, Listener l) {
        super(context);
        listener = l;
        setFocusable(true);
        setClickable(true);
        text.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
    }

    public void setYInverted(boolean left, boolean right) {
        leftYInvert = left;
        rightYInvert = right;
    }

    public void setTargetText(String value) {
        targetText = value == null || value.length() == 0 ? "等待 K230" : value;
        postInvalidate();
    }

    public void setTransportText(String value) {
        transportText = value == null ? "" : value;
        postInvalidate();
    }

    public void setModeText(String value) {
        modeText = value == null ? "模式未知" : value;
        postInvalidate();
    }

    public void setVideoText(String value) {
        videoText = value == null ? "" : value;
        postInvalidate();
    }

    public void setStatusText(String value) {
        statusText = value == null ? "" : value;
        postInvalidate();
    }

    public void stopAndReset() {
        leftPointer = rightPointer = swLeftPointer = swRightPointer = stopPointer = NONE;
        lx = ly = rx = ry = 0;
        swL = swR = false;
        notifyJoy();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float s = RoundScreen.scale800(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = RoundScreen.R(getWidth(), getHeight()) - 4f * s;

        // A quiet dark veil keeps the video legible without fighting the black-gold UI.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(android.graphics.Color.argb(78, 5, 4, 2));
        c.drawCircle(cx, cy, r, paint);

        paint.setColor(android.graphics.Color.argb(210, 12, 9, 6));
        rect.set(82f * s, 27f * s, 718f * s, 133f * s);
        c.drawRoundRect(rect, 26f * s, 26f * s, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f * s);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, 195));
        c.drawRoundRect(rect, 26f * s, 26f * s, paint);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.COLOR_GOLD);
        text.setTextSize(20f * s);
        c.drawText("车控 · TALOS", cx, 56f * s, text);
        text.setColor(Ui.COLOR_TEXT);
        text.setTextSize(13f * s);
        c.drawText(targetText + "  ·  " + modeText, cx, 80f * s, text);
        text.setColor(Ui.COLOR_AETHER);
        text.setTextSize(12f * s);
        String lower = transportText + "  ·  " + videoText;
        if (statusText.length() > 0) lower += "  ·  " + statusText;
        c.drawText(lower, cx, 103f * s, text);

        drawStick(c, cx - 400f * s + LEFT_X * s, cy - 400f * s + STICK_Y * s,
                lx, ly, "左摇杆", s, leftPointer != NONE);
        drawStick(c, cx - 400f * s + RIGHT_X * s, cy - 400f * s + STICK_Y * s,
                rx, ry, "右摇杆", s, rightPointer != NONE);
        drawButton(c, cx - 400f * s + LEFT_X * s, cy - 400f * s + BUTTON_Y * s,
                BUTTON_R * s, "SW L", swL, false, s);
        drawButton(c, cx, cy - 400f * s + BUTTON_Y * s,
                STOP_R * s, "急停", false, true, s);
        drawButton(c, cx - 400f * s + RIGHT_X * s, cy - 400f * s + BUTTON_Y * s,
                BUTTON_R * s, "SW R", swR, false, s);
    }

    private void drawStick(Canvas c, float x, float y, int ax, int ay, String label,
                            float s, boolean active) {
        float rr = STICK_R * s;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(android.graphics.Color.argb(active ? 155 : 118, 18, 14, 9));
        c.drawCircle(x, y, rr, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * s);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, active ? 235 : 180));
        c.drawCircle(x, y, rr, paint);
        paint.setStrokeWidth(1f * s);
        paint.setColor(withAlpha(Ui.COLOR_GOLD, 110));
        c.drawLine(x - rr * .72f, y, x + rr * .72f, y, paint);
        c.drawLine(x, y - rr * .72f, x, y + rr * .72f, paint);

        float kx = x + ax * rr * .72f / 100f;
        float ky = y - ay * rr * .72f / 100f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? Ui.COLOR_GOLD : Ui.COLOR_GOLD_DARK);
        c.drawCircle(kx, ky, 24f * s, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * s);
        paint.setColor(android.graphics.Color.argb(200, 255, 232, 139));
        c.drawCircle(kx, ky, 24f * s, paint);

        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.COLOR_TEXT);
        text.setTextSize(13f * s);
        c.drawText(label, x, y - rr - 12f * s, text);
        text.setColor(Ui.COLOR_TEXT_DIM);
        text.setTextSize(11f * s);
        c.drawText(String.format(java.util.Locale.US, "%+d, %+d", ax, ay), x, y + rr + 22f * s, text);
    }

    private void drawButton(Canvas c, float x, float y, float rr, String label,
                            boolean pressed, boolean danger, float s) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(danger ? android.graphics.Color.argb(205, 105, 25, 20)
                : android.graphics.Color.argb(190, 22, 16, 9));
        c.drawCircle(x, y, rr, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((pressed || danger ? 2.5f : 1.6f) * s);
        paint.setColor(danger ? Ui.COLOR_ERROR : (pressed ? Ui.COLOR_GOLD : Ui.COLOR_GOLD_DARK));
        c.drawCircle(x, y, rr, paint);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(danger ? Ui.COLOR_TEXT : Ui.COLOR_GOLD);
        text.setTextSize((danger ? 13f : 11f) * s);
        c.drawText(label, x, y + 4f * s, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = ev.getActionIndex();
            assignPointer(ev.getPointerId(index), ev.getX(index), ev.getY(index));
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            updatePointer(ev, leftPointer, true);
            updatePointer(ev, rightPointer, false);
            postInvalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = ev.getPointerId(ev.getActionIndex());
            releasePointer(id);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            stopAndReset();
            return true;
        }
        return true;
    }

    private void assignPointer(int id, float x, float y) {
        float s = RoundScreen.scale800(getWidth(), getHeight());
        float ox = (x - getWidth() / 2f) / s + 400f;
        float oy = (y - getHeight() / 2f) / s + 400f;
        if (inside(ox, oy, LEFT_X, STICK_Y, STICK_R + 34f) && leftPointer == NONE) {
            leftPointer = id;
            updateAxis(x, y, true);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (inside(ox, oy, RIGHT_X, STICK_Y, STICK_R + 34f) && rightPointer == NONE) {
            rightPointer = id;
            updateAxis(x, y, false);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (inside(ox, oy, LEFT_X, BUTTON_Y, BUTTON_R + 23f) && swLeftPointer == NONE) {
            swLeftPointer = id;
            swL = true;
            notifyJoy();
        } else if (inside(ox, oy, RIGHT_X, BUTTON_Y, BUTTON_R + 23f) && swRightPointer == NONE) {
            swRightPointer = id;
            swR = true;
            notifyJoy();
        } else if (inside(ox, oy, 400f, BUTTON_Y, STOP_R + 22f) && stopPointer == NONE) {
            stopPointer = id;
            stopAndReset();
            if (listener != null) listener.onStopRequested();
        }
        postInvalidate();
    }

    private void updatePointer(MotionEvent ev, int id, boolean left) {
        if (id == NONE) return;
        int index = ev.findPointerIndex(id);
        if (index >= 0) updateAxis(ev.getX(index), ev.getY(index), left);
    }

    private void updateAxis(float x, float y, boolean left) {
        float s = RoundScreen.scale800(getWidth(), getHeight());
        float ox = (x - getWidth() / 2f) / s + 400f;
        float oy = (y - getHeight() / 2f) / s + 400f;
        float dx = ox - (left ? LEFT_X : RIGHT_X);
        float dy = oy - STICK_Y;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > STICK_R) {
            float scale = STICK_R / distance;
            dx *= scale;
            dy *= scale;
        }
        int xx = axisValue(dx / STICK_R, false);
        // Screen Y grows downwards; the rover protocol follows the ESP32
        // convention where pushing the stick up is positive Y.
        int yy = axisValue(-dy / STICK_R, left ? leftYInvert : rightYInvert);
        if (left) {
            lx = xx;
            ly = yy;
        } else {
            rx = xx;
            ry = yy;
        }
        notifyJoy();
    }

    private int axisValue(float value, boolean invert) {
        if (Math.abs(value) < 0.08f) value = 0f;
        int out = Math.round(Math.max(-1f, Math.min(1f, value)) * 100f);
        return invert ? -out : out;
    }

    private void releasePointer(int id) {
        if (leftPointer == id) {
            leftPointer = NONE;
            lx = ly = 0;
        }
        if (rightPointer == id) {
            rightPointer = NONE;
            rx = ry = 0;
        }
        if (swLeftPointer == id) {
            swLeftPointer = NONE;
            swL = false;
        }
        if (swRightPointer == id) {
            swRightPointer = NONE;
            swR = false;
        }
        if (stopPointer == id) stopPointer = NONE;
        notifyJoy();
        postInvalidate();
    }

    private boolean inside(float x, float y, float cx, float cy, float rr) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= rr * rr;
    }

    private void notifyJoy() {
        if (listener != null) listener.onJoyChanged(lx, ly, rx, ry, swL, swR);
    }

    private static int withAlpha(int color, int alpha) {
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color));
    }
}
