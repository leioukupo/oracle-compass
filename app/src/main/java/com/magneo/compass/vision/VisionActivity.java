package com.magneo.compass.vision;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.cam.CameraStreamService;
import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.ui.Ui;
import com.magneo.compass.voice.VadService;
import com.magneo.compass.voice.VoiceController;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 灵眼：全屏显示摄像头画面，订阅共享语音流的最终文本后结合当前画面回答。
 */
public class VisionActivity extends com.magneo.compass.BaseActivity {
    private static final String TAG = "VisionActivity";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private final AtomicInteger visionTurn = new AtomicInteger();
    private final List<LlmClient.Msg> history = new ArrayList<>();

    private volatile byte[] lastNv21;
    private volatile int camW;
    private volatile int camH;
    private volatile boolean rendering;
    private volatile String overlayText = "";

    private FrameView frameView;
    private OverlayView overlayView;
    private Thread renderThread;
    private VoiceController voiceController;
    private boolean startedVoiceForVision;
    private final VoiceController.SpeechConsumer speechConsumer = text -> {
        handleSpeechText(text);
        return true;
    };

    private final CameraStreamService.FrameListener frameListener = new CameraStreamService.FrameListener() {
        @Override public void onFrame(byte[] nv21, int w, int h) {
            lastNv21 = nv21.clone();
            camW = w;
            camH = h;
            if (frameView != null) frameView.postInvalidate();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        frameView = new FrameView(this);
        root.addView(frameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        setContentView(root);
        startRenderThread();
    }

    @Override protected void onResume() {
        super.onResume();
        CameraStreamService.start(getApplicationContext());
        CameraStreamService.setFrameListener(frameListener);
        if (canListenForAi()) {
            voiceController = VoiceController.get(this, null);
            voiceController.setSpeechConsumer(speechConsumer);
            boolean globalVad = Prefs.getB(this, Prefs.K_VAD_ENABLED, false);
            startedVoiceForVision = !globalVad;
            if (globalVad) startService(new Intent(this, VadService.class));
            else voiceController.ensureContinuousListening();
            showOverlay("灵眼聆听中");
            h.postDelayed(() -> clearOverlayIf("灵眼聆听中"), 1800);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (voiceController != null) {
            voiceController.clearSpeechConsumer(speechConsumer);
            if (startedVoiceForVision) voiceController.stopContinuousListening();
        }
        startedVoiceForVision = false;
        CameraStreamService.setFrameListener(null);
        CameraStreamService.stop(getApplicationContext());
    }

    @Override protected void onDestroy() {
        rendering = false;
        h.removeCallbacksAndMessages(null);
        if (renderThread != null) renderThread.interrupt();
        if (frameView != null) frameView.setBitmap(null);
        super.onDestroy();
    }

    private boolean canListenForAi() {
        LlmClient llm = new LlmClient(this);
        if (llm.apiKey.isEmpty()) {
            showOverlay("未配置 API Key");
            h.postDelayed(() -> clearOverlayIf("未配置 API Key"), 3500);
            return false;
        }
        if (llm.asrUrl.isEmpty()) {
            showOverlay("未配置 ASR");
            h.postDelayed(() -> clearOverlayIf("未配置 ASR"), 3500);
            return false;
        }
        return true;
    }

    private void startRenderThread() {
        if (rendering) return;
        rendering = true;
        renderThread = new Thread(() -> {
            while (rendering) {
                try {
                    byte[] nv21 = lastNv21;
                    int w = camW;
                    int hh = camH;
                    if (nv21 != null && w > 0 && hh > 0) {
                        Bitmap b = frameBitmap(nv21, w, hh, 48);
                        if (b != null) {
                            runOnUiThread(() -> {
                                if (!rendering || frameView == null) {
                                    b.recycle();
                                    return;
                                }
                                frameView.setBitmap(b);
                                frameView.invalidate();
                            });
                        }
                    }
                    Thread.sleep(66);
                } catch (Throwable t) {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "vision-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void handleSpeechText(String spokenText) {
        String incoming = spokenText == null ? "" : spokenText.trim();
        if (incoming.isEmpty()) return;
        int turn = visionTurn.incrementAndGet();
        aiBusy.set(true);
        new Thread(() -> {
            try {
                LlmClient llm = new LlmClient(this);
                if (llm.apiKey.isEmpty()) {
                    showOverlay("未配置 API Key");
                    return;
                }
                String text = incoming.trim();
                ConversationLog.append(this, "user", "[灵眼] " + text);
                showOverlay("我: " + text);

                String imageB64 = currentFrameBase64();
                if (imageB64 == null || imageB64.isEmpty()) {
                    showOverlay("等待画面");
                    return;
                }

                String answer = askVisionBlocking(llm, text, imageB64);
                if (turn != visionTurn.get()) return;
                if (answer.trim().isEmpty()) {
                    showOverlay("视觉模型无返回");
                    return;
                }
                remember(text, answer);
                ConversationLog.append(this, "assistant", "[灵眼] " + answer);
                showOverlay(answer);
                VoiceController vc = voiceController == null ? VoiceController.get(this, null) : voiceController;
                vc.speakText(answer);
            } catch (Exception e) {
                showOverlay("错误: " + e.getMessage());
            } finally {
                if (turn == visionTurn.get()) aiBusy.set(false);
            }
        }, "vision-ai").start();
    }

    private String askVisionBlocking(LlmClient llm, String text, String imageB64) throws Exception {
        final Object lock = new Object();
        final StringBuilder streamed = new StringBuilder();
        final String[] finalText = {""};
        final String[] error = {null};

        List<LlmClient.Msg> msgs = new ArrayList<>();
        String sys = Prefs.get(this, Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION);
        msgs.add(new LlmClient.Msg("system", sys + "\n用户会通过麦克风询问当前画面，请结合图片和语音转写内容直接回答，中文简洁。"));
        synchronized (history) {
            msgs.addAll(history);
        }
        msgs.add(new LlmClient.Msg("user", text, imageB64));

        showOverlay("思考中");
        llm.chat(msgs, true, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                if (s == null || s.isEmpty()) return;
                synchronized (lock) { streamed.append(s); }
                showOverlay(streamed.toString());
            }
            @Override public void onDone(String full) {
                synchronized (lock) {
                    finalText[0] = full == null || full.isEmpty() ? streamed.toString() : full;
                    lock.notifyAll();
                }
            }
            @Override public void onError(String msg) {
                synchronized (lock) {
                    error[0] = msg == null ? "LLM 失败" : msg;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) { lock.wait(120000); }
        if (error[0] != null) throw new Exception(error[0]);
        return finalText[0] == null ? "" : finalText[0].trim();
    }

    private void remember(String user, String assistant) {
        synchronized (history) {
            history.add(new LlmClient.Msg("user", user));
            history.add(new LlmClient.Msg("assistant", assistant));
            while (history.size() > 6) history.remove(0);
        }
    }

    private String currentFrameBase64() {
        byte[] nv21 = lastNv21;
        int w = camW;
        int hh = camH;
        if (nv21 == null || w <= 0 || hh <= 0) return null;
        Bitmap full = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            full = frameBitmap(nv21, w, hh, 62);
            if (full == null) return null;
            cropped = centerCrop(full);
            scaled = scaleDown(cropped, 640);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 62, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "capture", t);
            return null;
        } finally {
            if (scaled != null && scaled != cropped && !scaled.isRecycled()) scaled.recycle();
            if (cropped != null && cropped != full && !cropped.isRecycled()) cropped.recycle();
            if (full != null && !full.isRecycled()) full.recycle();
        }
    }

    private Bitmap frameBitmap(byte[] nv21, int w, int h, int quality) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), quality, jpg);
            byte[] bytes = jpg.toByteArray();
            Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (raw == null) return null;
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (rotated != raw) raw.recycle();
            return rotated;
        } catch (Throwable t) {
            Log.w(TAG, "frame bitmap", t);
            return null;
        }
    }

    private Bitmap centerCrop(Bitmap b) {
        int size = Math.min(b.getWidth(), b.getHeight());
        int x = (b.getWidth() - size) / 2;
        int y = (b.getHeight() - size) / 2;
        if (x == 0 && y == 0 && b.getWidth() == size && b.getHeight() == size) return b;
        return Bitmap.createBitmap(b, x, y, size, size);
    }

    private Bitmap scaleDown(Bitmap b, int max) {
        int w = b.getWidth();
        int h = b.getHeight();
        float scale = Math.min(1f, (float) max / Math.max(w, h));
        if (scale >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * scale)),
                Math.max(1, (int) (h * scale)), true);
    }

