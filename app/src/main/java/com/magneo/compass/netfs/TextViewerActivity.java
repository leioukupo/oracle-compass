package com.magneo.compass.netfs;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.ClipboardManager;
import android.text.TextUtils;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 文本查看器：沉浸阅读 + 字号/换行/复制控制。 */
public class TextViewerActivity extends com.magneo.compass.BaseActivity {
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tv;
    private TextView titleView;
    private TextView statusView;
    private LinearLayout bottomBar;
    private String url;
    private String title = "";
    private long size = 0;
    private String fullText = "";
    private boolean wrap = true;
    private float textSp = 16f;
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

    @SuppressLint("WrongConstant")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra("url");
        title = safe(getIntent().getStringExtra("title"));
        if (title.isEmpty()) title = safe(getIntent().getStringExtra("name"));
        size = getIntent().getLongExtra("size", 0);
        if (title.isEmpty() && url != null) title = fileName(url);

        FrameLayoutCompat root = new FrameLayoutCompat(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Ui.COLOR_TEXT);
        titleView.setTextSize(13);
        titleView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        titleView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        titleView.setText(title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.48f), Ui.dp(this, 34));
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.setMargins(0, Ui.dp(this, 36), 0, Ui.dp(this, 8));
        shell.addView(titleView, tlp);

        statusView = new TextView(this);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Ui.COLOR_TEXT_DIM);
        statusView.setTextSize(12);
        statusView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        statusView.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        statusView.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28));
        slp.setMargins(Ui.dp(this, 28), 0, Ui.dp(this, 28), Ui.dp(this, 8));
        shell.addView(statusView, slp);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setFillViewport(true);
        tv = new TextView(this);
        tv.setTextColor(Ui.COLOR_TEXT);
        tv.setTextSize(textSp);
        tv.setLineSpacing(Ui.dp(this, 4), 1.08f);
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 22));
        tv.setTextIsSelectable(true);
        tv.setHorizontallyScrolling(false);
        if (Build.VERSION.SDK_INT >= 23) {
            tv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            tv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        sc.addView(tv, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cvp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cvp.setMargins(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 8));
        shell.addView(sc, cvp);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        bottomBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        Button dec = toolButton("A-");
        Button inc = toolButton("A+");
        Button wrapBtn = toolButton("换行");
        Button copy = toolButton("复制");
        Button reload = toolButton("↻");
        bottomBar.addView(dec, toolLpWide());
        bottomBar.addView(inc, toolLpWide());
        bottomBar.addView(wrapBtn, toolLpWide());
        bottomBar.addView(copy, toolLpWide());
        bottomBar.addView(reload, toolLp());
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
        bpl.gravity = Gravity.CENTER_HORIZONTAL;
        bpl.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 58));
        shell.addView(bottomBar, bpl);
        sc.setOnTouchListener((v, ev) -> {
            showChromeTransient();
            return false;
        });
        root.addView(shell);
        setContentView(root);

        dec.setOnClickListener(v -> {
            textSp = Math.max(12f, textSp - 1f);
            tv.setTextSize(textSp);
            showStatus("字号 " + (int) textSp);
        });
        inc.setOnClickListener(v -> {
            textSp = Math.min(24f, textSp + 1f);
            tv.setTextSize(textSp);
            showStatus("字号 " + (int) textSp);
        });
        wrapBtn.setOnClickListener(v -> {
            wrap = !wrap;
            tv.setHorizontallyScrolling(!wrap);
            tv.setSingleLine(!wrap);
            showStatus(wrap ? "已换行" : "单行浏览");
        });
        copy.setOnClickListener(v -> copyText());
        reload.setOnClickListener(v -> loadText());

        loadText();
    }

    private void loadText() {
        showStatus("读取中…");
        exec.execute(() -> {
            try {
                String text = read(url);
                fullText = text == null ? "" : text;
                runOnUiThread(() -> {
                    tv.setText(fullText);
                    updateTitle();
                    hideStatusSoon(1200);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tv.setText("");
                    showStatus("读取失败: " + e.getMessage());
                    Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String read(String u) throws Exception {
        byte[] bytes;
        if (u != null && u.startsWith("file://")) {
            File f = new File(Uri.parse(u).getPath());
            try (FileInputStream in = new FileInputStream(f)) {
                bytes = readAll(in, (int) Math.min(f.length(), 2 * 1024 * 1024L));
            }
        } else {
            OkHttpClient c = new OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
            try (Response resp = c.newCall(new Request.Builder().url(u).build()).execute()) {
                if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
                try (InputStream in = resp.body().byteStream()) {
                    bytes = readAll(in, 1024 * 1024 * 2);
                }
            }
        }
        String text = decode(bytes);
        if (text.length() > 200_000) {
            text = text.substring(0, 200_000) + "\n\n[已截断，长文本可继续复制到外部查看]";
        }
        return text;
    }

    private byte[] readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                out.write(buf, 0, n - (total - maxBytes));
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private String decode(byte[] bytes) {
        if (bytes == null) return "";
        int off = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            off = 3;
        }
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        }
        catch (Exception ignored) {}
        try { return new String(bytes, off, bytes.length - off, Charset.forName("GBK")); }
        catch (Exception ignored) {}
        return new String(bytes, off, bytes.length - off, StandardCharsets.ISO_8859_1);
    }

    private void copyText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setText(fullText == null ? "" : fullText);
            showStatus("已复制");
        } catch (Exception e) {
            showStatus("复制失败");
        }
    }

    private void updateTitle() {
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append(title);
        if (size > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(humanSize(size));
        }
        titleView.setText(sb.length() == 0 ? "文本" : sb.toString());
    }

    private String humanSize(long s) {
        if (s < 1024) return s + " B";
        if (s < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", s / 1024f);
        if (s < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", s / 1024f / 1024f);
        return String.format(java.util.Locale.US, "%.1f GB", s / 1024f / 1024f / 1024f);
    }

    private void showStatus(String s) {
        statusView.setText(s == null ? "" : s);
        statusView.setVisibility(View.VISIBLE);
        showChromeTransient();
    }

    private void hideStatusSoon(long ms) {
        ui.removeCallbacks(hideStatusTask);
        ui.removeCallbacks(hideBarTask);
        if (ms > 0) {
            ui.postDelayed(hideStatusTask, ms);
            ui.postDelayed(hideBarTask, ms + 400);
        }
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }

    private String fileName(String u) {
        try {
            String p = Uri.parse(u).getPath();
            if (p == null) return u;
            int i = p.lastIndexOf('/');
            return i >= 0 ? p.substring(i + 1) : p;
        } catch (Exception e) {
            return u;
        }
    }

    private Button toolButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        Ui.styleIconButton(b);
        b.setTextSize(label.length() > 1 ? 12 : 15);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void showChromeTransient() {
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        hideStatusSoon(1800);
    }

    private LinearLayout.LayoutParams toolLpWide() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 34));
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return lp;
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return lp;
    }

    @Override protected void onDestroy() {
        exec.shutdownNow();
        ui.removeCallbacks(hideStatusTask);
        ui.removeCallbacks(hideBarTask);
        super.onDestroy();
    }

    /** 简单兼容旧代码的帧布局包裹器，避免这里再引入一堆局部工具类。 */
    private static class FrameLayoutCompat extends android.widget.FrameLayout {
        FrameLayoutCompat(android.content.Context c) { super(c); }

        static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
            LayoutParams(int w, int h, int gravity) {
                super(w, h, gravity);
            }
        }
    }
}
