package com.magneo.compass.vision;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.Prefs;
import com.magneo.compass.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 灵眼：直接从 CameraStreamService 取 NV21 帧转 Bitmap 显示，绕过 RTSP 回环播放。
 * 原因：MTK 硬解对 RTP H.264 的 NAL 封装支持不佳（无 start code prefix），导致 MediaPlayer 解码出错、绿屏/黑屏。
 */
public class VisionActivity extends com.magneo.compass.BaseActivity {

    private TextView out;
    private boolean running = true;
    private final List<LlmClient.Msg> history = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    // 最新 NV21 帧
    private volatile byte[] lastNv21;
    private volatile int camW, camH;

    // 直接渲染 View
    private FrameView frameView;

    private final com.magneo.compass.cam.CameraStreamService.FrameListener fl =
            new com.magneo.compass.cam.CameraStreamService.FrameListener() {
        @Override public void onFrame(byte[] nv21, int w, int h) {
            lastNv21 = nv21.clone();
            camW = w; camH = h;
            frameView.postInvalidate();
        }
    };

    // 渲染线程（NV21 -> Bitmap）
    private Thread renderThread;
    private volatile boolean rendering = false;

    /** 自定义 View：画最新一帧 Bitmap */
    private class FrameView extends View {
        private Bitmap bmp;
        private final Paint paint = new Paint();
        public FrameView(android.content.Context ctx) {
            super(ctx);
            paint.setFilterBitmap(true);
            setBackgroundColor(Color.BLACK);
        }
        @Override protected void onDraw(Canvas canvas) {
            Bitmap b = bmp;
            if (b != null && !b.isRecycled()) {
                // 旋转后的竖图 center-crop 填满正方形视图
                int vw = getWidth(), vh = getHeight();
                int bw = b.getWidth(), bh = b.getHeight();
                float scale = Math.max((float)vw / bw, (float)vh / bh);
                int dw = (int)(bw * scale), dh = (int)(bh * scale);
                int dx = (vw - dw) / 2, dy = (vh - dh) / 2;
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(b, null, new Rect(dx, dy, dx + dw, dy + dh), paint);
            } else {
                canvas.drawColor(Color.BLACK);
            }
        }
        void setBitmap(Bitmap b) {
            Bitmap old = bmp;
            bmp = b;
            if (old != null && !old.isRecycled() && old != b) old.recycle();
        }
    }

