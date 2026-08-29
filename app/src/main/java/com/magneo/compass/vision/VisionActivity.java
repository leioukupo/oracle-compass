package com.magneo.compass.vision;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.SweepGradient;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.StartupRevealView;
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
    /** 直出预览不经过此处；仅为定格和视觉请求保留低频最新帧。 */
    private static final long VISION_FRAME_INTERVAL_MS = 200L;

    private enum UiState {
        WAITING, LISTENING, THINKING, SPEAKING, FROZEN, ERROR
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private final AtomicInteger visionTurn = new AtomicInteger();
    private final List<LlmClient.Msg> history = new ArrayList<>();

    private final Object latestFrameLock = new Object();
    private byte[] latestNv21;
    private volatile int camW;
    private volatile int camH;
    private volatile boolean rendering;
    private volatile String overlayText = "";
    private volatile long overlaySetAt = 0;
    private volatile boolean useHalVisionFrames = true;
    private volatile boolean mechanicalStyle = true;
    private volatile boolean resumed;
    private volatile long halFrameSeq = 0;
    private volatile long lastRenderedSeq = -1;
    private volatile long lastAcceptedFrameMs = 0;
    private volatile FrameData frozenFrame;
    private volatile UiState uiState = UiState.WAITING;
    private volatile boolean aiConfigured;

    private FrameLayout rootView;
    private TextureView previewView;
    private FrameView frameView;
    private OverlayView overlayView;
    private Thread renderThread;
    private VoiceController voiceController;
    private boolean startedVoiceForVision;
    private boolean startedCameraForVision;
    private int previewLayoutCamW;
    private int previewLayoutCamH;
    private int previewLayoutParentW;
    private int previewLayoutParentH;
    private final Runnable uiStatePoll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (aiBusy.get()) {
                setUiState(UiState.THINKING);
            } else if (voiceController != null && voiceController.isBusy()) {
                setUiState(UiState.SPEAKING);
            } else if (uiState == UiState.THINKING || uiState == UiState.SPEAKING) {
                setUiState(idleUiState());
            }
            if (overlayView != null) overlayView.invalidate();
            h.postDelayed(this, 200);
        }
    };
    private final VoiceController.SpeechConsumer speechConsumer = text -> {
        handleSpeechText(text);
        return true;
    };

    private final CameraStreamService.FrameListener frameListener = new CameraStreamService.FrameListener() {
        @Override public void onFrame(byte[] nv21, int w, int h) {
            long now = SystemClock.uptimeMillis();
            if (now - lastAcceptedFrameMs < VISION_FRAME_INTERVAL_MS) return;
            lastAcceptedFrameMs = now;
            int bytes = w * h * 3 / 2;
            if (nv21 == null || nv21.length < bytes) return;
            synchronized (latestFrameLock) {
                boolean dimensionsChanged = camW != w || camH != h;
                if (latestNv21 == null || latestNv21.length != bytes) latestNv21 = new byte[bytes];
                System.arraycopy(nv21, 0, latestNv21, 0, bytes);
                camW = w;
                camH = h;
                halFrameSeq++;
                if (dimensionsChanged) VisionActivity.this.h.post(VisionActivity.this::layoutDirectPreview);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(Color.BLACK);

        previewView = new TextureView(this);
        previewView.setBackgroundColor(Color.BLACK);
        previewView.setClickable(false);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (resumed && useHalVisionFrames) {
                    CameraStreamService.setVisionPreviewSurface(surface);
                    VisionActivity.this.h.post(VisionActivity.this::layoutDirectPreview);
                }
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                VisionActivity.this.h.post(VisionActivity.this::layoutDirectPreview);
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                CameraStreamService.clearVisionPreviewSurface(surface);
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        frameView = new FrameView(this);
        frameView.setVisibility(View.GONE);
        frameView.setClickable(false);
        root.addView(frameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        root.setOnClickListener(v -> toggleFrozenFrame());

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        mechanicalStyle = Prefs.visionOverlayMechanical(this);
        useHalVisionFrames = Prefs.VISION_FRAME_SOURCE_HAL.equals(Prefs.visionFrameSource(this));
        halFrameSeq = 0;
        lastRenderedSeq = -1;
        lastAcceptedFrameMs = 0;
        synchronized (latestFrameLock) { latestNv21 = null; }
        frozenFrame = null;
        camW = 0;
        camH = 0;
        overlayText = "";
        setUiState(UiState.WAITING);
        startedCameraForVision = !CameraStreamService.isActive();
        if (startedCameraForVision) {
            CameraStreamService.start(getApplicationContext());
        }
        CameraStreamService.setVisionSnapshotEnabled(!useHalVisionFrames);
        CameraStreamService.setFrameListener(useHalVisionFrames ? frameListener : null);
        if (useHalVisionFrames) {
            frameView.setVisibility(View.GONE);
            previewView.setVisibility(View.VISIBLE);
            resetPreviewLayout();
            if (previewView.isAvailable()) {
                CameraStreamService.setVisionPreviewSurface(previewView.getSurfaceTexture());
                h.post(this::layoutDirectPreview);
            }
        } else {
            CameraStreamService.clearVisionPreviewSurface(previewView.getSurfaceTexture());
            previewView.setVisibility(View.GONE);
            resetPreviewLayout();
            frameView.setVisibility(View.VISIBLE);
            startRenderThread();
        }
        aiConfigured = canListenForAi();
        if (aiConfigured) {
            voiceController = VoiceController.get(this, this::onVoiceStatus);
            voiceController.setSpeechConsumer(speechConsumer);
            boolean globalVad = Prefs.vadEnabled(this);
            startedVoiceForVision = !globalVad;
            if (globalVad) startService(new Intent(this, VadService.class));
            else voiceController.ensureContinuousListening();
            String source = Prefs.visionFrameSourceLabel(this);
            showOverlay("灵眼聆听中 · " + source, UiState.LISTENING);
            h.postDelayed(() -> clearOverlayIf("灵眼聆听中 · " + source), 1800);
        }
        h.removeCallbacks(uiStatePoll);
        h.post(uiStatePoll);
    }

    @Override protected void onPause() {
        resumed = false;
        visionTurn.incrementAndGet();
        aiBusy.set(false);
        h.removeCallbacksAndMessages(null);
        super.onPause();
        if (voiceController != null) {
            voiceController.clearSpeechConsumer(speechConsumer);
            if (startedVoiceForVision) voiceController.stopContinuousListening();
        }
        startedVoiceForVision = false;
        frozenFrame = null;
        stopRenderThread();
        if (previewView != null) CameraStreamService.clearVisionPreviewSurface(previewView.getSurfaceTexture());
        CameraStreamService.setVisionSnapshotEnabled(false);
        CameraStreamService.setFrameListener(null);
        if (frameView != null) {
            frameView.setBitmap(null);
            frameView.setVisibility(View.GONE);
        }
        if (startedCameraForVision) {
            CameraStreamService.stop(getApplicationContext());
        }
        startedCameraForVision = false;
    }

    @Override protected void onDestroy() {
        stopRenderThread();
        h.removeCallbacksAndMessages(null);
        if (frameView != null) frameView.setBitmap(null);
        super.onDestroy();
    }

    private boolean canListenForAi() {
        LlmClient llm = new LlmClient(this);
        if (llm.apiKey.isEmpty()) {
            showOverlay("未配置 API Key", UiState.ERROR);
            return false;
        }
        if (llm.asrUrl.isEmpty() && llm.asrFinalUrl.isEmpty()) {
            showOverlay("未配置 ASR", UiState.ERROR);
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
                    FrameData frame = currentFrameData();
                    if (frame != null && frame.seq != lastRenderedSeq) {
                        Bitmap b = frameBitmap(frame.nv21, frame.w, frame.h, renderQuality());
                        if (b != null) {
                            lastRenderedSeq = frame.seq;
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
                    Thread.sleep(renderIntervalMs());
                } catch (Throwable t) {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "vision-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void stopRenderThread() {
        rendering = false;
        Thread t = renderThread;
        renderThread = null;
        if (t != null) t.interrupt();
    }

    /** 预览输出已旋转 90 度，按旧 Canvas 路径的 center-crop 规则铺满屏幕。 */
    private void layoutDirectPreview() {
        if (!useHalVisionFrames || previewView == null || rootView == null) return;
        int parentW = rootView.getWidth();
        int parentH = rootView.getHeight();
        int sourceW = camH;
        int sourceH = camW;
        if (parentW <= 0 || parentH <= 0 || sourceW <= 0 || sourceH <= 0) return;
        if (previewLayoutCamW == camW && previewLayoutCamH == camH
                && previewLayoutParentW == parentW && previewLayoutParentH == parentH) return;
        float scale = Math.max((float) parentW / sourceW, (float) parentH / sourceH);
        int targetW = Math.round(sourceW * scale);
        int targetH = Math.round(sourceH * scale);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        if (lp.width == targetW && lp.height == targetH && lp.gravity == Gravity.CENTER) return;
        lp.width = targetW;
        lp.height = targetH;
        lp.gravity = Gravity.CENTER;
        previewView.setLayoutParams(lp);
        previewLayoutCamW = camW;
        previewLayoutCamH = camH;
        previewLayoutParentW = parentW;
        previewLayoutParentH = parentH;
    }

    private void resetPreviewLayout() {
        if (previewView == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewView.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.CENTER;
        previewView.setLayoutParams(lp);
        previewLayoutCamW = 0;
        previewLayoutCamH = 0;
        previewLayoutParentW = 0;
        previewLayoutParentH = 0;
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
                    showOverlay("未配置 API Key", UiState.ERROR);
                    return;
                }
                String text = incoming.trim();
                ConversationLog.append(this, "user", "[灵眼] " + text);
                showOverlay("我: " + text, UiState.THINKING);

                String imageB64 = currentFrameBase64();
                if (imageB64 == null || imageB64.isEmpty()) {
                    showOverlay("等待画面", UiState.WAITING);
                    return;
                }

                String answer = askVisionBlocking(llm, text, imageB64);
                if (turn != visionTurn.get()) return;
                if (answer.trim().isEmpty()) {
                    showTransientError("视觉模型无返回");
                    return;
                }
                remember(text, answer);
                ConversationLog.append(this, "assistant", "[灵眼] " + answer);
                showOverlay(answer, UiState.SPEAKING);
                VoiceController vc = voiceController == null ? VoiceController.get(this, null) : voiceController;
                vc.speakText(answer);
            } catch (Exception e) {
                showTransientError("错误: " + e.getMessage());
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

        showOverlay("思考中", UiState.THINKING);
        llm.chat(msgs, true, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                if (s == null || s.isEmpty()) return;
                synchronized (lock) { streamed.append(s); }
                showOverlay(streamed.toString(), UiState.THINKING);
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
        FrameData frame = currentFrameData();
        if (frame == null) return null;
        Bitmap full = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            full = frameBitmap(frame.nv21, frame.w, frame.h, askQuality());
            if (full == null) return null;
            cropped = centerCrop(full);
            scaled = scaleDown(cropped, 640);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, askQuality(), out);
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

    private FrameData currentFrameData() {
        FrameData frozen = frozenFrame;
        if (frozen != null) return frozen;
        return currentLiveFrameData();
    }

    private FrameData currentLiveFrameData() {
        if (useHalVisionFrames) {
            synchronized (latestFrameLock) {
                if (latestNv21 == null || camW <= 0 || camH <= 0) return null;
                return new FrameData(latestNv21.clone(), camW, camH, halFrameSeq);
            }
        }
        CameraStreamService.FrameSnapshot snap = CameraStreamService.latestVisionSnapshot();
        if (snap == null || snap.nv21 == null || snap.w <= 0 || snap.h <= 0) return null;
        return new FrameData(snap.nv21, snap.w, snap.h, snap.seq);
    }

    private int renderQuality() {
        return useHalVisionFrames ? 78 : 62;
    }

    private int askQuality() {
        return useHalVisionFrames ? 70 : 62;
    }

    private int renderIntervalMs() {
        return useHalVisionFrames ? (int) VISION_FRAME_INTERVAL_MS : 120;
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

    private static final class FrameData {
        final byte[] nv21;
        final int w;
        final int h;
        final long seq;

        FrameData(byte[] nv21, int w, int h, long seq) {
            this.nv21 = nv21;
            this.w = w;
            this.h = h;
            this.seq = seq;
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
        showOverlay(s, null);
    }

    private void showOverlay(String s, UiState state) {
        String text = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        h.post(() -> {
            overlayText = text;
            overlaySetAt = System.currentTimeMillis();
            if (state != null) uiState = state;
            if (overlayView != null) overlayView.invalidate();
        });
    }

    private void clearOverlayIf(String oldText) {
        if (oldText != null && oldText.equals(overlayText)) showOverlay("");
    }

    private void showTransientError(String text) {
        showOverlay(text, UiState.ERROR);
        h.postDelayed(() -> {
            clearOverlayIf(text);
            if (uiState == UiState.ERROR && aiConfigured) setUiState(idleUiState());
        }, 3500);
    }

    private void setUiState(UiState state) {
        if (state == null || uiState == state) return;
        uiState = state;
        if (overlayView != null) overlayView.postInvalidate();
    }

    private UiState idleUiState() {
        return frozenFrame == null ? UiState.LISTENING : UiState.FROZEN;
    }

    private void onVoiceStatus(String status) {
        if (!resumed || status == null) return;
        String s = status.trim();
        if (s.contains("错误") || s.contains("失败") || s.contains("未配置")) {
            showTransientError(s);
        } else if (s.contains("识别") || s.contains("思考") || s.contains("重试")) {
            setUiState(UiState.THINKING);
        } else if (s.contains("聆听") || s.contains("听见")) {
            setUiState(idleUiState());
        }
    }

    private void toggleFrozenFrame() {
        if (!mechanicalStyle) return;
        if (frozenFrame != null) {
            frozenFrame = null;
            lastRenderedSeq = -1;
            if (useHalVisionFrames) {
                if (frameView != null) {
                    frameView.setBitmap(null);
                    frameView.setVisibility(View.GONE);
                }
                if (previewView != null) previewView.setVisibility(View.VISIBLE);
            }
            showOverlay("实时画面", UiState.LISTENING);
            h.postDelayed(() -> clearOverlayIf("实时画面"), 1400);
            return;
        }
        FrameData live = currentLiveFrameData();
        if (live == null || live.nv21 == null) {
            showOverlay("等待画面", UiState.WAITING);
            h.postDelayed(() -> clearOverlayIf("等待画面"), 1400);
            return;
        }
        final FrameData frozen = live;
        frozenFrame = frozen;
        lastRenderedSeq = -1;
        if (!useHalVisionFrames) {
            showOverlay("画面已定格", UiState.FROZEN);
            h.postDelayed(() -> clearOverlayIf("画面已定格"), 1600);
            return;
        }
        showOverlay("画面定格中", UiState.FROZEN);
        new Thread(() -> {
            Bitmap bitmap = frameBitmap(frozen.nv21, frozen.w, frozen.h, renderQuality());
            runOnUiThread(() -> {
                if (bitmap == null || frozenFrame != frozen || !resumed) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                if (previewView != null) previewView.setVisibility(View.INVISIBLE);
                if (frameView != null) {
                    frameView.setBitmap(bitmap);
                    frameView.setVisibility(View.VISIBLE);
                    frameView.invalidate();
                }
                showOverlay("画面已定格", UiState.FROZEN);
                h.postDelayed(() -> clearOverlayIf("画面已定格"), 1600);
            });
        }, "vision-freeze").start();
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
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path blade = new Path();
        private final Path taijiClip = new Path();
        private final Path taijiGold = new Path();
        private Shader vignette;
        private Shader scanShader;

        OverlayView(android.content.Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) / 2f;
            vignette = new RadialGradient(cx, cy, radius,
                    new int[]{Color.TRANSPARENT, Color.argb(26, 5, 4, 2), Color.argb(212, 3, 3, 2)},
                    new float[]{0.42f, 0.72f, 1f}, Shader.TileMode.CLAMP);
            scanShader = new SweepGradient(cx, cy,
                    new int[]{Color.TRANSPARENT, withAlpha(Ui.COLOR_GOLD, 28),
                            withAlpha(Ui.COLOR_TEXT, 230), Color.TRANSPARENT},
                    new float[]{0f, 0.72f, 0.91f, 1f});
        }

        @Override protected void onDraw(Canvas canvas) {
            String text = overlayText;
            float w = getWidth();
            float h = getHeight();
            float shortSide = Math.min(w, h);
            float cx = w / 2f;
            float cy = h / 2f;
            long now = SystemClock.uptimeMillis();

            if (mechanicalStyle) {
                drawMechanical(canvas, cx, cy, shortSide, now);
                if (resumed) postInvalidateDelayed(33);
            }
            if (!TextUtils.isEmpty(text)) drawCaption(canvas, text, cx, h, shortSide);
        }

        private void drawMechanical(Canvas canvas, float cx, float cy, float shortSide, long now) {
            float radius = shortSide / 2f;
            float outer = radius * 0.93f;
            float iris = radius * 0.51f;
            float pupil = radius * 0.205f;
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 430.0);
            int stateColor = stateColor();

            p.setStyle(Paint.Style.FILL);
            p.setShader(vignette);
            canvas.drawCircle(cx, cy, radius, p);
            p.setShader(null);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
            p.setColor(withAlpha(Ui.COLOR_GOLD_DARK, 190));
            canvas.drawCircle(cx, cy, outer, p);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 2));
            p.setColor(withAlpha(stateColor, 110 + (int) (pulse * 80)));
            canvas.drawArc(new RectF(cx - outer, cy - outer, cx + outer, cy + outer),
                    (now / 24f) % 360f, 72f, false, p);
            canvas.drawArc(new RectF(cx - outer, cy - outer, cx + outer, cy + outer),
                    180f + (now / 31f) % 360f, 42f, false, p);

            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
            for (int i = 0; i < 48; i++) {
                double a = Math.toRadians(i * 7.5 - 90);
                float len = i % 6 == 0 ? radius * 0.052f : radius * 0.025f;
                float x1 = cx + (float) Math.cos(a) * (outer - len);
                float y1 = cy + (float) Math.sin(a) * (outer - len);
                float x2 = cx + (float) Math.cos(a) * outer;
                float y2 = cy + (float) Math.sin(a) * outer;
                p.setColor(withAlpha(i % 6 == 0 ? stateColor : Ui.COLOR_GOLD_DARK,
                        i % 6 == 0 ? 190 : 105));
                canvas.drawLine(x1, y1, x2, y2, p);
            }

            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1.2f));
            p.setColor(withAlpha(Ui.COLOR_GOLD_DARK, 175));
            canvas.drawCircle(cx, cy, iris, p);
            canvas.drawCircle(cx, cy, iris * 0.78f, p);
            p.setColor(withAlpha(stateColor, 180));
            canvas.drawArc(new RectF(cx - iris, cy - iris, cx + iris, cy + iris),
                    -(now / 20f) % 360f, 58f, false, p);

            drawAperture(canvas, cx, cy, iris, pupil, now, stateColor);
            drawStateMotion(canvas, cx, cy, iris, pupil, outer, now, pulse, stateColor);
            if (frozenFrame != null) drawFrozenMark(canvas, cx, cy, pupil, stateColor);
        }

        private void drawAperture(Canvas canvas, float cx, float cy, float iris, float pupil,
                                  long now, int stateColor) {
            float breathe = uiState == UiState.THINKING
                    ? 0.96f + 0.035f * (float) Math.sin(now / 170.0)
                    : 1f + 0.016f * (float) Math.sin(now / 520.0);
            float flowerScale = iris * 0.94f * breathe / 247f;
            p.setStyle(Paint.Style.FILL);
            // Keep the live camera image primary; the instrument is a translucent HUD.
            p.setColor(Color.argb(52, 7, 6, 4));
            canvas.drawCircle(cx, cy, iris * 0.96f, p);

            // Same three interlocking layers and rotation rates as the boot transition.
            float seconds = now / 1000f;
            StartupRevealView.drawBootLotus(canvas, p, blade, cx, cy, flowerScale, 1f, 155,
                    seconds * 10f, -seconds * 20f, seconds * 30f);

            drawVisionTaijiCore(canvas, cx, cy, flowerScale * 0.85f, 205);
        }

        /** Matches the compass Taiji: warm gold on the left, ink black on the right. */
        private void drawVisionTaijiCore(Canvas canvas, float cx, float cy, float scale, int alpha) {
            float medallion = 112f * scale;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(alpha, 7, 6, 4));
            canvas.drawCircle(cx, cy, medallion, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1.5f));
            p.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha));
            canvas.drawCircle(cx, cy, medallion, p);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
            p.setColor(withAlpha(Ui.COLOR_GOLD, alpha * 6 / 10));
            canvas.drawCircle(cx, cy, medallion * 0.93f, p);

            float radius = 90f * scale;
            int save = canvas.save();
            taijiClip.reset();
            taijiClip.addCircle(cx, cy, radius, Path.Direction.CW);
            canvas.clipPath(taijiClip);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(alpha, 0, 0, 0));
            canvas.drawCircle(cx, cy, radius, p);
            p.setColor(withAlpha(Ui.COLOR_GOLD, alpha));
            // One continuous S path avoids the artificial straight seam from overlapping shapes.
            taijiGold.reset();
            taijiGold.moveTo(cx, cy - radius);
            r.set(cx - radius, cy - radius, cx + radius, cy + radius);
            taijiGold.arcTo(r, -90f, -180f);
            r.set(cx - radius / 2f, cy, cx + radius / 2f, cy + radius);
            taijiGold.arcTo(r, 90f, 180f);
            r.set(cx - radius / 2f, cy - radius, cx + radius / 2f, cy);
            taijiGold.arcTo(r, 90f, -180f);
            taijiGold.close();
            canvas.drawPath(taijiGold, p);
            p.setColor(Color.argb(alpha, 0, 0, 0));
            canvas.drawCircle(cx, cy - radius / 2f, radius * 0.105f, p);
            p.setColor(withAlpha(Ui.COLOR_GOLD, alpha));
            canvas.drawCircle(cx, cy + radius / 2f, radius * 0.105f, p);
            canvas.restoreToCount(save);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1.35f));
            p.setColor(withAlpha(Ui.COLOR_GOLD_DARK, alpha));
            canvas.drawCircle(cx, cy, radius, p);
        }

        private void drawStateMotion(Canvas canvas, float cx, float cy, float iris, float pupil,
                                     float outer, long now, float pulse, int stateColor) {
            p.setStyle(Paint.Style.STROKE);
            if (uiState == UiState.THINKING) {
                int save = canvas.save();
                canvas.rotate((now / 8f) % 360f, cx, cy);
                p.setShader(scanShader);
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 3));
                canvas.drawCircle(cx, cy, iris * 1.08f, p);
                p.setShader(null);
                canvas.restoreToCount(save);
                p.setColor(withAlpha(Ui.COLOR_TEXT, 175));
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
                double a = Math.toRadians((now / 7f) % 360f - 90f);
                canvas.drawLine(cx + (float) Math.cos(a) * pupil,
                        cy + (float) Math.sin(a) * pupil,
                        cx + (float) Math.cos(a) * iris,
                        cy + (float) Math.sin(a) * iris, p);
            } else if (uiState == UiState.SPEAKING) {
                p.setColor(withAlpha(Ui.COLOR_GOLD, 195));
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 2));
                for (int i = 0; i < 3; i++) {
                    float wave = iris + (outer - iris) * ((pulse + i / 3f) % 1f);
                    canvas.drawArc(new RectF(cx - wave, cy - wave, cx + wave, cy + wave),
                            205f, 130f, false, p);
                }
            } else if (uiState == UiState.LISTENING) {
                p.setColor(withAlpha(Ui.COLOR_AETHER, 80 + (int) (pulse * 120)));
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1.5f));
                canvas.drawCircle(cx, cy, iris * (1.04f + pulse * 0.025f), p);
            } else if (uiState == UiState.ERROR) {
                p.setColor(withAlpha(Ui.COLOR_ERROR, 130 + (int) (pulse * 100)));
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 2));
                canvas.drawArc(new RectF(cx - iris * 1.08f, cy - iris * 1.08f,
                        cx + iris * 1.08f, cy + iris * 1.08f), 225f, 90f, false, p);
            } else if (uiState == UiState.WAITING) {
                p.setColor(withAlpha(Ui.COLOR_GOLD_DIM, 120));
                p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
                canvas.drawArc(new RectF(cx - iris * 0.9f, cy - iris * 0.9f,
                        cx + iris * 0.9f, cy + iris * 0.9f),
                        (now / 12f) % 360f, 38f, false, p);
            }
        }

        private void drawFrozenMark(Canvas canvas, float cx, float cy, float pupil, int stateColor) {
            float y = cy - pupil * 1.42f;
            float half = Ui.dpF(VisionActivity.this, 5);
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(stateColor, 220));
            canvas.drawRect(cx - half - Ui.dpF(VisionActivity.this, 3), y - half,
                    cx - Ui.dpF(VisionActivity.this, 3), y + half, p);
            canvas.drawRect(cx + Ui.dpF(VisionActivity.this, 3), y - half,
                    cx + half + Ui.dpF(VisionActivity.this, 3), y + half, p);
        }

        private int stateColor() {
            if (uiState == UiState.LISTENING || uiState == UiState.FROZEN) return Ui.COLOR_AETHER;
            if (uiState == UiState.THINKING) return Ui.COLOR_TEXT;
            if (uiState == UiState.ERROR) return Ui.COLOR_ERROR;
            return Ui.COLOR_GOLD;
        }

        private void drawCaption(Canvas canvas, String text, float cx, float h, float shortSide) {
            textPaint.setColor(Ui.COLOR_TEXT);
            textPaint.setTextSize(Ui.dpF(VisionActivity.this, 13));
            textPaint.setTextAlign(Paint.Align.CENTER);
            float barW = shortSide * 0.69f;
            float padX = Ui.dpF(VisionActivity.this, 14);
            float maxTextW = barW - padX * 2f;
            List<String> lines = captionLines(text, maxTextW, 3);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float lineH = fm.descent - fm.ascent + Ui.dpF(VisionActivity.this, 2);
            float padY = Ui.dpF(VisionActivity.this, 9);
            float barH = padY * 2f + lineH * lines.size();
            float barCy = h - shortSide * 0.225f;
            r.set(cx - barW / 2f, barCy - barH / 2f, cx + barW / 2f, barCy + barH / 2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(194, 8, 7, 5));
            canvas.drawRoundRect(r, Ui.dpF(VisionActivity.this, 6),
                    Ui.dpF(VisionActivity.this, 6), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpF(VisionActivity.this, 1));
            p.setColor(withAlpha(stateColor(), 145));
            canvas.drawRoundRect(r, Ui.dpF(VisionActivity.this, 6),
                    Ui.dpF(VisionActivity.this, 6), p);

            float baseY = r.top + padY - fm.ascent;
            for (String line : lines) {
                canvas.drawText(line, cx, baseY, textPaint);
                baseY += lineH;
            }
        }

        private List<String> captionLines(String source, float maxWidth, int maxLines) {
            List<String> out = new ArrayList<>();
            String rest = source == null ? "" : source.trim();
            while (!rest.isEmpty() && out.size() < maxLines) {
                if (out.size() == maxLines - 1) {
                    out.add(TextUtils.ellipsize(rest, textPaint, maxWidth,
                            TextUtils.TruncateAt.END).toString());
                    break;
                }
                int count = textPaint.breakText(rest, true, maxWidth, null);
                if (count <= 0) count = 1;
                if (count >= rest.length()) {
                    out.add(rest);
                    break;
                }
                int cut = preferredBreak(rest, count);
                out.add(rest.substring(0, cut).trim());
                rest = rest.substring(cut).trim();
            }
            if (out.isEmpty()) out.add("");
            return out;
        }

        private int preferredBreak(String text, int max) {
            int floor = Math.max(1, max / 2);
            for (int i = Math.min(max, text.length() - 1); i >= floor; i--) {
                char c = text.charAt(i - 1);
                if (Character.isWhitespace(c) || c == '，' || c == '。' || c == '；'
                        || c == ',' || c == '.' || c == ';') return i;
            }
            return max;
        }

        private int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
        }
    }
}
