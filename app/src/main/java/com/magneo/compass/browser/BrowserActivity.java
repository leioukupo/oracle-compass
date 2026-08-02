package com.magneo.compass.browser;

import android.app.Activity;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.DownloadListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.Prefs;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 灵镜浏览：圆形视口 WebView 浏览器（书签/历史/下载/UA 切换/SSL 兼容）。 */
public class BrowserActivity extends com.magneo.compass.BaseActivity {

    private WebView web;
    private EditText addr;
    private final List<String> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.magneo.compass.QuitFix.apply(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));

        LinearLayout bar = new LinearLayout(this);
        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setTextColor(Color.rgb(232, 220, 192));
        addr.setHint("输入网址或搜索");
        addr.setHintTextColor(Color.rgb(120, 114, 98));
        addr.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        addr.setPadding(dp(14), dp(6), dp(14), dp(6));
        addr.setOnEditorActionListener((v, action, ev) -> {
            load(addr.getText().toString());
            return true;
        });
        Button go = nav("Go"); go.setOnClickListener(v -> load(addr.getText().toString()));
        bar.addView(addr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(go);
        root.addView(bar);

        LinearLayout bar2 = new LinearLayout(this);
        Button bm = nav("☆"); bm.setOnClickListener(v -> toggleBookmark());
        Button hs = nav("书签"); hs.setOnClickListener(v -> showBookmarks());
        Button ht = nav("历史"); ht.setOnClickListener(v -> showHistory());
        bar2.addView(bm); bar2.addView(hs); bar2.addView(ht);
        root.addView(bar2);

        web = new WebView(this);
        web.setClipToOutline(true);
        web.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        if (Prefs.getB(this, Prefs.K_NO_IMAGES, false)) ws.setBlockNetworkImage(true);
        if (Prefs.getB(this, Prefs.K_UA_DESKTOP, false)) {
            ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                if (Prefs.getB(BrowserActivity.this, Prefs.K_IGNORE_SSL, false)) handler.proceed();
                else handler.cancel();
            }
            @Override public void onPageFinished(WebView v, String url) {
                addr.setText(url);
                addHistory(url);
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String contentDisposition, String mimetype, long len) {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessName(contentDisposition, url));
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    dm.enqueue(r);
                    Toast.makeText(BrowserActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(BrowserActivity.this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        String start = getIntent().getStringExtra("url");
        if (start == null || start.isEmpty()) start = "https://www.bing.com/";
        load(start);
        history.addAll(loadHistory());
    }

    private Button nav(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.rgb(232, 220, 192));
        b.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void load(String input) {
        String url = input.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) url = "https://" + url;
            else url = String.format(Prefs.get(this, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"),
                    Uri.encode(url));
        }
        addr.setText(url);
        web.loadUrl(url);
    }

    private static String guessName(String cd, String url) {
        if (cd != null && cd.contains("filename=")) {
            String[] p = cd.split("filename=");
            if (p.length > 1) return p[1].replace("\"", "").trim();
        }
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        String n = u.substring(u.lastIndexOf('/') + 1);
        return n.isEmpty() ? "download" : n;
    }

    private void toggleBookmark() {
        List<String> bm = loadBookmarks();
        String cur = web.getUrl();
        if (cur == null) return;
        if (bm.contains(cur)) bm.remove(cur);
        else bm.add(cur);
        saveBookmarks(bm);
        Toast.makeText(this, bm.contains(cur) ? "已添加书签" : "已移除书签", Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        List<String> bm = loadBookmarks();
        if (bm.isEmpty()) { Toast.makeText(this, "暂无书签", Toast.LENGTH_SHORT).show(); return; }
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("书签");
        for (String s : bm) d.item(shorten(s), () -> load(s));
        d.cancel().show();
    }

    private void showHistory() {
        if (history.isEmpty()) { Toast.makeText(this, "暂无历史", Toast.LENGTH_SHORT).show(); return; }
        List<String> rev = new ArrayList<>(history);
        java.util.Collections.reverse(rev);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("历史");
        for (String s : rev) d.item(shorten(s), () -> load(s));
        d.cancel().show();
    }

    private String shorten(String u) {
        return u.length() > 26 ? u.substring(0, 26) + "…" : u;
    }

    private void addHistory(String url) {
        if (url == null || url.startsWith("about:")) return;
        history.remove(url);
        history.add(url);
        while (history.size() > 50) history.remove(0);
        saveHistory(history);
    }

    private List<String> loadBookmarks() { return fromJson(Prefs.get(this, Prefs.K_BOOKMARKS, "[]")); }
    private void saveBookmarks(List<String> l) { Prefs.put(this, Prefs.K_BOOKMARKS, toJson(l)); }
    private List<String> loadHistory() { return fromJson(Prefs.get(this, Prefs.K_HISTORY, "[]")); }
    private void saveHistory(List<String> l) { Prefs.put(this, Prefs.K_HISTORY, toJson(l)); }

    private static List<String> fromJson(String s) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {}
        return l;
    }

    private static String toJson(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        return a.toString();
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
