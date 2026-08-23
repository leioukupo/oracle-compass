package com.magneo.compass.netfs;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 图片查看器：沉浸预览 + 双指缩放/拖动 + 同目录翻页。 */
public class ImageViewerActivity extends com.magneo.compass.BaseActivity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> urls = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Matrix baseMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private ImageView iv;
    private TextView loadingView;
    private TextView titleView;
    private TextView statusView;
    private LinearLayout bottomBar;
    private Button prevBtn;
    private Button fitBtn;
    private Button nextBtn;
    private Button moreBtn;
    private View content;
    private ScaleGestureDetector scaleDetector;
    private String curUrl = "";
    private String curName = "";
    private long curSize = 0;
    private int index = 0;
    private Bitmap bitmap;
    private float scale = 1f;
    private float minScale = 1f;
    private float maxScale = 3f;
    private float lastX;
    private float lastY;
    private long lastTapAt = 0;
    private boolean dragging = false;
    private final Runnable hideStatusTask = new Runnable() {
        @Override public void run() {
            if (statusView != null) statusView.setVisibility(View.GONE);
        }
    };
    private final Runnable hideBarTask = new Runnable() {
        @Override public void run() {
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        parseIntent();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.TRANSPARENT);

        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Ui.COLOR_TEXT);
        titleView.setTextSize(13);
        titleView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        titleView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.48f),
                Ui.dp(this, 34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleLp.topMargin = Ui.dp(this, 38);
        shell.addView(titleView, titleLp);

        statusView = new TextView(this);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Ui.COLOR_TEXT_DIM);
        statusView.setTextSize(12);
        statusView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        statusView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        statusView.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.78f),
                Ui.dp(this, 30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.bottomMargin = Ui.dp(this, 98);
        shell.addView(statusView, statusLp);

        content = buildImageArea();
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cp.topMargin = Ui.dp(this, 72);
        cp.bottomMargin = Ui.dp(this, 86);
        shell.addView(content, cp);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        prevBtn = toolButton("‹");
        fitBtn = toolButton("适配");
        nextBtn = toolButton("›");
        moreBtn = toolButton("⋯");
        bottomBar.addView(prevBtn, toolLp());
        bottomBar.addView(fitBtn, toolLpWide());
        bottomBar.addView(nextBtn, toolLp());
        bottomBar.addView(moreBtn, toolLp());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 50),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = Ui.dp(this, 26);
        shell.addView(bottomBar, blp);

        root.addView(shell);
        setContentView(root);

        wireControls();
        loadCurrent();
    }

    private View buildImageArea() {
        FrameLayout area = new FrameLayout(this);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        iv.setBackgroundColor(Color.TRANSPARENT);
        area.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        loadingView = new TextView(this);
        loadingView.setText("加载中…");
        loadingView.setTextColor(Ui.COLOR_GOLD);
        loadingView.setTextSize(16);
        loadingView.setGravity(Gravity.CENTER);
        area.addView(loadingView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        iv.setOnTouchListener(new View.OnTouchListener() {
            private final float[] start = new float[2];
            @Override public boolean onTouch(View v, MotionEvent ev) {
                scaleDetector.onTouchEvent(ev);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = ev.getX();
                        lastY = ev.getY();
                        start[0] = lastX;
                        start[1] = lastY;
                        dragging = false;
                        showChromeTransient();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && bitmap != null) {
                            float dx = ev.getX() - lastX;
                            float dy = ev.getY() - lastY;
                            if (Math.abs(ev.getX() - start[0]) > Ui.dp(ImageViewerActivity.this, 4)
                                    || Math.abs(ev.getY() - start[1]) > Ui.dp(ImageViewerActivity.this, 4)) {
                                dragging = true;
                            }
                            if (dragging) {
                                drawMatrix.postTranslate(dx, dy);
                                applyMatrix();
                            }
                        }
                        lastX = ev.getX();
                        lastY = ev.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        float totalDx = ev.getX() - start[0];
                        float totalDy = ev.getY() - start[1];
                        if (Math.abs(totalDx) > Ui.dp(ImageViewerActivity.this, 76)
                                && Math.abs(totalDy) < Ui.dp(ImageViewerActivity.this, 64)
                                && Math.abs(scale - minScale) < 0.04f) {
                            switchImage(totalDx < 0 ? 1 : -1);
                            break;
                        }
                        if (!dragging && !scaleDetector.isInProgress()) {
                            if (Math.abs(totalDx) < Ui.dp(ImageViewerActivity.this, 8)
                                    && Math.abs(totalDy) < Ui.dp(ImageViewerActivity.this, 8)) {
                                long now = android.os.SystemClock.uptimeMillis();
                                if (now - lastTapAt < 320) {
                                    toggleZoom();
                                    lastTapAt = 0;
                                } else {
                                    lastTapAt = now;
                                    toggleChrome();
                                }
                            } else {
                                snapIfNeeded();
                            }
                        } else {
                            snapIfNeeded();
                        }
                        break;
                }
                return true;
            }
        });

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (bitmap == null) return false;
                scale *= detector.getScaleFactor();
                scale = Math.max(minScale, Math.min(scale, maxScale));
                applyBaseMatrix();
                showChromeTransient();
                return true;
            }
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                dragging = false;
                return true;
            }
        });

        area.setOnClickListener(v -> toggleChrome());
        area.setOnLongClickListener(v -> {
            fitImage();
            return true;
        });
        return area;
    }

    private void parseIntent() {
        String u = getIntent().getStringExtra("url");
        if (u != null) curUrl = u;
        curName = safeText(getIntent().getStringExtra("title"));
        if (curName.isEmpty()) curName = safeText(getIntent().getStringExtra("name"));
        curSize = getIntent().getLongExtra("size", 0);
        ArrayList<String> uList = getIntent().getStringArrayListExtra("urls");
        ArrayList<String> nList = getIntent().getStringArrayListExtra("names");
        index = getIntent().getIntExtra("index", 0);
        if (uList != null) urls.addAll(uList);
        if (nList != null) names.addAll(nList);
        if (curUrl.isEmpty() && !urls.isEmpty()) curUrl = urls.get(Math.max(0, Math.min(index, urls.size() - 1)));
        if (curName.isEmpty() && !names.isEmpty() && index >= 0 && index < names.size()) curName = names.get(index);
        if (curName.isEmpty() && !curUrl.isEmpty()) curName = fileNameFromUrl(curUrl);
    }

    private String safeText(String s) { return s == null ? "" : s.trim(); }

    private String fileNameFromUrl(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return url;
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        } catch (Exception e) {
            return url;
        }
    }

    private void wireControls() {
        prevBtn.setOnClickListener(v -> switchImage(-1));
        nextBtn.setOnClickListener(v -> switchImage(1));
        fitBtn.setOnClickListener(v -> fitImage());
        moreBtn.setOnClickListener(v -> showMore());
        updateChrome();
    }

    private void loadCurrent() {
        if (curUrl == null || curUrl.isEmpty()) return;
        updateChrome();
        if (loadingView != null) {
            loadingView.setText("加载中…");
            loadingView.setVisibility(View.VISIBLE);
        }
        showStatus("加载中…");
        exec.execute(() -> {
            try {
                final Bitmap bmp = load(curUrl);
                runOnUiThread(() -> {
                    bitmap = bmp;
                    iv.setImageBitmap(bitmap);
                    loadingView.setVisibility(View.GONE);
                    iv.post(this::fitImage);
                    hideStatusSoon(1200);
                });
            } catch (Throwable e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    bitmap = null;
                    loadingView.setVisibility(View.GONE);
                    statusView.setText("无法显示：" + msg);
                    statusView.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "无法显示该图片：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Bitmap load(String url) throws Exception {
        if (url.startsWith("file://")) {
            return decodeSampled(Uri.parse(url).getPath());
        }
        File tmp = File.createTempFile("img_", ".img", getCacheDir());
        try {
            OkHttpClient c = new OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
            Request req = new Request.Builder().url(url).build();
            try (Response resp = c.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
                try (InputStream in = resp.body().byteStream();
                     FileOutputStream fo = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                }
            }
            return decodeSampled(tmp.getAbsolutePath());
        } finally {
            tmp.delete();
        }
    }

    private Bitmap decodeSampled(String path) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            throw new IOException("格式不支持或文件损坏（Android 5.1 不支持 HEIC 等新格式）");
        }
        int max = 1800;
        int s = 1;
        while (o.outWidth / s > max || o.outHeight / s > max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bmp = BitmapFactory.decodeFile(path, o2);
        if (bmp == null) throw new IOException("解码失败");
        return bmp;
    }

    private void fitImage() {
        if (bitmap == null || iv.getWidth() == 0 || iv.getHeight() == 0) return;
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        minScale = Math.min((float) iv.getWidth() / bw, (float) iv.getHeight() / bh);
        maxScale = Math.max(minScale * 4f, 3f);
        scale = Math.max(minScale, Math.min(scale, maxScale));
        applyBaseMatrix();
        showStatus(curLabel());
        hideStatusSoon(1800);
    }

    private void applyBaseMatrix() {
        if (bitmap == null || iv.getWidth() == 0 || iv.getHeight() == 0) return;
        baseMatrix.reset();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scaledW = bw * scale;
        float scaledH = bh * scale;
        float dx = (iv.getWidth() - scaledW) / 2f;
        float dy = (iv.getHeight() - scaledH) / 2f;
        baseMatrix.postScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);
        drawMatrix.set(baseMatrix);
        applyMatrix();
    }

    private void applyMatrix() {
        iv.setImageMatrix(drawMatrix);
    }

    private void snapIfNeeded() {
        if (bitmap == null) return;
        // 简单收束：确保内容不彻底飞出视口
        float[] v = new float[9];
        drawMatrix.getValues(v);
        float transX = v[Matrix.MTRANS_X];
        float transY = v[Matrix.MTRANS_Y];
        float scaleX = v[Matrix.MSCALE_X];
        float scaledW = bitmap.getWidth() * scaleX;
        float scaledH = bitmap.getHeight() * scaleX;
        float minX = Math.min(0, iv.getWidth() - scaledW);
        float minY = Math.min(0, iv.getHeight() - scaledH);
        if (transX > 0) drawMatrix.postTranslate(-transX, 0);
        if (transY > 0) drawMatrix.postTranslate(0, -transY);
        if (transX < minX) drawMatrix.postTranslate(minX - transX, 0);
        if (transY < minY) drawMatrix.postTranslate(0, minY - transY);
        applyMatrix();
    }

    private void switchImage(int delta) {
        if (urls.isEmpty()) {
            showStatus(delta < 0 ? "上一张不可用" : "下一张不可用");
            return;
        }
        int next = index + delta;
        if (next < 0 || next >= urls.size()) {
            showStatus(delta < 0 ? "已经是第一张" : "已经是最后一张");
            return;
        }
        index = next;
        curUrl = urls.get(index);
        curName = names.size() > index ? names.get(index) : fileNameFromUrl(curUrl);
        curSize = 0;
        bitmap = null;
        scale = 1f;
        drawMatrix.reset();
        updateChrome();
        loadCurrent();
    }

    private void showMore() {
        hideStatusSoon(0);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title(curName);
        d.item("适配到屏幕", this::fitImage);
        d.item(scale > minScale + 0.05f ? "恢复 1x" : "放大 2x", this::toggleZoom);
        d.item("重新加载", this::loadCurrent);
        d.cancel().show();
    }

    private void toggleZoom() {
        if (bitmap == null) return;
        scale = scale > minScale + 0.05f ? minScale : Math.min(maxScale, minScale * 2f);
        applyBaseMatrix();
        showStatus(scale <= minScale + 0.05f ? "已适配" : "2x");
        hideStatusSoon(1200);
    }

    private void updateChrome() {
        titleView.setText(curName.isEmpty() ? "图片" : curName);
    }

    private String curLabel() {
        StringBuilder sb = new StringBuilder();
        if (!curName.isEmpty()) sb.append(curName);
        if (curSize > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(humanSize(curSize));
        }
        if (urls.size() > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(index + 1).append("/").append(urls.size());
        }
        return sb.toString();
    }

    private String humanSize(long size) {
        if (size <= 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", size / 1024f);
        if (size < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", size / 1024f / 1024f);
        return String.format(java.util.Locale.US, "%.1f GB", size / 1024f / 1024f / 1024f);
    }

    private void showStatus(String s) {
        statusView.setText(s == null ? "" : s);
        statusView.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
    }

    private void hideStatusSoon(long ms) {
        ui.removeCallbacks(hideStatusTask);
        ui.removeCallbacks(hideBarTask);
        if (ms > 0) {
            ui.postDelayed(hideStatusTask, ms);
            ui.postDelayed(hideBarTask, ms + 300);
        }
    }

    private void toggleChrome() {
        boolean visible = bottomBar.getVisibility() != View.VISIBLE;
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) showStatus(curLabel());
        else statusView.setVisibility(View.GONE);
    }

    private void showChromeTransient() {
        if (bottomBar.getVisibility() != View.VISIBLE) bottomBar.setVisibility(View.VISIBLE);
        hideStatusSoon(1800);
    }

    private Button toolButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        Ui.styleIconButton(b);
        b.setTextSize(18);
        b.setPadding(0, 0, 0, Ui.dp(this, 2));
        return b;
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams toolLpWide() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return lp;
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
