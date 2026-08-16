package com.magneo.compass;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.ui.RoundFrame;
import com.magneo.compass.ui.Ui;
import com.magneo.compass.web.SettingsWebServer;

import java.util.ArrayList;
import java.util.List;

/** 罗盘式设置：外圈分类导航，中心摘要面板适配 800x800 圆屏。 */
public class SettingsActivity extends BaseActivity {

    private static final String[] CATS = {"模型", "语音", "视觉", "监听", "浏览", "桌面", "应用", "记录"};
    private static final int CAT_COUNT = CATS.length;
    private static final float SLOT_DEG = 360f / CAT_COUNT;

    private int cat = 0;
    private int offset = 0;
    private float baseRot = 0;
    private float dragRot = 0;
    private int lastOffset = -1;
    private RingPanel ring;
    private FrameLayout centerContent;
    private TextView homeStatusView;
    private String homeStatus = "系统会在按下 Home 键时确认默认桌面";

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG);
        root.addView(new CompassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ring = new RingPanel(this);
        ring.setAngleCallback(new AngleCallback() {
            @Override public void onAngle(float a) { onDragAngle(a); }
            @Override public void onEnd(float a) { onDragEnd(a); }
        });
        root.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        centerContent = new RoundFrame(this, true, false, 0);
        centerContent.setBackground(ovalBg(Color.argb(222, 28, 22, 14),
                Color.argb(210, 145, 116, 48), 1));
        root.addView(centerContent, new FrameLayout.LayoutParams(Ui.dp(this, 304),
                Ui.dp(this, 304), Gravity.CENTER));

        setContentView(root);
        ring.post(() -> renderRing(0, 0));
        ring.post(() -> selectCategory(0));
    }

    private void onDragAngle(float acc) {
        dragRot = acc;
        float total = baseRot + dragRot;
        int k = (int) Math.floor(total / SLOT_DEG);
        int off = ((k % CAT_COUNT) + CAT_COUNT) % CAT_COUNT;
        float vis = total - k * SLOT_DEG;
        if (off != lastOffset) {
            lastOffset = off;
            ring.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            selectCategory(off);
        }
        renderRing(vis, off);
    }

    private void onDragEnd(float acc) {
        dragRot = acc;
        int k = Math.round((baseRot + dragRot) / SLOT_DEG);
        baseRot = k * SLOT_DEG;
        dragRot = 0;
        offset = ((k % CAT_COUNT) + CAT_COUNT) % CAT_COUNT;
        lastOffset = offset;
        renderRing(0, offset);
        selectCategory(offset);
    }

    private float norm(float a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private void renderRing(float visRot, int off) {
        ring.removeAllViews();
        offset = off;
        int w = ring.getWidth(), h = ring.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.43f;
        int s = Ui.dp(this, 42);
        for (int i = 0; i < CAT_COUNT; i++) {
            int idx = ((off + i) % CAT_COUNT + CAT_COUNT) % CAT_COUNT;
            boolean sel = i == 0;
            TextView tv = new TextView(this);
            tv.setText(CATS[idx]);
            tv.setTextColor(sel ? Ui.COLOR_GOLD : Ui.COLOR_TEXT);
            tv.setTextSize(sel ? 13 : 12);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setBackground(sel
                    ? ovalBg(Color.argb(96, 212, 175, 55), Ui.COLOR_GOLD, 2)
                    : ovalBg(Color.argb(180, 28, 24, 16), Color.argb(120, 120, 98, 50), 1));
            tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            final int slot = i;
            tv.setOnClickListener(v -> {
                int idx2 = ((offset + slot) % CAT_COUNT + CAT_COUNT) % CAT_COUNT;
                cat = idx2;
                baseRot = 0;
                dragRot = 0;
                lastOffset = idx2;
                renderRing(0, idx2);
                selectCategory(cat);
            });
            float angle = (float) Math.toRadians(-90 + i * SLOT_DEG + visRot);
            int x = (int) (cx + r * Math.cos(angle) - s / 2f);
            int y = (int) (cy + r * Math.sin(angle) - s / 2f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
            lp.leftMargin = x;
            lp.topMargin = y;
            ring.addView(tv, lp);
        }
        ring.invalidate();
    }

    private void selectCategory(int i) {
        cat = i;
        centerContent.removeAllViews();

        CurvedScrollView sc = new CurvedScrollView(this);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setVerticalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Ui.dp(this, 28);
        body.setPadding(side, Ui.dp(this, 32), side, Ui.dp(this, 34));
        sc.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sc.setBody(body);
        centerContent.addView(sc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        addTitle(body, CATS[i] + " 设置", panelSubTitle(i));
        try {
            switch (i) {
                case 0: buildLlm(body); break;
                case 1: buildVoice(body); break;
                case 2: buildVision(body); break;
                case 3: buildVad(body); break;
                case 4: buildBrowser(body); break;
                case 5: buildHome(body); break;
                case 6: buildApps(body); break;
                case 7: buildConv(body); break;
            }
        } catch (Throwable t) {
            android.util.Log.w("Settings", "面板构建失败", t);
            TextView err = new TextView(this);
            err.setText("该页加载出错：" + t.getMessage());
            err.setTextColor(Ui.COLOR_ERROR);
            err.setTextSize(Ui.TEXT_SM);
            err.setGravity(Gravity.CENTER);
            body.addView(err, fullLp(4));
        }
        addFooter(body);
        sc.post(sc::applyCurve);
    }

    @Override
    protected void onBackExit() {
        saveCurrent();
    }

    private void buildLlm(LinearLayout b) {
        String provider = Prefs.get(this, Prefs.K_PROVIDER, "通义千问");
        summaryRow(b, "服务商", provider, this::chooseProvider);
        summaryRow(b, "API Key", maskSecret(Prefs.get(this, Prefs.K_API_KEY, "")),
                () -> editString("API Key", Prefs.K_API_KEY, "", false, true, null));
        summaryRow(b, "接口地址", compact(Prefs.get(this, Prefs.K_BASE_URL, "")),
                () -> editString("Base URL", Prefs.K_BASE_URL, "", false, false, null));
        summaryRow(b, "文本模型", compact(Prefs.get(this, Prefs.K_TEXT_MODEL, "")),
                () -> editString("文本模型", Prefs.K_TEXT_MODEL, "", false, false, null));
        summaryRow(b, "视觉模型", compact(Prefs.get(this, Prefs.K_VISION_MODEL, "")),
                () -> editString("视觉模型", Prefs.K_VISION_MODEL, "", false, false, null));
        actionButton(b, "测试对话", this::testChat, false);
    }

    private void buildVoice(LinearLayout b) {
        section(b, "语音识别");
        summaryRow(b, "ASR 地址", compact(Prefs.get(this, Prefs.K_ASR_URL, "")),
                () -> editString("ASR 地址", Prefs.K_ASR_URL, "", false, false, null));
        summaryRow(b, "ASR 模型", compact(Prefs.get(this, Prefs.K_ASR_MODEL, "")),
                () -> editString("ASR 模型", Prefs.K_ASR_MODEL, "", false, false, null));
        section(b, "语音合成");
        summaryRow(b, "TTS 地址", compact(Prefs.get(this, Prefs.K_TTS_URL, "")),
                () -> editString("TTS 地址", Prefs.K_TTS_URL, "", false, false, null));
        summaryRow(b, "TTS 模型", compact(Prefs.get(this, Prefs.K_TTS_MODEL, "")),
                () -> editString("TTS 模型", Prefs.K_TTS_MODEL, "", false, false, null));
        summaryRow(b, "TTS 音色", compact(Prefs.get(this, Prefs.K_TTS_VOICE, "")),
                () -> editString("TTS 音色", Prefs.K_TTS_VOICE, "", false, false, null));
        toggleRow(b, "本地优先", Prefs.K_LOCAL_TTS_FIRST, true);
        summaryRow(b, "语音 Prompt", promptSummary(Prefs.get(this, Prefs.K_SYS_PROMPT_VOICE,
                Prefs.DEFAULT_SYS_PROMPT_VOICE)), () -> editString("语音系统提示词",
                Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE, true, false, null));
    }

    private void buildVision(LinearLayout b) {
        toggleRow(b, "灵眼自动感知", Prefs.K_VISION_ENABLED, true);
        int cur = Prefs.getI(this, Prefs.K_VISION_INTERVAL, 2);
        segmentedInt(b, "感知间隔", Prefs.K_VISION_INTERVAL, cur,
                new int[]{1, 2, 5, 10}, new String[]{"1s", "2s", "5s", "10s"},
                () -> editInt("自定义间隔(秒)", Prefs.K_VISION_INTERVAL, 2, 1, 60, "秒"));
        summaryRow(b, "视觉 Prompt", promptSummary(Prefs.get(this, Prefs.K_SYS_PROMPT_VISION,
                Prefs.DEFAULT_SYS_PROMPT_VISION)), () -> editString("视觉系统提示词",
                Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION, true, false, null));
    }

    private void buildVad(LinearLayout b) {
        boolean enabled = Prefs.getB(this, Prefs.K_VAD_ENABLED, false);
        toggleRow(b, "持续监听 VAD", Prefs.K_VAD_ENABLED, false);
        int cur = Prefs.getI(this, Prefs.K_VAD_SENSITIVITY, 600);
        segmentedInt(b, "监听灵敏度", Prefs.K_VAD_SENSITIVITY, cur,
                new int[]{900, 600, 350}, new String[]{"低", "中", "高"},
                () -> editInt("自定义灵敏度", Prefs.K_VAD_SENSITIVITY, 600, 1, 3000, ""));
        if (enabled) {
            TextView warn = subtle("持续监听会增加耗电，低电量时建议关闭");
            warn.setTextColor(Color.rgb(180, 76, 54));
            b.addView(warn, fullLp(3));
        }
    }

    private void buildBrowser(LinearLayout b) {
        summaryRow(b, "搜索引擎", compact(Prefs.get(this, Prefs.K_SEARCH_ENGINE,
                "https://www.bing.com/search?q=%s")), this::chooseSearchEngine);
        toggleRow(b, "桌面版 UA", Prefs.K_UA_DESKTOP, false);
        toggleRow(b, "无图模式", Prefs.K_NO_IMAGES, false);
        boolean ssl = Prefs.getB(this, Prefs.K_IGNORE_SSL, false);
        summaryRow(b, "兼容 CA", ssl ? "已忽略证书校验" : "默认安全校验", this::confirmSslToggle);
    }

    private void buildHome(LinearLayout b) {
        homeStatusView = subtle(homeStatus);
        homeStatusView.setGravity(Gravity.CENTER);
        b.addView(homeStatusView, fullLp(4));
        actionButton(b, "设为默认桌面", () -> {
            homeStatus = "已打开系统桌面选择";
            if (homeStatusView != null) homeStatusView.setText(homeStatus);
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setComponent(new ComponentName(this, FakeHomeActivity.class));
            startActivity(i);
        }, false);
        actionButton(b, "恢复原桌面", () -> {
            homeStatus = "正在请求 root 清除默认桌面";
            if (homeStatusView != null) homeStatusView.setText(homeStatus);
            resetHome();
        }, true);
    }

    private void buildApps(LinearLayout b) {
        summaryRow(b, "网页设置", SettingsWebServer.url(), this::showWebSettings);
        actionButton(b, "优先应用 1-8", () -> startActivity(new Intent(this, PriorityAppsActivity.class)), false);
        actionButton(b, "保存并返回罗盘", () -> {
            saveCurrent();
            finish();
        }, false);
    }

    private void buildConv(LinearLayout b) {
        int maxKb = Prefs.getI(this, Prefs.K_CONV_MAX_KB, 1024);
        int cleanMin = Prefs.getI(this, Prefs.K_CONV_CLEAN_MIN, 60);
        long size = ConversationLog.size(this);
        summaryRow(b, "当前记录", formatBytes(size), this::showConvReadInfo);
        summaryRow(b, "大小上限", maxKb + " KB", this::chooseLogMax);
        summaryRow(b, "清理周期", cleanMin <= 0 ? "关闭定时清理" : cleanMin + " 分钟", this::chooseCleanMin);
        actionButton(b, "在网页端查看记录", this::showConvWeb, false);
        actionButton(b, "清空对话记录", this::confirmClearConv, true);
    }

    private void addTitle(LinearLayout b, String title, String sub) {
        TextView tv = new TextView(this);
        tv.setText(title);
        Ui.styleTitle(tv);
        tv.setTextSize(18);
        tv.setSingleLine(true);
        b.addView(tv, fullLp(0));
        if (sub != null && !sub.isEmpty()) {
            TextView st = subtle(sub);
            st.setGravity(Gravity.CENTER);
            b.addView(st, fullLp(2));
        }
    }

    private String panelSubTitle(int i) {
        switch (i) {
            case 0: return "模型、密钥与端点";
            case 1: return "识别、合成与人设";
            case 2: return "环境感知节奏";
            case 3: return "常驻收音状态";
            case 4: return "搜索与网页兼容";
            case 5: return "系统 Home 入口";
            case 6: return "入口管理";
            case 7: return "对话留存";
        }
        return "";
    }

    private void section(LinearLayout b, String label) {
        TextView tv = subtle(label);
        tv.setTextColor(Ui.COLOR_GOLD_DARK);
        tv.setTextSize(11);
        tv.setGravity(Gravity.START);
        b.addView(tv, fullLp(5));
    }

    private void summaryRow(LinearLayout b, String label, String value, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 42));
        row.setPadding(Ui.dp(this, 13), Ui.dp(this, 5), Ui.dp(this, 13), Ui.dp(this, 5));
        row.setBackground(rowBg(false, false));
        if (onClick != null) row.setOnClickListener(v -> onClick.run());

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.COLOR_GOLD);
        l.setTextSize(11);
        l.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView val = new TextView(this);
        val.setText(value == null || value.trim().isEmpty() ? "未设置" : value);
        val.setTextColor(Ui.COLOR_TEXT);
        val.setTextSize(13);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        b.addView(row, fullLp(4));
    }

    private void toggleRow(LinearLayout b, String label, String key, boolean def) {
        boolean on = Prefs.getB(this, key, def);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 42));
        row.setPadding(Ui.dp(this, 13), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        row.setBackground(rowBg(on, false));
        row.setOnClickListener(v -> {
            Prefs.putB(this, key, !Prefs.getB(this, key, def));
            selectCategory(cat);
        });

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(on ? Ui.COLOR_GOLD : Ui.COLOR_TEXT);
        l.setTextSize(13);
        l.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView state = new TextView(this);
        state.setText(on ? "开" : "关");
        state.setTextColor(on ? Ui.COLOR_BG_DEEP : Ui.COLOR_TEXT_DIM);
        state.setTextSize(12);
        state.setGravity(Gravity.CENTER);
        state.setBackground(ovalBg(on ? Ui.COLOR_GOLD : Ui.COLOR_PANEL_ALT,
                on ? Ui.COLOR_GOLD : Ui.COLOR_GOLD_DIM, 1));
        row.addView(state, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        b.addView(row, fullLp(4));
    }

    private void segmentedInt(LinearLayout b, String label, String key, int cur,
                              int[] values, String[] labels, final Runnable custom) {
        section(b, label);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setBackground(rowBg(false, false));
        row.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));

        boolean preset = false;
        for (int v : values) if (v == cur) preset = true;
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            Button btn = segment(labels[i], cur == value);
            btn.setOnClickListener(v -> {
                Prefs.putI(this, key, value);
                selectCategory(cat);
            });
            row.addView(btn, segLp(i == 0));
        }
        Button c = segment("自定", !preset);
        c.setOnClickListener(v -> custom.run());
        row.addView(c, segLp(values.length == 0));
        b.addView(row, fullLp(4));
    }

    private Button segment(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(selected ? Ui.COLOR_BG_DEEP : Ui.COLOR_TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rowBg(selected, false));
        return b;
    }

    private LinearLayout.LayoutParams segLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 32), 1);
        lp.setMargins(first ? 0 : Ui.dp(this, 3), 0, 0, 0);
        return lp;
    }

    private void actionButton(LinearLayout b, String label, final Runnable onClick, boolean danger) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setTextColor(danger ? Color.rgb(245, 175, 145) : Ui.COLOR_TEXT);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        btn.setBackground(rowBg(false, danger));
        btn.setOnClickListener(v -> onClick.run());
        b.addView(btn, fullLp(4));
    }

    private void addFooter(LinearLayout b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button save = footerButton("保存");
        save.setOnClickListener(v -> {
            saveCurrent();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        row.addView(save, footerLp(true));

        Button back = footerButton("返回");
        back.setOnClickListener(v -> {
            saveCurrent();
            finish();
        });
        row.addView(back, footerLp(false));
        b.addView(row, fullLp(8));
    }

    private Button footerButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setTextColor(Ui.COLOR_TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rowBg(false, false));
        return b;
    }

    private LinearLayout.LayoutParams footerLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1);
        lp.setMargins(first ? 0 : Ui.dp(this, 6), 0, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams fullLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, topDp), 0, 0);
        return lp;
    }

    private TextView subtle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        Ui.styleSubtle(tv);
        tv.setTextSize(12);
        tv.setLineSpacing(Ui.dp(this, 2), 1f);
        return tv;
    }

    private GradientDrawable rowBg(boolean selected, boolean danger) {
        int fill = selected ? Color.argb(215, 212, 175, 55) : Color.argb(178, 38, 31, 20);
        int stroke = selected ? Ui.COLOR_GOLD : Color.argb(130, 120, 98, 50);
        if (danger) {
            fill = Color.argb(96, 92, 27, 20);
            stroke = Color.argb(170, 139, 30, 30);
        }
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(Ui.dp(this, 16));
        g.setStroke(Ui.dp(this, 1), stroke);
        return g;
    }

    private GradientDrawable ovalBg(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(Ui.dp(this, strokeDp), stroke);
        return g;
    }

    private void chooseProvider() {
        String cur = Prefs.get(this, Prefs.K_PROVIDER, "通义千问");
        RoundDialog d = new RoundDialog(this).title("服务商");
        for (ProviderConfig pc : ProviderConfig.all()) {
            final ProviderConfig chosen = pc;
            d.item((pc.name.equals(cur) ? "✓ " : "") + pc.name, () -> {
                Prefs.put(this, Prefs.K_PROVIDER, chosen.name);
                putIfEmpty(Prefs.K_BASE_URL, chosen.baseUrl);
                putIfEmpty(Prefs.K_TEXT_MODEL, chosen.textModel);
                putIfEmpty(Prefs.K_VISION_MODEL, chosen.visionModel);
                putIfEmpty(Prefs.K_ASR_URL, chosen.asrUrl);
                putIfEmpty(Prefs.K_ASR_MODEL, chosen.asrModel);
                putIfEmpty(Prefs.K_TTS_URL, chosen.ttsUrl);
                putIfEmpty(Prefs.K_TTS_MODEL, chosen.ttsModel);
                putIfEmpty(Prefs.K_TTS_VOICE, chosen.ttsVoice);
                Toast.makeText(this, "已切换服务商", Toast.LENGTH_SHORT).show();
                selectCategory(cat);
            });
        }
        d.cancel().show();
    }

    private void putIfEmpty(String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (Prefs.get(this, key, "").trim().isEmpty()) Prefs.put(this, key, value);
    }

    private void editString(String title, String key, String def, boolean multi,
                            boolean secret, final Runnable afterSave) {
        final EditText e = new EditText(this);
        e.setText(Prefs.get(this, key, def));
        e.setSelection(e.getText().length());
        if (multi) {
            e.setSingleLine(false);
            e.setMinLines(4);
            e.setMaxLines(6);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            e.setSingleLine(true);
            int variation = secret ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_NORMAL;
            e.setInputType(InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        RoundDialog d = new RoundDialog(this).title(title);
        if (multi) d.fieldArea(e); else d.field(e);
        d.item("保存", () -> {
            Prefs.put(this, key, e.getText().toString().trim());
            if (afterSave != null) afterSave.run();
            selectCategory(cat);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        }).cancel().show();
    }

    private void editInt(String title, String key, int def, int min, int max, String suffix) {
        final EditText e = new EditText(this);
        e.setText(String.valueOf(Prefs.getI(this, key, def)));
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        new RoundDialog(this)
                .title(title)
                .field(e)
                .item("保存", () -> {
                    try {
                        int v = Integer.parseInt(e.getText().toString().trim());
                        v = Math.max(min, Math.min(max, v));
                        Prefs.putI(this, key, v);
                        Toast.makeText(this, "已保存 " + v + suffix, Toast.LENGTH_SHORT).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                    }
                    selectCategory(cat);
                })
                .cancel()
                .show();
    }

    private void chooseSearchEngine() {
        String cur = Prefs.get(this, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s");
        new RoundDialog(this)
                .title("搜索引擎")
                .item((cur.contains("bing.com") ? "✓ " : "") + "Bing", () -> {
                    Prefs.put(this, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s");
                    selectCategory(cat);
                })
                .item((cur.contains("google.com") ? "✓ " : "") + "Google", () -> {
                    Prefs.put(this, Prefs.K_SEARCH_ENGINE, "https://www.google.com/search?q=%s");
                    selectCategory(cat);
                })
                .item("自定义", () -> editString("搜索 URL", Prefs.K_SEARCH_ENGINE,
                        "https://www.bing.com/search?q=%s", false, false, null))
                .cancel()
                .show();
    }

    private void confirmSslToggle() {
        boolean ssl = Prefs.getB(this, Prefs.K_IGNORE_SSL, false);
        if (ssl) {
            Prefs.putB(this, Prefs.K_IGNORE_SSL, false);
            Toast.makeText(this, "已恢复证书校验", Toast.LENGTH_SHORT).show();
            selectCategory(cat);
            return;
        }
        new RoundDialog(this)
                .title("兼容 CA")
                .text("开启后会忽略网页证书错误，仅建议内网或旧设备临时使用。")
                .item("开启忽略证书", () -> {
                    Prefs.putB(this, Prefs.K_IGNORE_SSL, true);
                    Toast.makeText(this, "已开启，下次加载生效", Toast.LENGTH_SHORT).show();
                    selectCategory(cat);
                })
                .cancel()
                .show();
    }

    private void chooseLogMax() {
        RoundDialog d = new RoundDialog(this).title("大小上限");
        int[] vals = {512, 1024, 4096, 10240};
        int cur = Prefs.getI(this, Prefs.K_CONV_MAX_KB, 1024);
        for (int v : vals) {
            final int value = v;
            d.item((cur == v ? "✓ " : "") + v + " KB", () -> {
                Prefs.putI(this, Prefs.K_CONV_MAX_KB, value);
                selectCategory(cat);
            });
        }
        d.item("自定义", () -> editInt("大小上限 KB", Prefs.K_CONV_MAX_KB, 1024, 100, 20480, " KB"))
                .cancel()
                .show();
    }

    private void chooseCleanMin() {
        RoundDialog d = new RoundDialog(this).title("清理周期");
        int[] vals = {0, 30, 60, 240};
        String[] labels = {"关闭", "30 分", "60 分", "4 小时"};
        int cur = Prefs.getI(this, Prefs.K_CONV_CLEAN_MIN, 60);
        for (int i = 0; i < vals.length; i++) {
            final int value = vals[i];
            d.item((cur == value ? "✓ " : "") + labels[i], () -> {
                Prefs.putI(this, Prefs.K_CONV_CLEAN_MIN, value);
                selectCategory(cat);
            });
        }
        d.item("自定义", () -> editInt("清理间隔(分)", Prefs.K_CONV_CLEAN_MIN, 60, 0, 1440, " 分钟"))
                .cancel()
                .show();
    }

    private void confirmClearConv() {
        new RoundDialog(this)
                .title("清空记录")
                .text("此操作会删除本机对话记录。")
                .item("确认清空", () -> {
                    ConversationLog.clear(this);
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                    selectCategory(cat);
                })
                .cancel()
                .show();
    }

    private void showConvReadInfo() {
        new RoundDialog(this)
                .title("当前记录")
                .text("本机记录大小：" + formatBytes(ConversationLog.size(this)))
                .item("知道了", null)
                .show();
    }

    private void showWebSettings() {
        final String url = SettingsWebServer.url();
        new RoundDialog(this)
                .title("网页设置")
                .text("浏览器打开：" + url)
                .text("可配置所有设置，并实时查看屏幕")
                .item("在本机浏览器打开", () -> startActivity(new Intent(this,
                        com.magneo.compass.browser.BrowserActivity.class).putExtra("url", url)))
                .item("知道了", null)
                .show();
    }

    private void showConvWeb() {
        final String url = SettingsWebServer.url();
        new RoundDialog(this)
                .title("网页查看记录")
                .text("其他设备浏览器打开：" + url)
                .text("页面底部显示对话记录")
                .item("在本机浏览器打开", () -> startActivity(new Intent(this,
                        com.magneo.compass.browser.BrowserActivity.class).putExtra("url", url)))
                .item("知道了", null)
                .show();
    }

    private void saveCurrent() {
        // 摘要控制台的编辑动作会即时写入 Prefs；保留入口用于返回键/保存按钮一致反馈。
    }

    private String maskSecret(String v) {
        if (v == null || v.trim().isEmpty()) return "未设置";
        String s = v.trim();
        if (s.length() <= 4) return "已设置 ****";
        return "已设置 ****" + s.substring(s.length() - 4);
    }

    private String promptSummary(String s) {
        if (s == null || s.trim().isEmpty()) return "未设置";
        return compact(s.replace('\n', ' '));
    }

    private String compact(String s) {
        if (s == null || s.trim().isEmpty()) return "未设置";
        s = s.trim();
        if (s.length() <= 26) return s;
        return s.substring(0, 12) + "..." + s.substring(s.length() - 9);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        long kb = bytes / 1024;
        if (kb < 1024) return kb + " KB";
        return (kb / 1024) + " MB";
    }

    private void testChat() {
        saveCurrent();
        LlmClient llm = new LlmClient(this);
        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("user", "你好，请简短回复"));
        llm.chat(msgs, false, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {}
            @Override public void onDone(String full) {
                ui.post(() -> Toast.makeText(SettingsActivity.this,
                        full.length() > 120 ? full.substring(0, 120) + "..." : full, Toast.LENGTH_LONG).show());
            }
            @Override public void onError(String msg) {
                ui.post(() -> Toast.makeText(SettingsActivity.this, "失败: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private interface AngleCallback {
        void onAngle(float accumulatedDeg);
        void onEnd(float accumulatedDeg);
    }

    /** 圆环容器：转圈跟手，背景绘制一层低亮导航轨。 */
    private class RingPanel extends FrameLayout {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private AngleCallback cb;
        private float cx, cy;
        private float lastAng, accum;
        private boolean intercepting;

        RingPanel(android.content.Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void setAngleCallback(AngleCallback cb) { this.cb = cb; }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float r = Math.min(getWidth(), getHeight()) * 0.43f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpF(SettingsActivity.this, 1.2f));
            p.setColor(Color.argb(92, 145, 116, 48));
            c.drawCircle(cx, cy, r, p);
            p.setColor(Color.argb(42, 70, 210, 214));
            c.drawCircle(cx, cy, r - Ui.dpF(SettingsActivity.this, 30), p);
            p.setStrokeWidth(Ui.dpF(SettingsActivity.this, 0.8f));
            p.setColor(Color.argb(70, 120, 98, 50));
            for (int i = 0; i < CAT_COUNT; i++) {
                float a = (float) Math.toRadians(-90 + i * SLOT_DEG);
                float x1 = cx + (r - Ui.dpF(SettingsActivity.this, 28)) * (float) Math.cos(a);
                float y1 = cy + (r - Ui.dpF(SettingsActivity.this, 28)) * (float) Math.sin(a);
                float x2 = cx + (r + Ui.dpF(SettingsActivity.this, 22)) * (float) Math.cos(a);
                float y2 = cy + (r + Ui.dpF(SettingsActivity.this, 22)) * (float) Math.sin(a);
                c.drawLine(x1, y1, x2, y2, p);
            }
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cx = w / 2f;
            cy = h / 2f;
        }

        private float ang(MotionEvent e) {
            return (float) Math.toDegrees(Math.atan2(e.getY() - cy, e.getX() - cx));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastAng = ang(ev);
                    accum = 0;
                    intercepting = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float a = ang(ev);
                    float da = norm(a - lastAng);
                    lastAng = a;
                    accum += da;
                    if (!intercepting && Math.abs(accum) > 5f) {
                        intercepting = true;
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastAng = ang(ev);
                    accum = 0;
                    intercepting = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float a = ang(ev);
                    float da = norm(a - lastAng);
                    lastAng = a;
                    accum += da;
                    if (!intercepting && Math.abs(accum) > 5f) intercepting = true;
                    if (intercepting && cb != null) cb.onAngle(accum);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (intercepting && cb != null) cb.onEnd(accum);
                    else {
                        int w = getWidth(), h = getHeight();
                        float cx2 = w / 2f, cy2 = h / 2f;
                        float dx = ev.getX() - cx2, dy = ev.getY() - cy2;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        float r2 = Math.min(w, h) * 0.43f;
                        if (dist > r2 * 0.7f && dist < r2 * 1.4f) {
                            float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
                            int idx = sectorIdx(deg);
                            if (idx >= 0 && idx < getChildCount()) {
                                View child = getChildAt(idx);
                                if (child != null) child.performClick();
                            }
                        }
                    }
                    intercepting = false;
                    accum = 0;
                    return true;
            }
            return true;
        }

        private int sectorIdx(float deg) {
            return ((int) Math.floor((deg + 90f) / SLOT_DEG) + CAT_COUNT) % CAT_COUNT;
        }
    }

    /** 曲线缩放 ScrollView：子项按距视口中心距离动态缩放 + 透明度变化。 */
    private class CurvedScrollView extends ScrollView {
        private LinearLayout body;
        CurvedScrollView(android.content.Context c) { super(c); }
        void setBody(LinearLayout b) { this.body = b; }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            applyCurve();
        }

        void applyCurve() {
            if (body == null) return;
            int vpH = getHeight();
            if (vpH == 0) return;
            float vpCenter = vpH / 2f;
            for (int i = 0; i < body.getChildCount(); i++) {
                View child = body.getChildAt(i);
                float childCenter = child.getTop() + child.getHeight() / 2f - getScrollY();
                float dist = Math.abs(childCenter - vpCenter);
                float n = Math.min(1f, dist / vpCenter);
                float scale = 1f - n * 0.22f;
                float alpha = 1f - n * 0.35f;
                child.setScaleX(scale);
                child.setScaleY(scale);
                child.setAlpha(Math.max(0.42f, alpha));
                child.setPivotX(child.getWidth() / 2f);
                child.setPivotY(childCenter < vpCenter ? child.getHeight() : 0);
            }
        }
    }

    private void resetHome() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "pm clear-package-preferred-activities com.magneo.compass"});
                int code = p.waitFor();
                ui.post(() -> {
                    homeStatus = code == 0 ? "已清除默认桌面，按 Home 键重新选择"
                            : "需要 root 或手动到系统设置清除默认值";
                    if (homeStatusView != null) homeStatusView.setText(homeStatus);
                    Toast.makeText(this, homeStatus, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    homeStatus = "需要 root 或手动清除默认值";
                    if (homeStatusView != null) homeStatusView.setText(homeStatus);
                    Toast.makeText(this, homeStatus, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
