package com.magneo.compass;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.web.SettingsWebServer;

import java.util.ArrayList;
import java.util.List;

/** 罗盘式设置 v0.2：外圈 7 个分类小圆盘，中央圆形面板显示选项。 */
public class SettingsActivity extends BaseActivity {

    private static final String[] CATS = {"模型", "语音", "视觉", "监听", "浏览", "桌面", "应用", "记录"};
    private static final int CAT_COUNT = CATS.length;

    private static final float SLOT_DEG = 360f / CAT_COUNT; // 每格角度
    private int cat = 0;
    private int offset = 0;
    private float baseRot = 0;
    private float dragRot = 0;
    private int lastOffset = -1;
    private RingPanel ring;
    private FrameLayout centerContent;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Spinner spProvider;
    private EditText eApiKey, eBaseUrl, eTextModel, eVisionModel;
    private EditText eAsrUrl, eAsrModel, eTtsUrl, eTtsModel, eTtsVoice;
    private CheckBox cLocal;
    private CheckBox cVision;
    private EditText eVisionInterval;
    private CheckBox cVad;
    private EditText eVadSens;
    private EditText eSearch;
    private CheckBox cUa, cNoImg, cSsl;
    private EditText eConvMaxKb, eConvCleanMin;
    private EditText eSysVoice, eSysVision;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new CompassBackground(this), 0);

        centerContent = new FrameLayout(this);
        centerContent.setBackgroundResource(R.drawable.bg_dialog_oval);
        centerContent.setClipToOutline(true);
        centerContent.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        root.addView(centerContent, new FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER));

        ring = new RingPanel(this);
        ring.setAngleCallback(new AngleCallback() {
            @Override public void onAngle(float a) { onDragAngle(a); }
            @Override public void onEnd(float a) { onDragEnd(a); }
        });
        root.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

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
        float r = Math.min(w, h) * 0.425f;
        int s = dp(44);
        for (int i = 0; i < CAT_COUNT; i++) {
            int idx = ((off + i) % CAT_COUNT + CAT_COUNT) % CAT_COUNT;
            boolean sel = i == 0; // 顶部槽位=当前分类
            TextView tv = new TextView(this);
            tv.setText(CATS[idx]);
            tv.setTextColor(sel ? Color.rgb(212, 175, 55) : Color.rgb(232, 220, 192));
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(sel ? R.drawable.bg_oval_gold : R.drawable.bg_oval_dark);
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
            float angle = (float) Math.toRadians(-90 + i * (360f / CAT_COUNT) + visRot);
            int x = (int) (cx + r * Math.cos(angle) - s / 2f);
            int y = (int) (cy + r * Math.sin(angle) - s / 2f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
            lp.leftMargin = x;
            lp.topMargin = y;
            ring.addView(tv, lp);
        }
    }

    private void selectCategory(int i) {
        saveCurrent();
        cat = i;
        centerContent.removeAllViews();
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.TRANSPARENT);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        body.setPadding(p, dp(10), p, dp(16));
        sc.addView(body);
        centerContent.addView(sc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(CATS[i] + " 设置 v0.2");
        title.setTextSize(17);
        title.setTextColor(Color.rgb(212, 175, 55));
        title.setGravity(Gravity.CENTER);
        body.addView(title);

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
            err.setTextColor(Color.rgb(231, 76, 60));
            err.setTextSize(13);
            body.addView(err);
        }
        Button save = pill("保存本页");
        save.setOnClickListener(v -> {
            saveCurrent();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
        body.addView(save);
    }

    @Override
    protected void onBackExit() {
        saveCurrent();
    }

    private void buildLlm(LinearLayout b) {
        spProvider = new Spinner(this);
        spProvider.setBackgroundResource(R.drawable.bg_rect_gold);
        spProvider.setPadding(dp(10), dp(5), dp(10), dp(5));
        ArrayAdapter<String> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names());
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProvider.setAdapter(pa);
        spProvider.setSelection(indexOf(Prefs.get(this, Prefs.K_PROVIDER, "通义千问")));
        spProvider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                ProviderConfig pc = ProviderConfig.byName(names()[pos]);
                if (eBaseUrl.getText().toString().trim().isEmpty()) eBaseUrl.setText(pc.baseUrl);
                if (eTextModel.getText().toString().trim().isEmpty()) eTextModel.setText(pc.textModel);
                if (eVisionModel.getText().toString().trim().isEmpty()) eVisionModel.setText(pc.visionModel);
                if (eAsrUrl != null && Prefs.get(SettingsActivity.this, Prefs.K_ASR_URL, "").isEmpty()) eAsrUrl.setText(pc.asrUrl);
                if (eAsrModel != null && Prefs.get(SettingsActivity.this, Prefs.K_ASR_MODEL, "").isEmpty()) eAsrModel.setText(pc.asrModel);
                if (eTtsUrl != null && Prefs.get(SettingsActivity.this, Prefs.K_TTS_URL, "").isEmpty()) eTtsUrl.setText(pc.ttsUrl);
                if (eTtsModel != null && Prefs.get(SettingsActivity.this, Prefs.K_TTS_MODEL, "").isEmpty()) eTtsModel.setText(pc.ttsModel);
                if (eTtsVoice != null && Prefs.get(SettingsActivity.this, Prefs.K_TTS_VOICE, "").isEmpty()) eTtsVoice.setText(pc.ttsVoice);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        b.addView(spProvider);
        eApiKey = field(b, "API Key", Prefs.get(this, Prefs.K_API_KEY, ""));
        eBaseUrl = field(b, "Base URL", Prefs.get(this, Prefs.K_BASE_URL, ""));
        eTextModel = field(b, "文本模型", Prefs.get(this, Prefs.K_TEXT_MODEL, ""));
        eVisionModel = field(b, "视觉模型", Prefs.get(this, Prefs.K_VISION_MODEL, ""));
        Button test = pill("测试对话");
        test.setOnClickListener(v -> testChat());
        b.addView(test);
    }

    private void buildVoice(LinearLayout b) {
        eAsrUrl = field(b, "ASR 地址", Prefs.get(this, Prefs.K_ASR_URL, ""));
        eAsrModel = field(b, "ASR 模型", Prefs.get(this, Prefs.K_ASR_MODEL, ""));
        eTtsUrl = field(b, "TTS 地址", Prefs.get(this, Prefs.K_TTS_URL, ""));
        eTtsModel = field(b, "TTS 模型", Prefs.get(this, Prefs.K_TTS_MODEL, ""));
        eTtsVoice = field(b, "TTS 音色", Prefs.get(this, Prefs.K_TTS_VOICE, ""));
        cLocal = check(b, "本地优先（离线可用）", Prefs.getB(this, Prefs.K_LOCAL_TTS_FIRST, true));
        eSysVoice = fieldArea(b, "语音系统提示词", Prefs.get(this, Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE));
    }

    private void buildVision(LinearLayout b) {
        cVision = check(b, "灵眼自动感知", Prefs.getB(this, Prefs.K_VISION_ENABLED, true));
        eVisionInterval = field(b, "间隔(秒)", String.valueOf(Prefs.getI(this, Prefs.K_VISION_INTERVAL, 2)));
        eSysVision = fieldArea(b, "视觉系统提示词", Prefs.get(this, Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION));
    }

    private void buildVad(LinearLayout b) {
        cVad = check(b, "持续监听（VAD）", Prefs.getB(this, Prefs.K_VAD_ENABLED, false));
        eVadSens = field(b, "灵敏度", String.valueOf(Prefs.getI(this, Prefs.K_VAD_SENSITIVITY, 600)));
    }

    private void buildBrowser(LinearLayout b) {
        eSearch = field(b, "搜索引擎", Prefs.get(this, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"));
        cUa = check(b, "桌面版 UA", Prefs.getB(this, Prefs.K_UA_DESKTOP, false));
        cNoImg = check(b, "无图模式", Prefs.getB(this, Prefs.K_NO_IMAGES, false));
        cSsl = check(b, "兼容 CA（忽略证书）", Prefs.getB(this, Prefs.K_IGNORE_SSL, false));
    }

    private void buildHome(LinearLayout b) {
        Button setHome = pill("设为默认桌面");
        setHome.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setComponent(new ComponentName(this, FakeHomeActivity.class));
            startActivity(i);
        });
        b.addView(setHome);
        Button resetHome = pill("恢复原桌面");
        resetHome.setOnClickListener(v -> resetHome());
        b.addView(resetHome);
    }

    private void buildApps(LinearLayout b) {
        Button web = pill("局域网网页设置");
        web.setOnClickListener(v -> {
            final String url = com.magneo.compass.web.SettingsWebServer.url();
            new RoundDialog(this)
                    .title("网页设置")
                    .text("浏览器打开：" + url)
                    .text("可配置所有设置，并实时查看屏幕")
                    .item("在本机浏览器打开", () -> {
                        startActivity(new Intent(this, com.magneo.compass.browser.BrowserActivity.class)
                                .putExtra("url", url));
                    })
                    .item("知道了", null)
                    .show();
        });
        b.addView(web);
        Button pri = pill("优先应用（前 8 位）");
        pri.setOnClickListener(v -> startActivity(new Intent(this, PriorityAppsActivity.class)));
        b.addView(pri);
        Button back = pill("保存并返回罗盘");
        back.setOnClickListener(v -> { saveCurrent(); finish(); });
        b.addView(back);
    }

    private void buildConv(LinearLayout b) {
        eConvMaxKb = field(b, "大小上限KB", String.valueOf(Prefs.getI(this, Prefs.K_CONV_MAX_KB, 1024)));
        eConvCleanMin = field(b, "清理间隔分", String.valueOf(Prefs.getI(this, Prefs.K_CONV_CLEAN_MIN, 60)));
        TextView tip = new TextView(this);
        tip.setText("对话记录会保存识别出的语音和 AI 回答，网页端可实时查看。\n上限 100~20480KB；清理间隔 0=关闭定时清理（超上限仍自动裁剪）。");
        tip.setTextColor(Color.rgb(138, 130, 114));
        tip.setTextSize(11);
        tip.setLineSpacing(dp(2), 1f);
        b.addView(tip);
        Button view = pill("在网页端查看记录");
        view.setOnClickListener(v -> {
            final String url = SettingsWebServer.url();
            new RoundDialog(this)
                    .title("网页查看对话记录")
                    .text("其他设备浏览器打开：" + url)
                    .text("页面底部「对话记录」区块实时显示")
                    .item("在本机浏览器打开", () -> startActivity(new Intent(this, com.magneo.compass.browser.BrowserActivity.class)
                            .putExtra("url", url)))
                    .item("知道了", null)
                    .show();
        });
        b.addView(view);
        Button clear = pill("清空对话记录");
        clear.setOnClickListener(v -> {
            ConversationLog.clear(this);
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
        b.addView(clear);
    }

    private void saveCurrent() {
        switch (cat) {
            case 0:
                if (spProvider == null) break;
                Prefs.put(this, Prefs.K_PROVIDER, names()[spProvider.getSelectedItemPosition()]);
                Prefs.put(this, Prefs.K_API_KEY, eApiKey.getText().toString().trim());
                Prefs.put(this, Prefs.K_BASE_URL, eBaseUrl.getText().toString().trim());
                Prefs.put(this, Prefs.K_TEXT_MODEL, eTextModel.getText().toString().trim());
                Prefs.put(this, Prefs.K_VISION_MODEL, eVisionModel.getText().toString().trim());
                break;
            case 1:
                if (eAsrUrl == null) break;
                Prefs.put(this, Prefs.K_ASR_URL, eAsrUrl.getText().toString().trim());
                Prefs.put(this, Prefs.K_ASR_MODEL, eAsrModel.getText().toString().trim());
                Prefs.put(this, Prefs.K_TTS_URL, eTtsUrl.getText().toString().trim());
                Prefs.put(this, Prefs.K_TTS_MODEL, eTtsModel.getText().toString().trim());
                Prefs.put(this, Prefs.K_TTS_VOICE, eTtsVoice.getText().toString().trim());
                Prefs.putB(this, Prefs.K_LOCAL_TTS_FIRST, cLocal.isChecked());
                if (eSysVoice != null) Prefs.put(this, Prefs.K_SYS_PROMPT_VOICE, eSysVoice.getText().toString().trim());
                break;
            case 2:
                if (cVision == null) break;
                Prefs.putB(this, Prefs.K_VISION_ENABLED, cVision.isChecked());
                try { Prefs.putI(this, Prefs.K_VISION_INTERVAL, Integer.parseInt(eVisionInterval.getText().toString().trim())); } catch (Exception ignored) {}
                if (eSysVision != null) Prefs.put(this, Prefs.K_SYS_PROMPT_VISION, eSysVision.getText().toString().trim());
                break;
            case 3:
                if (cVad == null) break;
                Prefs.putB(this, Prefs.K_VAD_ENABLED, cVad.isChecked());
                try { Prefs.putI(this, Prefs.K_VAD_SENSITIVITY, Integer.parseInt(eVadSens.getText().toString().trim())); } catch (Exception ignored) {}
                break;
            case 4:
                if (eSearch == null) break;
                Prefs.put(this, Prefs.K_SEARCH_ENGINE, eSearch.getText().toString().trim());
                Prefs.putB(this, Prefs.K_UA_DESKTOP, cUa.isChecked());
                Prefs.putB(this, Prefs.K_NO_IMAGES, cNoImg.isChecked());
                Prefs.putB(this, Prefs.K_IGNORE_SSL, cSsl.isChecked());
                break;
            case 7:
                if (eConvMaxKb == null) break;
                try { Prefs.putI(this, Prefs.K_CONV_MAX_KB, Math.max(100, Math.min(20480, Integer.parseInt(eConvMaxKb.getText().toString().trim())))); } catch (Exception ignored) {}
                try { Prefs.putI(this, Prefs.K_CONV_CLEAN_MIN, Math.max(0, Math.min(1440, Integer.parseInt(eConvCleanMin.getText().toString().trim())))); } catch (Exception ignored) {}
                break;
        }
    }

    private EditText field(LinearLayout b, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(rlp);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(Color.rgb(212, 175, 55));
        lab.setTextSize(12);
        lab.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(lab, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.rgb(232, 220, 192));
        e.setHintTextColor(Color.rgb(120, 114, 98));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackgroundResource(R.drawable.bg_rect_gold);
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        e.setGravity(Gravity.CENTER);
        row.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.addView(row);
        return e;
    }

    private EditText fieldArea(LinearLayout b, String label, String value) {
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(Color.rgb(212, 175, 55));
        lab.setTextSize(12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMargins(0, dp(8), 0, dp(2));
        b.addView(lab, llp);

        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.rgb(232, 220, 192));
        e.setHintTextColor(Color.rgb(120, 114, 98));
        e.setSingleLine(false);
        e.setMinLines(3);
        e.setMaxLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackgroundResource(R.drawable.bg_rect_gold);
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        e.setLayoutParams(lp);
        b.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout b, String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.rgb(232, 220, 192));
        c.setTextSize(13);
        c.setChecked(value);
        b.addView(c);
        return c;
    }

    private Button pill(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12);
        b.setTextColor(Color.rgb(232, 220, 192));
        b.setBackgroundResource(R.drawable.bg_rect_gold);
        b.setMinWidth(0);
        b.setMinHeight(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private String[] names() {
        ProviderConfig[] ps = ProviderConfig.all();
        String[] n = new String[ps.length];
        for (int i = 0; i < ps.length; i++) n[i] = ps[i].name;
        return n;
    }

    private int indexOf(String name) {
        String[] n = names();
        for (int i = 0; i < n.length; i++) if (n[i].equals(name)) return i;
        return 0;
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
                        full.length() > 120 ? full.substring(0, 120) + "…" : full, Toast.LENGTH_LONG).show());
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

    /** 圆环容器：转圈跟手（像应用抽屉），点按穿透给分类盘。 */
    private class RingPanel extends FrameLayout {
        private AngleCallback cb;
        private float cx, cy;
        private float lastAng, accum;
        private boolean intercepting;

        RingPanel(android.content.Context c) { super(c); }

        void setAngleCallback(AngleCallback cb) { this.cb = cb; }

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
                    if (!intercepting && Math.abs(accum) > 8f) {
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
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!intercepting) return false;
                    float a = ang(ev);
                    accum += norm(a - lastAng);
                    lastAng = a;
                    if (cb != null) cb.onAngle(accum);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!intercepting) return false;
                    if (cb != null) cb.onEnd(accum);
                    intercepting = false;
                    accum = 0;
                    return true;
            }
            return false;
        }
    }

    private void resetHome() {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "pm clear-package-preferred-activities com.magneo.compass"});
                int code = p.waitFor();
                ui.post(() -> Toast.makeText(this,
                        code == 0 ? "已清除默认桌面（按 Home 键重新选择）" : "需要 root 或手动到系统设置清除默认值",
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "需要 root 或手动清除默认值", Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
