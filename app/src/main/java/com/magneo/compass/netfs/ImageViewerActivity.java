package com.magneo.compass.netfs;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 图片查看器（远程流代理/file://），支持缩放。
 *  远程图片先落到临时文件再采样解码：避免大图整包读进堆内存导致黑屏/OOM；
 *  无法解码（格式不支持/文件损坏）时给出明确提示，而不是静默黑屏。 */
public class ImageViewerActivity extends com.magneo.compass.BaseActivity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private ImageView iv;
    private TextView loading;
    private float scale = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = getIntent().getStringExtra("url");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        com.magneo.compass.ui.OutlineUtil.oval(root);

        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(v -> {
            scale = scale >= 3f ? 1f : scale + 1f;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            iv.setImageMatrix(m);
        });
        loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextColor(Color.rgb(212, 175, 55));
        loading.setTextSize(16);
        loading.setGravity(Gravity.CENTER);

        FrameLayout area = new FrameLayout(this);
        area.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        area.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(new com.magneo.compass.BackButton(this));
        root.addView(area, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        exec.execute(() -> {
            try {
                final Bitmap bmp = load(url);
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    if (bmp != null) iv.setImageBitmap(bmp);
                });
            } catch (Throwable e) { // 含 OOM：低内存设备大图解码可能直接 OOM，提示而不是崩溃
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
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
        int max = 1600;
        int s = 1;
        while (o.outWidth / s > max || o.outHeight / s > max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeFile(path, o2);
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
