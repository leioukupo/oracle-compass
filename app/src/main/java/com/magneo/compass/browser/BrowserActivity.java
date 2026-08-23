package com.magneo.compass.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.ConsoleMessage;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.magneo.compass.Prefs;
import com.magneo.compass.ui.Ui;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** 灵镜浏览：圆屏轻量查询浏览器。 */
public class BrowserActivity extends com.magneo.compass.BaseActivity {

    private WebView web;
    private TextView titleView;
    private TextView statusView;
    private ProgressLineView progressLine;
    private LinearLayout toolbar;
    private Button backBtn;
    private Button forwardBtn;
    private Button reloadBtn;

    private final List<String> history = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clearStatusTask = () -> {
        if (statusView != null) statusView.setVisibility(View.GONE);
    };
    private final Runnable hideChromeTask = () -> setChromeVisible(false);
    private final Runnable blankCheckTask = this::checkBlankPage;
    private final Runnable roundFitTask = () -> applyRoundFitToPage(web == null ? null : web.getUrl());

    private String pageTitle = "";
    private String defaultUserAgent = "";
    private String lastConsoleError = "";
    private boolean loading = false;
    private boolean lastLoadHadError = false;
    private int progress = 0;
    private boolean chromeVisible = true;
    private boolean sslPromptShowing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.magneo.compass.QuitFix.apply(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int minSide = Math.min(sw, sh);

        web = new WebView(this);
        int webSize = minSide;
        root.addView(web, new FrameLayout.LayoutParams(webSize, webSize, Gravity.CENTER));

        int titleW = Math.min(Ui.dp(this, 280), (int) (minSide * 0.70f));
        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Ui.COLOR_TEXT);
        titleView.setTextSize(13);
        titleView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        titleView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        titleView.setText("灵镜浏览");
        titleView.setOnClickListener(v -> showUrlEditDialog());
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(titleW, Ui.dp(this, 34),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleLp.topMargin = Ui.dp(this, 46);
        root.addView(titleView, titleLp);

        progressLine = new ProgressLineView(this);
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(titleW - Ui.dp(this, 32),
                Ui.dp(this, 3), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        progressLp.topMargin = titleLp.topMargin + Ui.dp(this, 31);
        root.addView(progressLine, progressLp);

        statusView = new TextView(this);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Ui.COLOR_TEXT_DIM);
        statusView.setTextSize(12);
        statusView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        statusView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        statusView.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                Math.min(Ui.dp(this, 310), (int) (minSide * 0.74f)), Ui.dp(this, 30),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.bottomMargin = Ui.dp(this, 108);
        root.addView(statusView, statusLp);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        toolbar.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        backBtn = toolButton("‹");
        forwardBtn = toolButton("›");
        reloadBtn = toolButton("↻");
        Button moreBtn = toolButton("⋯");
        toolbar.addView(backBtn, toolLp());
        toolbar.addView(forwardBtn, toolLp());
        toolbar.addView(reloadBtn, toolLp());
        toolbar.addView(moreBtn, toolLp());
        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(Ui.dp(this, 228),
                Ui.dp(this, 52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toolbarLp.bottomMargin = Ui.dp(this, 48);
        root.addView(toolbar, toolbarLp);

        setContentView(root);
        configureWebView();

        backBtn.setOnClickListener(v -> {
            showChromeTransient();
            if (web.canGoBack()) web.goBack();
            else finish();
        });
        forwardBtn.setOnClickListener(v -> {
            showChromeTransient();
            if (web.canGoForward()) web.goForward();
            else showStatus("没有下一页");
        });
        reloadBtn.setOnClickListener(v -> {
            showChromeTransient();
            if (loading) {
                web.stopLoading();
                loading = false;
                showStatus("已停止加载");
            } else {
                web.reload();
            }
            updateChrome();
        });
        moreBtn.setOnClickListener(v -> {
            setChromeVisible(true);
            ui.removeCallbacks(hideChromeTask);
            showOverflow();
        });

        history.addAll(loadHistory());
        String start = getIntent().getStringExtra("url");
        if (start == null || start.isEmpty()) start = "https://www.bing.com/";
        load(start);
        scheduleChromeHide();
    }

    private void configureWebView() {
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        try { ws.setDatabaseEnabled(true); } catch (Throwable ignored) {}
        try { ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Throwable ignored) {}
        defaultUserAgent = ws.getUserAgentString();
        applyBrowserPrefs();
        web.setBackgroundColor(Color.WHITE);
        web.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) showChromeTransient();
            return false;
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                loading = true;
                progress = Math.max(5, progress);
                pageTitle = "";
                lastConsoleError = "";
                lastLoadHadError = false;
                ui.removeCallbacks(roundFitTask);
                ui.removeCallbacks(blankCheckTask);
                setChromeVisible(true);
                scheduleChromeHide(3600);
                updateChromeForUrl(url);
            }

            @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler,
                                                     android.net.http.SslError error) {
                showSslBlockedDialog(handler, error);
            }

            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                String cur = view == null ? null : view.getUrl();
                if (failingUrl == null || failingUrl.equals(cur)) {
                    lastLoadHadError = true;
                    showStatus("加载失败: " + (description == null ? String.valueOf(errorCode) : description), 8200);
                }
            }

            @Override public void onPageFinished(WebView v, String url) {
                loading = false;
                progress = 100;
                addHistory(url);
                updateChromeForUrl(url);
                scheduleChromeHide();
                scheduleRoundFit(url);
                ui.removeCallbacks(blankCheckTask);
                ui.postDelayed(blankCheckTask, 1300);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress = newProgress;
                loading = newProgress < 100;
                updateChrome();
                if (!loading) scheduleChromeHide();
                else scheduleChromeHide(3600);
            }

            @Override public void onReceivedTitle(WebView view, String title) {
                pageTitle = title == null ? "" : title.trim();
                updateChrome();
            }

            @Override public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    lastConsoleError = cm.message() == null ? "" : cm.message();
                    Log.w("BrowserActivity", "console error: " + lastConsoleError
                            + " @ " + cm.sourceId() + ":" + cm.lineNumber());
                }
                return super.onConsoleMessage(cm);
            }
        });

        web.setDownloadListener((url, ua, contentDisposition, mimetype, len) -> {
            try {
                android.app.DownloadManager dm =
                        (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                android.app.DownloadManager.Request r =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,
                        guessName(contentDisposition, url));
                r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                dm.enqueue(r);
                showStatus("开始下载: " + guessName(contentDisposition, url));
            } catch (Exception e) {
                showStatus("下载失败: " + e.getMessage());
            }
        });
    }

    private Button toolButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        Ui.styleIconButton(b);
        b.setTextSize(20);
        b.setPadding(0, 0, 0, Ui.dp(this, 2));
        return b;
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }

    private void showUrlEditDialog() {
        setChromeVisible(true);
        ui.removeCallbacks(hideChromeTask);
        EditText input = new EditText(this);
        input.setText(web.getUrl() != null ? web.getUrl() : "");
        input.setHint("输入网址或搜索");
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        final com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this);
        input.setOnEditorActionListener((v, action, ev) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                d.dismiss();
                load(input.getText().toString());
                return true;
            }
            return false;
        });
        d.title("搜索 / 网址")
                .field(input)
                .item("前往", () -> load(input.getText().toString()))
                .item("书签", this::showBookmarks)
                .item("历史", this::showHistory)
                .cancel()
                .show();
    }

    private void showOverflow() {
        setChromeVisible(true);
        ui.removeCallbacks(hideChromeTask);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("浏览");
        d.item(bookmarkLabel(), this::toggleBookmark);
        d.item("书签", this::showBookmarks);
        d.item("历史", this::showHistory);
        d.item("页面设置", this::showPageSettings);
        d.cancel().show();
    }

    private void showPageSettings() {
        setChromeVisible(true);
        ui.removeCallbacks(hideChromeTask);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("页面设置");
        d.item(Prefs.getB(this, Prefs.K_BROWSER_ROUND_FIT, true) ? "✓ 圆屏适配" : "圆屏适配", () -> {
            boolean next = !Prefs.getB(this, Prefs.K_BROWSER_ROUND_FIT, true);
            Prefs.putB(this, Prefs.K_BROWSER_ROUND_FIT, next);
            applyBrowserPrefs();
            applyRoundFitToPage(web.getUrl());
            web.reload();
            showStatus(next ? "圆屏适配已开启" : "圆屏适配已关闭");
        });
        d.item(Prefs.getB(this, Prefs.K_NO_IMAGES, false) ? "✓ 无图模式" : "无图模式", () -> {
            Prefs.putB(this, Prefs.K_NO_IMAGES, !Prefs.getB(this, Prefs.K_NO_IMAGES, false));
            applyBrowserPrefs();
            web.reload();
            showStatus("无图模式已切换");
        });
        d.item(Prefs.getB(this, Prefs.K_UA_DESKTOP, false) ? "✓ 桌面 UA" : "桌面 UA", () -> {
            Prefs.putB(this, Prefs.K_UA_DESKTOP, !Prefs.getB(this, Prefs.K_UA_DESKTOP, false));
            applyBrowserPrefs();
            web.reload();
            showStatus("桌面 UA 已切换");
        });
        d.item("SSL：系统校验", this::showSslHelpDialog);
        d.cancel().show();
    }

    private void load(String input) {
        if (input == null) return;
        String url = input.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = String.format(Prefs.get(this, Prefs.K_SEARCH_ENGINE,
                        "https://www.bing.com/search?q=%s"), Uri.encode(url));
            }
        }
        pageTitle = "";
        loading = true;
        progress = Math.max(5, progress);
        updateChromeForUrl(url);
        setChromeVisible(true);
        scheduleChromeHide(3600);
        web.loadUrl(url);
    }

    private void applyBrowserPrefs() {
        WebSettings ws = web.getSettings();
        ws.setBlockNetworkImage(Prefs.getB(this, Prefs.K_NO_IMAGES, false));
        boolean roundFit = Prefs.getB(this, Prefs.K_BROWSER_ROUND_FIT, true);
        ws.setLoadWithOverviewMode(!roundFit);
        ws.setUseWideViewPort(!roundFit);
        if (Prefs.getB(this, Prefs.K_UA_DESKTOP, false)) {
            ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        } else {
            ws.setUserAgentString(defaultUserAgent);
        }
    }

    private void scheduleRoundFit(String url) {
        ui.removeCallbacks(roundFitTask);
        if (!isHttpUrl(url)) return;
        ui.postDelayed(roundFitTask, 120);
        ui.postDelayed(roundFitTask, 1400);
    }

    private void applyRoundFitToPage(String url) {
        if (web == null || !isHttpUrl(url)) return;
        boolean enabled = Prefs.getB(this, Prefs.K_BROWSER_ROUND_FIT, true);
        String js = enabled ? buildRoundFitEnableJs() : buildRoundFitDisableJs();
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w("BrowserActivity", "round fit injection failed", t);
        }
    }

    private boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private String buildRoundFitEnableJs() {
        String css =
                ":root{--oracle-round-side:64px;--oracle-round-top:76px;--oracle-round-bottom:90px;}"
                + "html.oracle-round-fit{overflow-x:hidden!important;background:transparent!important;}"
                + "html.oracle-round-fit body{box-sizing:border-box!important;width:100%!important;max-width:100vw!important;"
                + "min-height:100vh!important;margin-left:auto!important;margin-right:auto!important;"
                + "padding-left:var(--oracle-round-side)!important;padding-right:var(--oracle-round-side)!important;"
                + "padding-top:var(--oracle-round-top)!important;padding-bottom:var(--oracle-round-bottom)!important;"
                + "overflow-x:hidden!important;}"
                + "html.oracle-round-fit body *{box-sizing:border-box!important;}"
                + "html.oracle-round-fit img,html.oracle-round-fit video,html.oracle-round-fit canvas,"
                + "html.oracle-round-fit iframe,html.oracle-round-fit svg{max-width:100%!important;}"
                + "html.oracle-round-fit table{max-width:100%!important;}"
                + "html.oracle-round-fit pre,html.oracle-round-fit code{white-space:pre-wrap!important;word-break:break-word!important;}"
                + "html.oracle-round-fit input,html.oracle-round-fit textarea,html.oracle-round-fit select,"
                + "html.oracle-round-fit button,html.oracle-round-fit form{max-width:100%!important;min-width:0!important;}"
                + "html.oracle-round-fit #root,html.oracle-round-fit #app,html.oracle-round-fit #__next,"
                + "html.oracle-round-fit .app,html.oracle-round-fit .page,html.oracle-round-fit main,"
                + "html.oracle-round-fit .container,html.oracle-round-fit .content{max-width:100%!important;min-width:0!important;}"
                + "html.oracle-round-fit header,html.oracle-round-fit footer,html.oracle-round-fit nav,"
                + "html.oracle-round-fit [role='banner'],html.oracle-round-fit [role='navigation']{"
                + "max-width:calc(100vw - var(--oracle-round-side) - var(--oracle-round-side))!important;"
                + "margin-left:auto!important;margin-right:auto!important;}"
                + "html.oracle-round-fit a,html.oracle-round-fit p,html.oracle-round-fit li,"
                + "html.oracle-round-fit h1,html.oracle-round-fit h2,html.oracle-round-fit h3{overflow-wrap:anywhere;}";
        return "(function(){try{"
                + "var id='oracle-round-fit-style';var cls='oracle-round-fit';var de=document.documentElement;"
                + "if(!de||!document.body)return 'no-body';"
                + "if(de.classList)de.classList.add(cls);else if((' '+de.className+' ').indexOf(' '+cls+' ')<0)de.className+=' '+cls;"
                + "var meta=document.querySelector('meta[name=\"viewport\"]');"
                + "if(!meta){meta=document.createElement('meta');meta.name='viewport';"
                + "meta.content='width=device-width, initial-scale=1, maximum-scale=3, user-scalable=yes';"
                + "(document.head||de).appendChild(meta);}"
                + "var style=document.getElementById(id);"
                + "if(!style){style=document.createElement('style');style.id=id;(document.head||de).appendChild(style);}"
                + "style.textContent='" + jsQuote(css) + "';"
                + "var se=document.scrollingElement||de;if(se)se.scrollLeft=0;if(document.body)document.body.scrollLeft=0;"
                + "return 'on';"
                + "}catch(e){return 'err:'+e.message;}})()";
    }

    private String buildRoundFitDisableJs() {
        return "(function(){try{"
                + "var id='oracle-round-fit-style';var cls='oracle-round-fit';"
                + "var style=document.getElementById(id);if(style&&style.parentNode)style.parentNode.removeChild(style);"
                + "var de=document.documentElement;if(de){"
                + "if(de.classList)de.classList.remove(cls);"
                + "else de.className=(' '+de.className+' ').replace(' '+cls+' ',' ').trim();}"
                + "return 'off';"
                + "}catch(e){return 'err:'+e.message;}})()";
    }

    private static String jsQuote(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
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
        showStatus(bm.contains(cur) ? "已添加书签" : "已移除书签");
    }

    private void showBookmarks() {
        List<String> bm = loadBookmarks();
        if (bm.isEmpty()) {
            showStatus("暂无书签");
            return;
        }
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("书签");
        for (String s : bm) d.item(shorten(s), () -> load(s));
        d.cancel().show();
    }

    private void showHistory() {
        if (history.isEmpty()) {
            showStatus("暂无历史");
            return;
        }
        List<String> rev = new ArrayList<>(history);
        java.util.Collections.reverse(rev);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("历史");
        for (String s : rev) d.item(shorten(s), () -> load(s));
        d.cancel().show();
    }

    private String shorten(String u) {
        String label = domainAndPath(u);
        return label.length() > 28 ? label.substring(0, 28) + "…" : label;
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

    private String bookmarkLabel() {
        String cur = web.getUrl();
        if (cur != null && loadBookmarks().contains(cur)) return "★ 取消书签";
        return "★ 添加书签";
    }

    private void updateChrome() {
        updateChromeForUrl(web.getUrl());
    }

    private void updateChromeForUrl(String url) {
        String label = pageTitle == null ? "" : pageTitle.trim();
        if (label.isEmpty() || label.startsWith("http://") || label.startsWith("https://")) {
            label = shortHost(url);
        }
        if (label == null || label.trim().isEmpty()) label = "灵镜浏览";
        titleView.setText(label);
        if (progressLine != null) {
            progressLine.setProgress(chromeVisible && loading ? Math.max(5, progress) : 0);
        }
        if (forwardBtn != null) {
            boolean can = web.canGoForward();
            forwardBtn.setEnabled(can);
            forwardBtn.setAlpha(can ? 1f : 0.38f);
        }
        if (backBtn != null) backBtn.setAlpha(1f);
        if (reloadBtn != null) reloadBtn.setText(loading ? "×" : "↻");
    }

    private void showStatus(String msg) {
        showStatus(msg, 3600);
    }

    private void showStatus(String msg, long ms) {
        if (statusView == null) return;
        statusView.setText(msg == null ? "" : msg);
        statusView.setVisibility(View.VISIBLE);
        setChromeVisible(true);
        ui.removeCallbacks(clearStatusTask);
        ui.postDelayed(clearStatusTask, ms);
        scheduleChromeHide(Math.max(ms, 2800));
    }

    private void showSslBlockedDialog(android.webkit.SslErrorHandler handler,
                                      android.net.http.SslError error) {
        if (sslPromptShowing) {
            handler.cancel();
            return;
        }
        sslPromptShowing = true;
        handler.cancel();
        loading = false;
        progress = 0;
        updateChrome();
        setChromeVisible(true);
        ui.removeCallbacks(hideChromeTask);
        showStatus("SSL 证书未被系统信任", 9000);
        new com.magneo.compass.RoundDialog(this)
                .title("证书未通过")
                .text("浏览器已按系统证书校验拦截，未忽略 SSL。要正常打开，需要服务端换成 Android 5.1 信任的证书链，或在系统安装对应 CA。")
                .item("知道了", () -> {
                    sslPromptShowing = false;
                    showStatus("已按系统校验拦截 SSL", 6200);
                })
                .show();
        ui.postDelayed(() -> sslPromptShowing = false, 12000);
    }

    private void showSslHelpDialog() {
        new com.magneo.compass.RoundDialog(this)
                .title("SSL 校验")
                .text("浏览器页现在只按系统证书打开，不再忽略 SSL。Android 5.1 不信任的证书链，需要在服务端更换兼容老安卓的证书链。")
                .item("知道了", null)
                .show();
    }

    private void checkBlankPage() {
        if (web == null || lastLoadHadError) return;
        try {
            web.evaluateJavascript("(function(){var b=document.body;if(!b)return '0|0';"
                    + "var r=document.getElementById('root');"
                    + "return String((b.innerText||'').trim().length)+'|'"
                    + "+String(r?r.childNodes.length:-1);})()", value -> {
                String data = unquoteJs(value);
                String[] p = data.split("\\|");
                int textLen = p.length > 0 ? parseInt(p[0], 0) : 0;
                int rootChildren = p.length > 1 ? parseInt(p[1], -1) : -1;
                if (rootChildren == 0 || textLen <= 2) {
                    String extra = lastConsoleError.isEmpty() ? "" : ": " + compact(lastConsoleError, 28);
                    showStatus("页面空白，可能不兼容旧 WebView" + extra, 10000);
                }
            });
        } catch (Throwable t) {
            Log.w("BrowserActivity", "blank check failed", t);
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String compact(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    private static String unquoteJs(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void showChromeTransient() {
        setChromeVisible(true);
        scheduleChromeHide();
    }

    private void scheduleChromeHide() {
        scheduleChromeHide(3300);
    }

    private void scheduleChromeHide(long ms) {
        ui.removeCallbacks(hideChromeTask);
        ui.postDelayed(hideChromeTask, ms);
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        int v = visible ? View.VISIBLE : View.GONE;
        if (titleView != null) titleView.setVisibility(v);
        if (toolbar != null) toolbar.setVisibility(v);
        if (progressLine != null) {
            if (!visible || !loading) progressLine.setVisibility(View.GONE);
            else progressLine.setProgress(progress);
        }
    }

    private String shortHost(String url) {
        try {
            if (url == null || url.trim().isEmpty()) return "";
            String host = Uri.parse(url).getHost();
            if (host == null || host.isEmpty()) return url;
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return url == null ? "" : url;
        }
    }

    private String domainAndPath(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return url == null ? "" : url;
            if (host.startsWith("www.")) host = host.substring(4);
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) return host;
            return host + path;
        } catch (Exception e) {
            return url == null ? "" : url;
        }
    }

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
        ui.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    private class ProgressLineView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int value = 0;

        ProgressLineView(Context context) {
            super(context);
            setVisibility(View.GONE);
        }

        void setProgress(int progress) {
            value = Math.max(0, Math.min(100, progress));
            setVisibility(value > 0 && value < 100 ? View.VISIBLE : View.GONE);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (value <= 0 || value >= 100) return;
            float y = getHeight() / 2f;
            p.setStrokeWidth(Math.max(1f, getHeight()));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.argb(76, 212, 175, 55));
            canvas.drawLine(0, y, getWidth(), y, p);
            p.setColor(Ui.COLOR_GOLD);
            canvas.drawLine(0, y, getWidth() * value / 100f, y, p);
        }
    }
}