    private void showOverlay(String s) {
        String text = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        h.post(() -> {
            overlayText = compact(text, 74);
            if (overlayView != null) overlayView.invalidate();
        });
    }

    private void clearOverlayIf(String oldText) {
        if (oldText != null && oldText.equals(overlayText)) showOverlay("");
    }

    private String compact(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(1, max - 3)) + "...";
    }

    private class FrameView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap bmp;

        FrameView(android.content.Context ctx) {
            super(ctx);
            setBackgroundColor(Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            Bitmap b = bmp;
            if (b != null && !b.isRecycled()) {
                int vw = getWidth();
                int vh = getHeight();
                float scale = Math.max((float) vw / b.getWidth(), (float) vh / b.getHeight());
                int dw = (int) (b.getWidth() * scale);
                int dh = (int) (b.getHeight() * scale);
                int dx = (vw - dw) / 2;
                int dy = (vh - dh) / 2;
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(b, null, new Rect(dx, dy, dx + dw, dy + dh), paint);
            } else {
                canvas.drawColor(Ui.COLOR_BG_DEEP);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(Ui.dpF(VisionActivity.this, 14));
                paint.setColor(Ui.COLOR_TEXT_DIM);
                Paint.FontMetrics fm = paint.getFontMetrics();
                canvas.drawText("等待画面", getWidth() / 2f,
                        getHeight() / 2f - (fm.ascent + fm.descent) / 2f, paint);
            }
        }

        void setBitmap(Bitmap b) {
            Bitmap old = bmp;
            bmp = b;
            if (old != null && old != b && !old.isRecycled()) old.recycle();
        }
    }

    private class OverlayView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        OverlayView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            String text = overlayText;
            if (TextUtils.isEmpty(text)) return;

            float w = getWidth();
            float h = getHeight();
            float shortSide = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = shortSide / 2f;
            float barH = Ui.dpF(VisionActivity.this, 38);
            float barCy = h - shortSide * 0.14f;
            float dy = Math.abs(barCy - cy);
            float halfChord = (float) Math.sqrt(Math.max(0, radius * radius - dy * dy))
                    - Ui.dpF(VisionActivity.this, 18);
            float barW = Math.min(halfChord * 2f, Ui.dpF(VisionActivity.this, 292));
            if (barW < Ui.dpF(VisionActivity.this, 180)) {
                barW = Math.min(w - Ui.dpF(VisionActivity.this, 40),
                        Ui.dpF(VisionActivity.this, 240));
            }

            r.set(cx - barW / 2f, barCy - barH / 2f, cx + barW / 2f, barCy + barH / 2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(176, 8, 7, 5));
            canvas.drawRoundRect(r, barH / 2f, barH / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
            p.setColor(Color.argb(132, 212, 175, 55));
            canvas.drawRoundRect(r, barH / 2f, barH / 2f, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(aiBusy.get() ? Ui.COLOR_AETHER : Ui.COLOR_GOLD);
            canvas.drawCircle(r.left + Ui.dpF(VisionActivity.this, 18), barCy,
                    Ui.dpF(VisionActivity.this, 3.5f), p);

            p.setColor(Ui.COLOR_TEXT);
            p.setTextSize(Ui.dpF(VisionActivity.this, 13));
            p.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = p.getFontMetrics();
            float textX = r.left + Ui.dpF(VisionActivity.this, 30);
            float maxTextW = r.width() - Ui.dpF(VisionActivity.this, 44);
            canvas.drawText(fit(text, maxTextW), textX,
                    barCy - (fm.ascent + fm.descent) / 2f, p);
        }

        private String fit(String s, float maxW) {
            if (p.measureText(s) <= maxW) return s;
            String ellipsis = "...";
            int end = s.length();
            while (end > 1 && p.measureText(s.substring(0, end) + ellipsis) > maxW) end--;
            return s.substring(0, Math.max(1, end)) + ellipsis;
        }
    }
}
