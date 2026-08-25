package com.magneo.compass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.magneo.compass.browser.BrowserActivity;
import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.netfs.FileBrowserActivity;
import com.magneo.compass.netfs.MusicPlayerActivity;
import com.magneo.compass.vision.VisionActivity;
import com.magneo.compass.voice.LocalTts;
import com.magneo.compass.voice.VadService;
import com.magneo.compass.voice.VoiceController;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/** 真理罗盘桌面主屏：HOME 桌面 + 罗盘 + 传感器 + 语音/灵眼入口。 */
public class MainActivity extends BaseActivity implements CompassView.Actions {

    private static volatile MainActivity activeMain;

    private CompassHostView view;
    private SensorHub hub;
    private VoiceController voice;
    private WifiLocator locator;
    private Call oracleCall;
    private long oracleUiUpdateMs = 0;
    private final Handler uiTicker = new Handler();
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (view != null) view.postInvalidate();   // 时钟/低频状态心跳，不依赖传感器事件
            uiTicker.postDelayed(this, idleFrameDelayMs());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QuitFix.apply(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        Prefs.restoreBackupIfPresent(this);
        if (Prefs.get(this, Prefs.K_PROVIDER, "").isEmpty()) {
            ProviderConfig.apply(this, ProviderConfig.openaiCompatible());
        }
        // MT6580 渲染全屏罗盘较贵：静置时交给 1fps 时钟，明显转动时再提升到约 8fps。
        long[] lastDraw = {0};
        float[] lastAzimuth = {Float.NaN};
        float[] lastPitch = {Float.NaN};
        float[] lastRoll = {Float.NaN};
        int[] lastBattery = {-1};
        hub = new SensorHub(this, () -> {
            long now = System.currentTimeMillis();
            float accelMove = (float) Math.abs(Math.sqrt(hub.ax * hub.ax + hub.ay * hub.ay + hub.az * hub.az) - 9.80665f);
            boolean oracleMotion = view != null && view.isOracleDetailActive()
                    && (view.isOracleCollecting() || accelMove > 0.25f);
            boolean changed = angleChanged(hub.azimuth, lastAzimuth[0], 1.5f)
                    || valueChanged(hub.pitch, lastPitch[0], 1.4f)
                    || valueChanged(hub.roll, lastRoll[0], 1.4f)
                    || hub.battery != lastBattery[0]
                    || oracleMotion;
            long minGap = activeFrameMinGapMs(oracleMotion);
            if (changed && now - lastDraw[0] >= minGap) {
                lastDraw[0] = now;
                lastAzimuth[0] = hub.azimuth;
                lastPitch[0] = hub.pitch;
                lastRoll[0] = hub.roll;
                lastBattery[0] = hub.battery;
                view.postInvalidate();
            }
        });
        view = new CompassHostView(this, hub, this);
        setContentView(view);
        // GPS 硬件不可用时用系统网络/WiFi 定位兜底（每 30s 更新一次，onResume 启动）
        locator = new WifiLocator(this);

        voice = VoiceController.get(this, this::setMainVoiceStatus);
        LocalTts.ensureInit(this);
        com.magneo.compass.web.SettingsWebServer.start(this);
        // 自动启动 ADB TCP / frpc（按网页配置）；摄像头推流不再随主屏冷启，改由 Vision 前后 onResume/onPause 或 web /cam/start|stop 按需启停，避免冷启即占 CPU
        new Thread(() -> {
            try {
                logAuto(RemoteAccessWatchdog.tickOnce(getApplicationContext()));
                RemoteAccessWatchdog.start(getApplicationContext());
            } catch (Throwable t) {
                logAuto("frpc auto FAILED: " + t);
            }
        }, "auto-stream").start();
        // 兜底：应用每次冷启动清理上次崩溃遗留的推流编码进程，防止 CPU 被占满；
        // 若上次推流是被强杀/崩溃终止的，屏幕常亮设置会残留，一并恢复自动休眠
        new Thread(() -> {
            com.magneo.compass.web.H264SurfaceStreamer.cleanupStale();
            com.magneo.compass.web.ScreenAwake.off();
        }, "stream-cleanup").start();
        ConversationLog.startCleaner(this);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "crash.log");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
                java.io.PrintWriter pw = new java.io.PrintWriter(fo);
                pw.println("=== " + new java.util.Date() + " thread=" + thread.getName());
                throwable.printStackTrace(pw);
                pw.close();
            } catch (Exception ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        syncVoiceServiceFromPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeMain = this;
        view.applyRendererPrefs();
        view.onHostResume();
        hub.start();
        FlashlightController.restoreIfRequested();
        applyLocationPrefs();
        uiTicker.removeCallbacks(uiTick);
        uiTicker.post(uiTick);
        if (voice != null) voice = VoiceController.get(this, this::setMainVoiceStatus);
        syncVoiceServiceFromPrefs();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        if (activeMain == this) activeMain = null;
        cancelOracleAi();
        FlashlightController.releaseHardwareKeepingRequest();
        if (view != null) view.onHostPause();
        hub.stop();
        if (locator != null) locator.stop();
        uiTicker.removeCallbacks(uiTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiTicker.removeCallbacks(uiTick);
        if (locator != null) locator.stop();
        cancelOracleAi();
        FlashlightController.turnOff();
        voice.shutdown();
        super.onDestroy();
    }

    public static void applyLocationPrefsToActive() {
        final MainActivity a = activeMain;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() { a.applyLocationPrefs(); }
        });
    }

    private void applyLocationPrefs() {
        boolean wifiIpLoc = Prefs.locSourceWifiIp(this);
        hub.setGpsEnabled(Prefs.locSourceGpsDiag(this));
        if (view != null) view.syncHardwareDemand();
        if (locator != null) {
            if (wifiIpLoc) {
                locator.start((lat, lon, acc, src) -> {
                    hub.netLat = lat; hub.netLon = lon; hub.netAcc = acc; hub.netSrc = src;
                    view.postInvalidate();
                });
            } else {
                locator.stop();
            }
        }
    }

    private long idleFrameDelayMs() {
        String mode = Prefs.mainFpsMode(this);
        if (renderPowerCapped()) return 1000L;
        if (Prefs.MAIN_FPS_POWER.equals(mode)) return 1000L;
        if (Prefs.MAIN_FPS_SMOOTH.equals(mode)) return 250L;
        return 500L;
    }

    private long activeFrameMinGapMs(boolean oracleMotion) {
        String mode = Prefs.mainFpsMode(this);
        if (renderPowerCapped()) return oracleMotion ? 140L : 220L;
        if (Prefs.MAIN_FPS_POWER.equals(mode)) return oracleMotion ? 140L : 180L;
        if (Prefs.MAIN_FPS_SMOOTH.equals(mode)) return oracleMotion ? 55L : 55L;
        return oracleMotion ? 70L : 70L;
    }

    private boolean renderPowerCapped() {
        if (hub != null && hub.battery >= 0 && hub.battery < 15) return true;
        return com.magneo.compass.web.ScreenStreamer.isActive()
                || com.magneo.compass.web.H264Streamer.isActive()
                || com.magneo.compass.web.H264SurfaceStreamer.isActive();
    }

    private void logAuto(String msg) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "auto.log");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            java.io.PrintWriter pw = new java.io.PrintWriter(fo);
            pw.println(new java.util.Date() + " " + msg);
            pw.close();
        } catch (Exception ignored) {}
    }

    private static boolean valueChanged(float now, float old, float threshold) {
        if (Float.isNaN(now)) return false;
        if (Float.isNaN(old)) return true;
        return Math.abs(now - old) >= threshold;
    }

    private static boolean angleChanged(float now, float old, float threshold) {
        if (Float.isNaN(now)) return false;
        if (Float.isNaN(old)) return true;
        float d = Math.abs(now - old) % 360f;
        if (d > 180f) d = 360f - d;
        return d >= threshold;
    }

    private void syncVoiceServiceFromPrefs() {
        Intent i = new Intent(this, VadService.class);
        if (Prefs.vadEnabled(this)) {
            startService(i);
        } else {
            stopService(i);
            if (voice != null) voice.stopContinuousListening();
        }
    }

    private void setMainVoiceStatus(String status) {
        if (view == null) return;
        view.setStatus(filterMainVoiceStatus(status));
    }

    private String filterMainVoiceStatus(String status) {
        if (status == null) return "";
        String s = status.trim();
        if (s.isEmpty()) return "";
        String base = s;
        while (base.endsWith(".") || base.endsWith("…")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        if (base.equals("常驻聆听中")
                || base.equals("聆听中")
                || base.equals("识别中")
                || base.equals("思考中")
                || base.equals("合成中")
                || base.equals("播报中")
                || base.equals("正在重试识别")
                || base.equals("听见声音")
                || base.equals("收到打断")
                || base.equals("回声已过滤")
                || base.equals("语音已暂停")
                || base.equals("切换 TTS 音色")
                || base.equals("查找 TTS 音色")
                || base.startsWith("听见：")) {
            return "";
        }
        return s;
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onSector(int index) {
        switch (index) {
            case 0: startActivity(new Intent(this, AppDrawerActivity.class)); break;
            case 1: startActivity(new Intent(this, FileBrowserActivity.class)); break;
            case 2: startActivity(new Intent(this, SettingsActivity.class)); break;
            case 3: startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); break;
            case 4: startActivity(new Intent(this, MusicPlayerActivity.class)); break;
            case 5: startActivity(new Intent(this, VisionActivity.class)); break;
            case 6: startActivity(new Intent(this, BrowserActivity.class)); break;
            case 7: view.toggleDetail(); break;
        }
    }

    @Override
    public void onCenterTap() {
        try {
            boolean on = FlashlightController.toggle();
            Toast.makeText(this, on ? "闪光灯已开启" : "闪光灯已关闭", Toast.LENGTH_SHORT).show();
            ConversationLog.append(this, "system", on ? "中心太极：闪光灯开启" : "中心太极：闪光灯关闭");
        } catch (Throwable t) {
            FlashlightController.turnOff();
            String msg = t.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = "摄像头可能被占用";
            if (msg.length() > 28) msg = msg.substring(0, 28);
            Toast.makeText(this, "闪光灯不可用：" + msg, Toast.LENGTH_SHORT).show();
            ConversationLog.append(this, "error", "闪光灯不可用: " + t);
        }
    }

    @Override
    public void onOracleReading(OracleReading reading) {
        requestOracleAi(reading);
    }

    @Override
    public void onOraclePageLeft() {
        cancelOracleAi();
    }

    private void cancelOracleAi() {
        if (oracleCall != null) {
            oracleCall.cancel();
            oracleCall = null;
        }
    }

    private void requestOracleAi(final OracleReading reading) {
        if (reading == null || view == null) return;
        cancelOracleAi();
        LlmClient llm = new LlmClient(this);
        if (llm.apiKey == null || llm.apiKey.trim().isEmpty()
                || llm.textBaseUrl == null || llm.textBaseUrl.trim().isEmpty()
                || llm.textModel == null || llm.textModel.trim().isEmpty()) {
            view.setOracleAiResult(reading.id, "", "AI 未配置，本地简解可用");
            return;
        }

        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("system",
                "你是周易占筮解读助手。基于给定卦象，用中文克制解读，分象意、提醒、行动建议三点，总计不超过180字。不要宣称预测必然发生。"));
        msgs.add(new LlmClient.Msg("user", reading.prompt()));
        final StringBuilder full = new StringBuilder();
        oracleUiUpdateMs = 0;
        oracleCall = llm.chat(msgs, false, LlmClient.ChatOptions.oracle(), new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                if (s == null || s.isEmpty()) return;
                full.append(s);
                long now = System.currentTimeMillis();
                if (now - oracleUiUpdateMs < 320) return;
                oracleUiUpdateMs = now;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (view != null && view.isOracleDetailActive()) {
                            view.setOracleAiResult(reading.id, full.toString(), "AI 解读中");
                        }
                    }
                });
            }

            @Override public void onDone(final String done) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (view != null && view.isOracleDetailActive()) {
                            String text = done == null || done.trim().isEmpty() ? full.toString() : done;
                            text = text.trim();
                            view.setOracleAiResult(reading.id, text, text.isEmpty() ? "AI 无返回" : "AI 已解读并播报");
                            if (!text.isEmpty() && voice != null) {
                                ConversationLog.append(MainActivity.this, "assistant",
                                        "占卜：" + compactForLog(text, 220));
                                voice.speakText("占卜解读。" + text);
                            }
                        }
                    }
                });
            }

            @Override public void onError(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (view != null && view.isOracleDetailActive()) {
                            String err = msg == null || msg.trim().isEmpty() ? "未知错误" : msg.trim();
                            view.setOracleAiResult(reading.id, "", "AI 失败：" + err);
                        }
                    }
                });
            }
        });
    }

    private static String compactForLog(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 主屏不响应返回键：吞掉，BaseActivity 默认会 finish()，桌面 launcher 不应被关
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }
}
