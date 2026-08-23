package com.magneo.compass.voice;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.CompassBackground;
import com.magneo.compass.Prefs;
import com.magneo.compass.R;
import com.magneo.compass.RoundDialog;
import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.ui.RoundFrame;
import com.magneo.compass.ui.Ui;

import okhttp3.Call;

/** 调试用：手动验证云端 TTS 合成和设备 MediaPlayer 播放。 */
public class TtsTestActivity extends com.magneo.compass.BaseActivity {
    private static final String TAG = "TtsTestActivity";
    private static final String TEST_TEXT = "真理罗盘云端语音测试。";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView stageView;
    private TextView statusView;
    private TextView configView;
    private TextView hintView;
    private Button startButton;
    private Button stopButton;
    private Button detailButton;
    private Call currentCall;
    private int serial = 0;
    private boolean running = false;
    private String lastReport = "还没有开始测试。";
    private long requestStartMs;
    private long audioReadyMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildView());
        refreshConfig();
        setIdle();
    }

    private View buildView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new CompassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER_HORIZONTAL);
        shell.setPadding(Ui.dp(this, 44), Ui.dp(this, 34), Ui.dp(this, 44), Ui.dp(this, 34));

        TextView title = new TextView(this);
        Ui.styleTitle(title);
        title.setText("语音自检");
        title.setTextSize(18);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundResource(R.drawable.bg_pill_dark);
        title.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.42f), Ui.dp(this, 34));
        titleLp.setMargins(0, 0, 0, Ui.dp(this, 16));
        shell.addView(title, titleLp);

        RoundFrame dial = new RoundFrame(this, true, true, 22);
        LinearLayout dialBody = new LinearLayout(this);
        dialBody.setOrientation(LinearLayout.VERTICAL);
        dialBody.setGravity(Gravity.CENTER);

        stageView = new TextView(this);
        stageView.setTextColor(Ui.COLOR_GOLD);
        stageView.setTextSize(24);
        stageView.setGravity(Gravity.CENTER);
        stageView.setSingleLine(true);
        dialBody.addView(stageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        Ui.styleSubtle(statusView);
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(false);
        statusView.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, Ui.dp(this, 10), 0, 0);
        dialBody.addView(statusView, statusLp);

        TextView sample = new TextView(this);
        Ui.styleBody(sample);
        sample.setTextSize(14);
        sample.setGravity(Gravity.CENTER);
        sample.setText(TEST_TEXT);
        sample.setSingleLine(false);
        LinearLayout.LayoutParams sampleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sampleLp.setMargins(0, Ui.dp(this, 18), 0, 0);
        dialBody.addView(sample, sampleLp);

        dial.addView(dialBody, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(Ui.dp(this, 250), Ui.dp(this, 250));
        dialLp.setMargins(0, 0, 0, Ui.dp(this, 12));
        shell.addView(dial, dialLp);

        configView = new TextView(this);
        Ui.styleSubtle(configView);
        configView.setTextSize(12);
        configView.setGravity(Gravity.CENTER);
        configView.setSingleLine(false);
        configView.setLineSpacing(Ui.dp(this, 2), 1f);
        configView.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cfgLp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        shell.addView(configView, cfgLp);

        hintView = new TextView(this);
        Ui.styleSubtle(hintView);
        hintView.setTextSize(12);
        hintView.setGravity(Gravity.CENTER);
        hintView.setSingleLine(true);
        hintView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24));
        hintLp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 8));
        shell.addView(hintView, hintLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        startButton = pill("开始");
        stopButton = pill("停止");
        detailButton = pill("详情");
        Button backButton = pill("返回");
        startButton.setOnClickListener(v -> startTest());
        stopButton.setOnClickListener(v -> stopActive("已停止"));
        detailButton.setOnClickListener(v -> showDetails());
        backButton.setOnClickListener(v -> {
            stopActive("已退出");
            finish();
        });
        addButton(buttons, startButton);
        addButton(buttons, stopButton);
        addButton(buttons, detailButton);
        addButton(buttons, backButton);
        shell.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));

        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private Button pill(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        Ui.stylePillButton(b);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void addButton(LinearLayout parent, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        parent.addView(b, lp);
    }

    private void refreshConfig() {
        LlmClient llm = new LlmClient(this);
        boolean voiceKey = !Prefs.get(this, Prefs.K_VOICE_API_KEY, "").trim().isEmpty();
        boolean apiKey = !Prefs.get(this, Prefs.K_API_KEY, "").trim().isEmpty();
        boolean localFirst = Prefs.getB(this, Prefs.K_LOCAL_TTS_FIRST, false);
        String keyState = voiceKey ? "语音 Key 已设" : (apiKey ? "复用大模型 Key" : "未设置 Key");
        configView.setText("TTS  " + compact(llm.ttsModel, 18) + " / "
                + compact(llm.ttsVoice, 18)
                + "\n" + compact(speechEndpoint(llm.ttsUrl), 42)
                + " · " + keyState + " · 本地回退" + (localFirst ? "开" : "关"));
    }

    private void setIdle() {
        running = false;
        currentCall = null;
        stageView.setText("待测试");
        statusView.setText("点击开始后才会请求云端 TTS。\n不会自动播报。");
        hintView.setText("用于检查 /v1/audio/speech、音色和设备播放。");
        updateButtons();
    }

    private void startTest() {
        stopActive(null);
        refreshConfig();
        final int runId = ++serial;
        running = true;
        requestStartMs = SystemClock.elapsedRealtime();
        audioReadyMs = 0L;
        lastReport = "测试文本：" + TEST_TEXT + "\n\n" + configForReport();
        stageView.setText("合成中");
        statusView.setText("正在请求云端 TTS...\n等待音频返回");
        hintView.setText("测试中不会启用本地 TTS 回退。");
        updateButtons();

        Log.i(TAG, "TTS self-test start " + compact(configForReport().replace('\n', ' '), 160));
        LlmClient llm = new LlmClient(this);
        currentCall = llm.synthesize(TEST_TEXT, new LlmClient.BytesCallback() {
            @Override public void onResult(byte[] audio) {
                onResult(audio, "");
            }

            @Override public void onResult(byte[] audio, String contentType) {
                ui.post(() -> handleAudio(runId, audio, contentType));
            }

            @Override public void onError(String msg) {
                ui.post(() -> handleError(runId, msg));
            }
        });
    }

    private void handleAudio(int runId, byte[] audio, String contentType) {
        if (runId != serial) return;
        currentCall = null;
        if (audio == null || audio.length == 0) {
            handleError(runId, "TTS 返回空音频");
            return;
        }
        audioReadyMs = elapsed();
        long playStartMs = SystemClock.elapsedRealtime();
        stageView.setText("播报中");
        statusView.setText("音频 " + formatBytes(audio.length) + "\n类型 "
                + emptyToDash(contentType));
        hintView.setText("正在播放，必要时可点停止。");
        lastReport = "TTS 自检成功"
                + "\ntts_audio_ms=" + audioReadyMs
                + "\nbytes=" + audio.length
                + "\ncontent_type=" + emptyToDash(contentType)
                + "\nplay_start_ms=" + (playStartMs - requestStartMs)
                + "\n\n" + configForReport();
        Log.i(TAG, lastReport.replace('\n', ' '));

        CloudTts.play(this, audio, contentType, () -> ui.post(() -> {
            if (runId != serial) return;
            running = false;
            stageView.setText("完成");
            statusView.setText("播放完成\n总耗时 " + elapsed() + " ms");
            hintView.setText("可以重试，或查看详情。");
            updateButtons();
            Toast.makeText(this, "云端 TTS 自检完成", Toast.LENGTH_SHORT).show();
        }));
    }

    private void handleError(int runId, String msg) {
        if (runId != serial) return;
        running = false;
        currentCall = null;
        String clean = msg == null || msg.trim().isEmpty() ? "未知错误" : msg.trim();
        stageView.setText("失败");
        statusView.setText(compact(clean, 72) + "\n耗时 " + elapsed() + " ms");
        hintView.setText("点详情查看完整错误，或点重试。");
        lastReport = "TTS 自检失败"
                + "\nerror=" + clean
                + "\nelapsed_ms=" + elapsed()
                + "\n\n" + configForReport();
        Log.w(TAG, lastReport);
        updateButtons();
        Toast.makeText(this, "云端 TTS 自检失败", Toast.LENGTH_SHORT).show();
    }

    private void stopActive(String reason) {
        serial++;
        if (currentCall != null) {
            try { currentCall.cancel(); } catch (Throwable ignored) {}
            currentCall = null;
        }
        CloudTts.stop();
        if (running || reason != null) {
            running = false;
            if (stageView != null) stageView.setText(reason == null ? "待测试" : reason);
            if (statusView != null) statusView.setText("当前没有播放中的测试。");
            if (hintView != null) hintView.setText("可以重新开始一次云端 TTS 自检。");
            lastReport = (reason == null ? "测试已取消" : reason) + "\n\n" + configForReport();
            updateButtons();
        }
    }

    private void showDetails() {
        new RoundDialog(this)
                .title("自检详情")
                .text(lastReport)
                .item("重新测试", this::startTest)
                .cancel()
                .show();
    }

    private void updateButtons() {
        if (startButton == null) return;
        startButton.setText(running ? "重测" : "开始");
        stopButton.setEnabled(running || CloudTts.isPlaying());
        stopButton.setTextColor(stopButton.isEnabled() ? Ui.COLOR_TEXT : Ui.COLOR_TEXT_MUTED);
        detailButton.setEnabled(lastReport != null);
        detailButton.setTextColor(detailButton.isEnabled() ? Ui.COLOR_TEXT : Ui.COLOR_TEXT_MUTED);
    }

    private long elapsed() {
        if (requestStartMs == 0L) return 0L;
        return SystemClock.elapsedRealtime() - requestStartMs;
    }

    private String configForReport() {
        LlmClient llm = new LlmClient(this);
        boolean voiceKey = !Prefs.get(this, Prefs.K_VOICE_API_KEY, "").trim().isEmpty();
        boolean apiKey = !Prefs.get(this, Prefs.K_API_KEY, "").trim().isEmpty();
        return "tts_url=" + speechEndpoint(llm.ttsUrl)
                + "\ntts_model=" + emptyToDash(llm.ttsModel)
                + "\ntts_voice=" + emptyToDash(llm.ttsVoice)
                + "\nvoice_key_set=" + voiceKey
                + "\napi_key_set=" + apiKey
                + "\nlocal_tts_first=" + Prefs.getB(this, Prefs.K_LOCAL_TTS_FIRST, false)
                + "\ntts_audio_ms=" + audioReadyMs;
    }

    private static String speechEndpoint(String baseOrEndpoint) {
        String s = baseOrEndpoint == null ? "" : baseOrEndpoint.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (s.startsWith("ws://")) s = "http://" + s.substring("ws://".length());
        else if (s.startsWith("wss://")) s = "https://" + s.substring("wss://".length());
        if (s.endsWith("/audio/speech")) return s;
        if (s.endsWith("/v1")) return s + "/audio/speech";
        if (!s.contains("/v1/")) return s + "/v1/audio/speech";
        return s + "/audio/speech";
    }

    private static String compact(String s, int max) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() <= max) return v;
        if (max <= 3) return v.substring(0, max);
        return v.substring(0, max - 3) + "...";
    }

    private static String emptyToDash(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s.trim();
    }

    private static String formatBytes(int bytes) {
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
        if (bytes >= 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
        return bytes + " B";
    }

    @Override
    protected void onBackExit() {
        stopActive("已退出");
    }

    @Override
    protected void onDestroy() {
        stopActive(null);
        super.onDestroy();
    }
}