    private final Runnable captureLoop = new Runnable() {
        @Override public void run() {
            if (running) captureNow();
            int interval = Prefs.getI(VisionActivity.this, Prefs.K_VISION_INTERVAL, 2);
            long b = getBatteryLevel();
            if (b >= 0 && b < 20) interval = Math.max(interval, 5);
            h.postDelayed(this, Math.max(1, interval) * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new com.magneo.compass.BackButton(this));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int availH = sh - com.magneo.compass.ui.Ui.dp(this, 96) - com.magneo.compass.ui.Ui.dp(this, 84);
        int R = (int) com.magneo.compass.ui.RoundScreen.R(sw, sh);
        int sizeV = Math.min(availH, (int) (R * Math.sqrt(2)));
        frameView = new FrameView(this);
        com.magneo.compass.ui.OutlineUtil.oval(frameView);
        root.addView(frameView, new LinearLayout.LayoutParams(sizeV, sizeV, Gravity.CENTER));

        out = new TextView(this);
        out.setTextColor(Color.rgb(232, 220, 192));
        out.setTextSize(14);
        out.setPadding(com.magneo.compass.ui.Ui.dp(this, 8), com.magneo.compass.ui.Ui.dp(this, 4),
                com.magneo.compass.ui.Ui.dp(this, 8), com.magneo.compass.ui.Ui.dp(this, 4));
        ScrollView sc = new ScrollView(this);
        sc.addView(out, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底带
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER_HORIZONTAL);
        Button more = new Button(this); more.setText("⋯");
        more.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        more.setTextColor(Color.rgb(232, 220, 192));
        int tb = com.magneo.compass.ui.Ui.dp(this, 44);
        more.setLayoutParams(new LinearLayout.LayoutParams(tb, tb));
        more.setOnClickListener(v -> {
            com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("灵眼");
            d.item(running ? "暂停" : "继续", () -> running = !running);
            d.item("现在问", this::captureNow);
            d.cancel().show();
        });
        btns.addView(more);
        root.addView(btns);

        setContentView(root);
        h.postDelayed(captureLoop, 2500);

        startRenderThread();
    }

    /** 启动渲染线程：循环把最新 NV21 转 Bitmap 刷新到 FrameView。 */
    private void startRenderThread() {
        rendering = true;
        renderThread = new Thread(() -> {
            while (rendering) {
                try {
                    byte[] nv21 = lastNv21;
                    int w = camW, hh = camH;
                    if (nv21 != null && w > 0 && hh > 0) {
                        // 整帧 NV21 -> JPEG -> Bitmap
                        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, hh, null);
                        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                        yuv.compressToJpeg(new Rect(0, 0, w, hh), 50, jpg);
                        byte[] jpgBytes = jpg.toByteArray();
                        Bitmap b = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.length);
                        if (b != null) {
                            // 顺时针旋转90度修正传感器方向
                            android.graphics.Matrix m = new android.graphics.Matrix();
                            m.postRotate(90);
                            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                            if (rotated != b) b.recycle();
                            final Bitmap fb = rotated;
                            runOnUiThread(() -> {
                                if (!rendering) return;
                                frameView.setBitmap(fb);
                                frameView.invalidate();
                            });
                        }
                    }
                    Thread.sleep(40); // ~25fps
                } catch (Throwable ignored) {
                    try { Thread.sleep(100); } catch (Exception ignored2) {}
                }
            }
        }, "vision-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private int getBatteryLevel() {
        android.content.Intent i = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        return i == null ? -1 : i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        com.magneo.compass.cam.CameraStreamService.start(getApplicationContext());
        com.magneo.compass.cam.CameraStreamService.setFrameListener(fl);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        com.magneo.compass.cam.CameraStreamService.setFrameListener(null);
        com.magneo.compass.cam.CameraStreamService.stop(getApplicationContext());
    }

    @Override
    protected void onDestroy() {
        rendering = false;
        h.removeCallbacksAndMessages(null);
        if (renderThread != null) renderThread.interrupt();
        super.onDestroy();
    }

    /** 抓当前帧送 LLM。 */
    private void captureNow() {
        if (!running) return;
        byte[] nv21 = lastNv21;
        int w = camW, hh = camH;
        if (nv21 == null || w <= 0 || hh <= 0) return;
        try {
            // 中心裁切正方形（视觉模型偏好正方形）
            int size = Math.min(w, hh);
            int sx = (w - size) / 2, sy = (hh - size) / 2;
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, hh, null);
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(sx, sy, sx + size, sy + size), 60, jpg);
            Bitmap full = BitmapFactory.decodeByteArray(jpg.toByteArray(), 0, jpg.size());
            if (full == null) return;
            Bitmap scaled = scaleDown(full, 640);
            ByteArrayOutputStream jpgOut = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, jpgOut);
            String b64 = Base64.encodeToString(jpgOut.toByteArray(), Base64.NO_WRAP);
            sendToLlm(b64);
        } catch (Exception e) {
            toast("视觉错误: " + e.getMessage());
        }
    }

    private void sendToLlm(String b64) {
        if (history.size() >= 3) history.remove(0);
        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("system",
                Prefs.get(this, Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION)));
        msgs.addAll(history);
        msgs.add(new LlmClient.Msg("user", "观察当前画面。", b64));
        if (Prefs.get(this, Prefs.K_API_KEY, "").isEmpty()) {
            out.append("\n[提示] 未配置 API Key，请在设置页填写后重试\n");
            return;
        }
        out.setText("灵眼凝视中…\n");
        new LlmClient(this).chat(msgs, true, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) { runOnUiThread(() -> out.append(s)); }
            @Override public void onDone(String full) {
                runOnUiThread(() -> {
                    history.add(new LlmClient.Msg("assistant", full));
                    out.append("\n——\n");
                });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> out.append("\n[错误] " + msg + "\n"));
            }
        });
    }

    private Bitmap scaleDown(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight();
        float scale = Math.min(1f, (float) max / Math.max(w, h));
        if (scale >= 1f) return b;
        return Bitmap.createScaledBitmap(b, (int) (w * scale), (int) (h * scale), true);
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}