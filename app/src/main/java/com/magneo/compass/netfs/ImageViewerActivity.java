package com.magneo.compass.netfs;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 图片查看器（远程流代理/file://），支持缩放。 */
public class ImageViewerActivity extends com.magneo.compass.BaseActivity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private ImageView iv;
    private float scale = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = getIntent().getStringExtra("url");

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.setClipToOutline(true);
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(v -> {
            scale = scale >= 3f ? 1f : scale + 1f;
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            iv.setImageMatrix(m);
        });
        root.addView(new com.magneo.compass.BackButton(this));
        root.addView(iv);
        setContentView(root);

        exec.execute(() -> {
            try {
                Bitmap bmp = load(url);
                runOnUiThread(() -> iv.setImageBitmap(bmp));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private Bitmap load(String url) throws Exception {
        if (url.startsWith("file://")) {
            return decodeSampled(Uri.parse(url).getPath());
        }
        OkHttpClient c = new OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
        Request req = new Request.Builder().url(url).build();
        try (Response resp = c.newCall(req).execute()) {
            byte[] bytes = resp.body().bytes();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
    }

    private Bitmap decodeSampled(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
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
