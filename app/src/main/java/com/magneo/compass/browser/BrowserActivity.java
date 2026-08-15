package com.magneo.compass.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.magneo.compass.Prefs;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 灵镜浏览：圆形视口 WebView 浏览器（书签/历史/下载/UA 切换/SSL 兼容）。 */
public class BrowserActivity extends com.magneo.compass.BaseActivity {

    private WebView web;
    private CircleBrowserLayout circleBar;
    private final List<String> history = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.magneo.compass.QuitFix.apply(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int minSide = Math.min(sw, sh);
        int R = minSide / 2;

        // WebView 占满中心（圆形遮罩）
        web = new WebView(this);
        com.magneo.compass.ui.OutlineUtil.oval(web);
        int webSize = (int) (minSide * 0.82f);
        root.addView(web, new FrameLayout.LayoutParams(webSize, webSize, android.view.Gravity.CENTER));

        // 圆弧地址栏（覆盖在 WebView 上方顶部弧段）
        circleBar = new CircleBrowserLayout(this);
        circleBar.setOnUrlClickListener(() -> showUrlEditDialog());
        root.addView(circleBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 按钮沿圆周分布
        int btnSize = com.magneo.compass.ui.Ui.dp(this, 40);
        float btnR = R * 0.92f;  // 按钮在圆边缘

        // 左上：返回上级 ◀
        android.widget.Button backBtn = new android.widget.Button(this);
        backBtn.setText("◀");
        backBtn.setTextColor(Color.rgb(232, 220, 192));
        backBtn.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        float angleBack = (float) Math.toRadians(225);  // 左下
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(btnSize, btnSize);
        blp.leftMargin = (int) (R + btnR * Math.cos(angleBack)) - btnSize / 2;
        blp.topMargin = (int) (R + btnR * Math.sin(angleBack)) - btnSize / 2;
        backBtn.setLayoutParams(blp);
        backBtn.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
            else finish();
        });
        root.addView(backBtn);

        // 右上：⋯ 菜单
        android.widget.Button moreBtn = new android.widget.Button(this);
        moreBtn.setText("⋯");
        moreBtn.setTextColor(Color.rgb(232, 220, 192));
        moreBtn.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        float angleMore = (float) Math.toRadians(315);  // 右下
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(btnSize, btnSize);
        mlp.leftMargin = (int) (R + btnR * Math.cos(angleMore)) - btnSize / 2;
        mlp.topMargin = (int) (R + btnR * Math.sin(angleMore)) - btnSize / 2;
        moreBtn.setLayoutParams(mlp);
        moreBtn.setOnClickListener(v -> showOverflow());
        root.addView(moreBtn);

        setContentView(root);

        // WebView 配置
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
                circleBar.setUrlText(url);
                addHistory(url);
            }
        });
        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> {
            try {
                android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, guessName(contentDisposition, url));
                r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                dm.enqueue(r);
                Toast.makeText(BrowserActivity.this, "开始下载", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(BrowserActivity.this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        String start = getIntent().getStringExtra("url");
        if (start == null || start.isEmpty()) start = "https://www.bing.com/";
        load(start);
        circleBar.setUrlText(start);
        history.addAll(loadHistory());
    }

    /** 点击圆弧地址栏时弹出编辑对话框 */
    private void showUrlEditDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(web.getUrl() != null ? web.getUrl() : "");
        input.setTextColor(Color.rgb(232, 220, 192));
        input.setHint("输入网址或搜索");
        input.setHintTextColor(Color.rgb(120, 114, 98));
        input.setSingleLine(true);
        input.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((v, action, ev) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                load(input.getText().toString());
                return true;
            }
            return false;
        });
        new com.magneo.compass.RoundDialog(this)
                .title("网址")
                .view(input)
                .item("前往", () -> load(input.getText().toString()))
                .cancel()
                .show();
    }

    private void showOverflow() {
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("浏览");
        d.item("★ 添加/取消书签", this::toggleBookmark);
        d.item("书签", this::showBookmarks);
        d.item("历史", this::showHistory);
        d.item(Prefs.getB(this, Prefs.K_NO_IMAGES, false) ? "✓ 无图模式" : "无图模式", () -> {
            Prefs.putB(this, Prefs.K_NO_IMAGES, !Prefs.getB(this, Prefs.K_NO_IMAGES, false));
            recreate();
        });
        d.item(Prefs.getB(this, Prefs.K_UA_DESKTOP, false) ? "✓ 桌面 UA" : "桌面 UA", () -> {
            Prefs.putB(this, Prefs.K_UA_DESKTOP, !Prefs.getB(this, Prefs.K_UA_DESKTOP, false));
            recreate();
        });
        d.item(Prefs.getB(this, Prefs.K_IGNORE_SSL, false) ? "✓ 忽略 SSL" : "忽略 SSL", () -> {
            Prefs.putB(this, Prefs.K_IGNORE_SSL, !Prefs.getB(this, Prefs.K_IGNORE_SSL, false));
            Toast.makeText(this, "已切换，下次加载生效", Toast.LENGTH_SHORT).show();
        });
        d.cancel().show();
    }

    private void load(String input) {
        String url = input.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) url = "https://" + url;
            else url = String.format(Prefs.get(this, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"),
                    Uri.encode(url));
        }
        circleBar.setUrlText(url);
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
