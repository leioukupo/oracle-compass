package com.magneo.compass.netfs;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 文本查看器（自动识别 UTF-8/GBK）。 */
public class TextViewerActivity extends com.magneo.compass.BaseActivity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = getIntent().getStringExtra("url");
        final TextView tv = new TextView(this);
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(16);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setPadding(16, 16, 16, 16);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.rgb(10, 10, 10));
        sc.addView(tv);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipToOutline(true);
        wrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        wrap.addView(new com.magneo.compass.BackButton(this));
        wrap.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(wrap);

        exec.execute(() -> {
            try {
                String text = read(url);
                runOnUiThread(() -> tv.setText(text));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String read(String url) throws Exception {
        byte[] bytes;
        if (url.startsWith("file://")) {
            File f = new File(Uri.parse(url).getPath());
            FileInputStream in = new FileInputStream(f);
            bytes = new byte[(int) f.length()];
            int off = 0, n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
            in.close();
        } else {
            OkHttpClient c = new OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
            try (Response resp = c.newCall(new Request.Builder().url(url).build()).execute()) {
                bytes = resp.body().bytes();
            }
        }
        try { return new String(bytes, Charset.forName("UTF-8")); }
        catch (Exception e) { return new String(bytes, Charset.forName("GBK")); }
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
