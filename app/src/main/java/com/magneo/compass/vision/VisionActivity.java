package com.magneo.compass.vision;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.Prefs;
import com.magneo.compass.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 灵眼：伪实时视觉——周期抓帧送视觉模型，流式显示描述，保留最近 3 条摘要作上下文。 */
public class VisionActivity extends com.magneo.compass.BaseActivity implements Camera.PreviewCallback, SurfaceHolder.Callback {

    private Camera camera;
    private SurfaceView surface;
    private TextView out;
    private boolean running = true;
    private volatile byte[] lastFrame;
    private volatile int frameW, frameH;
    private final List<String> context = new ArrayList<>(3);
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<LlmClient.Msg> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new com.magneo.compass.BackButton(this));

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.setClipToOutline(true);
        surface.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        FrameLayout frame = new FrameLayout(this);
        int size = Math.min(getResources().getDisplayMetrics().widthPixels, 400);
        frame.addView(surface, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        frame.setClipChildren(true);
        root.addView(frame, new LinearLayout.LayoutParams(size, size, Gravity.CENTER));

        out = new TextView(this);
        out.setTextColor(Color.rgb(232, 220, 192));
        out.setTextSize(14);
        out.setPadding(16, 8, 16, 8);
        ScrollView sc = new ScrollView(this);
        sc.addView(out, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout btns = new LinearLayout(this);
        Button pause = new Button(this); pause.setText("暂停/继续");
        pause.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        pause.setTextColor(Color.rgb(232, 220, 192));
        pause.setOnClickListener(v -> running = !running);
        Button ask = new Button(this); ask.setText("现在问");
        ask.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        ask.setTextColor(Color.rgb(232, 220, 192));
        ask.setOnClickListener(v -> captureNow());
        btns.addView(pause);
        btns.addView(ask);
        root.addView(btns);

        setContentView(root);
        h.postDelayed(captureLoop, 1000);
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

    private int getBatteryLevel() {
        android.content.Intent i = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        return i == null ? -1 : i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
    }

    private void captureNow() {
        if (camera == null || lastFrame == null) return;
        try {
            byte[] frame = lastFrame;
            int w = frameW, hh = frameH;
            Bitmap bmp = yuvToBitmap(frame, w, hh);
            if (bmp == null) return;
            Bitmap scaled = scaleDown(bmp, 640);
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, jpg);
            String b64 = Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP);
            sendToLlm(b64);
        } catch (Exception e) {
            toast("视觉错误: " + e.getMessage());
        }
    }

    private void sendToLlm(String b64) {
        if (history.size() >= 3) {
            history.remove(0);
        }
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
            @Override public void onDelta(String s) {
                runOnUiThread(() -> out.append(s));
            }
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

    private Bitmap yuvToBitmap(byte[] nv21, int w, int h) {
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        if (!yuv.compressToJpeg(new Rect(0, 0, w, h), 80, jpg)) return null;
        byte[] j = jpg.toByteArray();
        return BitmapFactory.decodeByteArray(j, 0, j.length);
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

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (data != null) {
            lastFrame = data;
            Camera.Size s = cam.getParameters().getPreviewSize();
            frameW = s.width;
            frameH = s.height;
        }
        if (camera != null) camera.addCallbackBuffer(data);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) { openCamera(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseCamera(); }

    private void openCamera() {
        releaseCamera();
        try {
            camera = Camera.open(0);
            Camera.Parameters p = camera.getParameters();
            Camera.Size ps = bestPreviewSize(p);
            p.setPreviewSize(ps.width, ps.height);
            p.setPreviewFormat(ImageFormat.NV21);
            p.setJpegQuality(60);
            camera.setParameters(p);
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallbackWithBuffer(this);
            int bufSize = ps.width * ps.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.startPreview();
        } catch (Exception e) {
            toast("相机打开失败: " + e.getMessage());
        }
    }

    private Camera.Size bestPreviewSize(Camera.Parameters p) {
        Camera.Size best = p.getPreviewSize();
        int bestScore = Integer.MAX_VALUE;
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            int score = Math.abs(s.width * s.height - 640 * 480);
            if (score < bestScore && s.width <= 1280) { bestScore = score; best = s; }
        }
        return best;
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Throwable ignored) {}
            camera.setPreviewCallbackWithBuffer(null);
            camera.release();
            camera = null;
        }
    }

    @Override protected void onPause() {
        releaseCamera();
        running = false;
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        if (surface != null && surface.getHolder() != null && surface.getHolder().getSurface().isValid()) openCamera();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releaseCamera();
        super.onDestroy();
    }
}
