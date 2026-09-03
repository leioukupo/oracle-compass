package com.magneo.compass;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.magneo.compass.browser.BrowserActivity;
import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.netfs.FileBrowserActivity;
import com.magneo.compass.netfs.MusicPlayerActivity;
import com.magneo.compass.vision.VisionActivity;
import com.magneo.compass.voice.LocalTts;
import com.magneo.compass.voice.VadService;
import com.magneo.compass.voice.VoiceController;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;

/** 真理罗盘桌面主屏：HOME 桌面 + 罗盘 + 传感器 + 语音/灵眼入口。 */
public class MainActivity extends BaseActivity implements CompassView.Actions {

    private static volatile MainActivity activeMain;
    private static boolean startupRevealShown;

    private CompassHostView view;
    private SensorHub hub;
    private VoiceController voice;
    private WifiLocator locator;
    private KeyguardManager.KeyguardLock keyguardLock;
    private boolean startupRevealActive;
    private boolean bootHandoffReadyWritten;
    private int bootHandoffReadyAttempts;
    private final Runnable settleKeyguardWindow = this::settleSeamlessKeyguardWindow;
    private final Runnable bootHandoffReadySignal = this::signalBootHandoffReady;
    private final Object oracleLock = new Object();
    private final AtomicInteger oracleGeneration = new AtomicInteger();
    private Call oracleCall;
    private VoiceController.StreamingSpeechSession oracleSpeechSession;
    private long oracleUiUpdateMs = 0;
    private final Handler uiTicker = new Handler();
    private final Object voiceUiLock = new Object();
    private String pendingVoiceStatus;
    private boolean voiceUiDispatchPosted;
    private final Runnable applyPendingVoiceStatus = new Runnable() {
        @Override public void run() {
            String next;
            synchronized (voiceUiLock) {
                next = pendingVoiceStatus;
                pendingVoiceStatus = null;
                voiceUiDispatchPosted = false;
            }
            if (activeMain == MainActivity.this && !isFinishing() && view != null) {
                applyMainVoiceStatus(next);
            }
            synchronized (voiceUiLock) {
                if (pendingVoiceStatus != null && !voiceUiDispatchPosted
                        && activeMain == MainActivity.this && !isFinishing()) {
                    voiceUiDispatchPosted = true;
                    uiTicker.post(this);
                }
            }
        }
    };
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
        Prefs.restoreBackupIfPresent(this);
        configureSeamlessKeyguard();
        hideSystemUi();

        RootGrantNotificationManager.applySaved(this);
        SystemLockscreenManager.applySavedAsync(this);
        SystemLowBatterySoundManager.applySavedAsync(this);
        if (Prefs.get(this, Prefs.K_PROVIDER, "").isEmpty()) {
            ProviderConfig.apply(this, ProviderConfig.openaiCompatible());
        }
        // MT6580 渲染全屏罗盘较贵：静置时交给 1fps 时钟，明显转动时再提升到约 8fps。
        long[] lastDraw = {0};
        float[] lastAzimuth = {Float.NaN};
        float[] lastPitch = {Float.NaN};
        float[] lastRoll = {Float.NaN};
        int[] lastBattery = {-1};
        boolean[] lastBatteryCharging = {false};
        boolean[] lastBatteryFull = {false};
        hub = new SensorHub(this, () -> {
            long now = System.currentTimeMillis();
            float accelMove = (float) Math.abs(Math.sqrt(hub.ax * hub.ax + hub.ay * hub.ay + hub.az * hub.az) - 9.80665f);
            boolean oracleMotion = view != null && view.isOracleDetailActive()
                    && (view.isOracleCollecting() || accelMove > 0.25f);
            boolean batteryStateChanged = hub.battery != lastBattery[0]
                    || hub.batteryCharging != lastBatteryCharging[0]
                    || hub.batteryFull != lastBatteryFull[0];
            if (batteryStateChanged) {
                lastBattery[0] = hub.battery;
                lastBatteryCharging[0] = hub.batteryCharging;
                lastBatteryFull[0] = hub.batteryFull;
                applyScreenPolicy();
                if (view != null) view.onBatteryStateChanged();
            }
            boolean changed = angleChanged(hub.azimuth, lastAzimuth[0], 1.5f)
                    || valueChanged(hub.pitch, lastPitch[0], 1.4f)
                    || valueChanged(hub.roll, lastRoll[0], 1.4f)
                    || batteryStateChanged
                    || oracleMotion;
            long minGap = activeFrameMinGapMs(oracleMotion);
            if (changed && (batteryStateChanged || now - lastDraw[0] >= minGap)) {
                lastDraw[0] = now;
                lastAzimuth[0] = hub.azimuth;
                lastPitch[0] = hub.pitch;
                lastRoll[0] = hub.roll;
                view.postInvalidate();
            }
        });
        view = new CompassHostView(this, hub, this);
        applyScreenPolicy();
        if (!startupRevealShown && savedInstanceState == null) {
            startupRevealShown = true;
            startupRevealActive = true;
            FrameLayout root = new FrameLayout(this);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            StartupRevealView reveal = new StartupRevealView(this);
            reveal.setOnFirstDraw(() -> getWindow().getDecorView()
                    .postDelayed(bootHandoffReadySignal, 80));
            root.addView(reveal, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            reveal.start(() -> {
                root.removeView(reveal);
                startupRevealActive = false;
                settleSeamlessKeyguardWindow();
            });
        } else {
            setContentView(view);
        }
        // This MTK build does not draw app windows while bootanimation is on top, so an
        // onDraw-only readiness callback deadlocks with the module waiting for the marker.
        // Reaching this point means the complete HOME hierarchy is installed on the UI
        // thread; release bootanimation shortly afterwards, then run the reveal on focus.
        getWindow().getDecorView().postDelayed(bootHandoffReadySignal, 180);
        // GPS 硬件不可用时用系统网络/WiFi 定位兜底（每 30s 更新一次，onResume 启动）
        locator = new WifiLocator(this);

        voice = VoiceController.get(this, this::setMainVoiceStatus);
        LocalTts.ensureInit(this);
        com.magneo.compass.web.SettingsWebServer.start(this);
        // 自动启动 ADB TCP / frpc（按网页配置）；摄像头推流不再随主屏冷启，改由 Vision 前后 onResume/onPause 或 web /cam/start|stop 按需启停，避免冷启即占 CPU
        try {
            RemoteAccessService.start(getApplicationContext());
            logAuto("remote access watchdog service started");
        } catch (Throwable t) {
            logAuto("remote access watchdog FAILED: " + t);
        }
        // 兜底：应用每次冷启动清理上次崩溃遗留的推流编码进程，防止 CPU 被占满；
        // 若上次推流是被强杀/崩溃终止的，屏幕常亮设置会残留，一并恢复自动休眠
        new Thread(() -> {
            com.magneo.compass.web.H264SurfaceStreamer.cleanupStale();
            com.magneo.compass.web.ScreenAwake.off();
            StorageMaintenance.cleanup(getApplicationContext());
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

    private void configureSeamlessKeyguard() {
        if (Prefs.systemLockscreenEnabled(this)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            if (keyguardLock != null) {
                try { keyguardLock.reenableKeyguard(); } catch (Throwable ignored) {}
                keyguardLock = null;
            }
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardSecure()) {
            return;
        }
        try {
            keyguardLock = keyguard.newKeyguardLock("OracleCompassHome");
            keyguardLock.disableKeyguard();
        } catch (Throwable ignored) {
            keyguardLock = null;
        }
    }

    private void dismissSeamlessKeyguard() {
        if (Prefs.systemLockscreenEnabled(this)) return;
        if (keyguardLock == null) return;
        try {
            keyguardLock.disableKeyguard();
        } catch (Throwable ignored) {
            // A vendor keyguard may reject the deprecated API after boot.
        }
    }

    private void settleSeamlessKeyguardWindow() {
        if (isFinishing()) return;
        // FLAG_DISMISS_KEYGUARD is only needed for the boot handoff. Keeping it forever makes
        // this MTK SystemUI emit keyguard-done hundreds of times per second.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    private void signalBootHandoffReady() {
        if (bootHandoffReadyWritten || isFinishing()) return;
        View decor = getWindow().getDecorView();
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean secure = keyguard != null && keyguard.isKeyguardSecure();
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        boolean systemLockscreenEnabled = Prefs.systemLockscreenEnabled(this);
        // The bootanimation window owns focus until this marker releases it. The installed
        // HOME hierarchy is the readiness signal; waiting for focus, draw, or boot-complete
        // here would create a circular handoff on this Android 5.1 MTK build.
        if (!systemLockscreenEnabled && secure) {
            dismissSeamlessKeyguard();
            if (++bootHandoffReadyAttempts < 400) {
                decor.postDelayed(bootHandoffReadySignal, 50);
            } else {
                logAuto("boot handoff marker timed out: secure=" + secure
                        + " locked=" + locked);
            }
            return;
        }
        File marker = new File(getFilesDir(), "boot-handoff-ready");
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write('1');
            output.flush();
            bootHandoffReadyWritten = true;
            logAuto("boot handoff main frame ready: locked=" + locked);
        } catch (Exception e) {
            if (++bootHandoffReadyAttempts < 400) {
                decor.postDelayed(bootHandoffReadySignal, 100);
            } else {
                logAuto("boot handoff marker write failed: " + e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeMain = this;
        view.applyRendererPrefs();
        view.onHostResume();
        hub.start();
        applyScreenPolicy();
        FlashlightController.restoreIfRequested();
        applyLocationPrefs();
        uiTicker.removeCallbacks(uiTick);
        uiTicker.post(uiTick);
        if (voice != null) voice = VoiceController.get(this, this::setMainVoiceStatus);
        syncVoiceServiceFromPrefs();
        configureSeamlessKeyguard();
        dismissSeamlessKeyguard();
        hideSystemUi();
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(settleKeyguardWindow);
        if (!startupRevealActive) decor.postDelayed(settleKeyguardWindow, 500);
    }

    @Override
    protected void onPause() {
        if (activeMain == this) activeMain = null;
        uiTicker.removeCallbacks(applyPendingVoiceStatus);
        synchronized (voiceUiLock) {
            pendingVoiceStatus = null;
            voiceUiDispatchPosted = false;
        }
        getWindow().getDecorView().removeCallbacks(clearIdleScreen);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        getWindow().getDecorView().removeCallbacks(bootHandoffReadySignal);
        getWindow().getDecorView().removeCallbacks(clearIdleScreen);
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

    public static void applyScreenPolicyToActive() {
        final MainActivity activity = activeMain;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.applyScreenPolicy(); }
        });
    }

    /** Wake the main screen briefly for a remote web operation. */
    public static void wakeScreenForInteractionActive() {
        final MainActivity activity = activeMain;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.wakeScreenForInteraction(); }
        });
    }

    private void applyScreenPolicy() {
        String policy = Prefs.screenPolicy(this);
        if (Prefs.SCREEN_POLICY_SLEEP.equals(policy)) {
            getWindow().getDecorView().removeCallbacks(clearIdleScreen);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }
        boolean charging = hub != null ? hub.batteryCharging : isChargingNow();
        boolean keep = Prefs.SCREEN_POLICY_ALWAYS.equals(policy) || charging;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().removeCallbacks(clearIdleScreen);
        if (!keep) getWindow().getDecorView().postDelayed(clearIdleScreen, 60_000L);
    }

    private final Runnable clearIdleScreen = () -> {
        if (Prefs.SCREEN_POLICY_PLUGGED.equals(Prefs.screenPolicy(this)) && !isChargingNow()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    private void wakeScreenForInteraction() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (activeMain != this || isFinishing()) return;
            uiTicker.post(() -> {
                if (activeMain == MainActivity.this && !isFinishing()) {
                    wakeScreenForInteraction();
                }
            });
            return;
        }
        if (activeMain != this || isFinishing()) return;
        if (Prefs.SCREEN_POLICY_SLEEP.equals(Prefs.screenPolicy(this))) return;
        applyScreenPolicy();
    }

    @Override
    public void onUserInteraction() {
        wakeScreenForInteraction();
        super.onUserInteraction();
    }

    private boolean isChargingNow() {
        try {
            Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,
                    android.os.BatteryManager.BATTERY_STATUS_UNKNOWN);
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void applySystemLockscreenPrefToActive() {
        final MainActivity activity = activeMain;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                activity.configureSeamlessKeyguard();
                activity.hideSystemUi();
            }
        });
    }

    public static void applyVoiceDiagnosticPrefToActive() {
        final MainActivity activity = activeMain;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!Prefs.voiceDiagnosticOverlays(activity) && activity.view != null) {
                    activity.view.setStatus("");
                }
            }
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
        if (activeMain != this || isFinishing() || view == null) return;
        synchronized (voiceUiLock) {
            pendingVoiceStatus = status;
            if (voiceUiDispatchPosted) return;
            voiceUiDispatchPosted = true;
            uiTicker.post(applyPendingVoiceStatus);
        }
    }

    /** Applies voice status only on the Activity's UI thread. */
    private void applyMainVoiceStatus(String status) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || activeMain != this || isFinishing() || view == null) return;
        if (status != null && !status.trim().isEmpty()) wakeScreenForInteraction();
        view.setStatus(filterMainVoiceStatus(status));
    }

    private String filterMainVoiceStatus(String status) {
        if (status == null) return "";
        String s = status.trim();
        if (s.isEmpty()) return "";
        return Prefs.voiceDiagnosticOverlays(this) ? s : "";
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
        oracleGeneration.incrementAndGet();
        Call call;
        VoiceController.StreamingSpeechSession speech;
        synchronized (oracleLock) {
            call = oracleCall;
            oracleCall = null;
            speech = oracleSpeechSession;
            oracleSpeechSession = null;
        }
        if (call != null) call.cancel();
        if (speech != null) speech.cancel();
    }

    private void requestOracleAi(final OracleReading reading) {
        if (reading == null || view == null) return;
        cancelOracleAi();
        final int generation = oracleGeneration.incrementAndGet();
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
        final Object resultLock = new Object();
        final boolean[] hadDelta = {false};
        oracleUiUpdateMs = 0;
        oracleCall = llm.chat(msgs, false, LlmClient.ChatOptions.oracle(), new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                if (!isCurrentOracle(generation) || s == null || s.isEmpty()) return;
                String visibleText;
                synchronized (resultLock) {
                    hadDelta[0] = true;
                    full.append(s);
                    visibleText = full.toString();
                }
                appendOracleSpeech(generation, s);
                long now = System.currentTimeMillis();
                if (now - oracleUiUpdateMs < 320) return;
                oracleUiUpdateMs = now;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isCurrentOracle(generation) && view != null && view.isOracleDetailActive()) {
                            view.setOracleAiResult(reading.id, visibleText, "AI 解读并播报中");
                        }
                    }
                });
            }

            @Override public void onDone(final String done) {
                if (!isCurrentOracle(generation)) return;
                final String text;
                final boolean receivedDelta;
                synchronized (resultLock) {
                    receivedDelta = hadDelta[0];
                    text = (done == null || done.trim().isEmpty() ? full.toString() : done).trim();
                }
                if (!text.isEmpty()) {
                    if (!receivedDelta) appendOracleSpeech(generation, text);
                    finishOracleSpeech(generation);
                    ConversationLog.append(MainActivity.this, "assistant",
                            "占卜：" + compactForLog(text, 220));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isCurrentOracle(generation) && view != null && view.isOracleDetailActive()) {
                            view.setOracleAiResult(reading.id, text, text.isEmpty() ? "AI 无返回" : "AI 已解读并播报");
                        }
                    }
                });
            }

            @Override public void onError(final String msg) {
                if (!isCurrentOracle(generation)) return;
                final String partial;
                final boolean receivedDelta;
                synchronized (resultLock) {
                    partial = full.toString().trim();
                    receivedDelta = hadDelta[0] && !partial.isEmpty();
                }
                if (receivedDelta) finishOracleSpeech(generation);
                else cancelOracleSpeech(generation);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isCurrentOracle(generation) && view != null && view.isOracleDetailActive()) {
                            String err = msg == null || msg.trim().isEmpty() ? "未知错误" : msg.trim();
                            view.setOracleAiResult(reading.id, receivedDelta ? partial : "",
                                    receivedDelta ? "AI 中断，已播报已生成部分" : "AI 失败：" + err);
                        }
                    }
                });
            }
        });
    }

    private boolean isCurrentOracle(int generation) {
        return oracleGeneration.get() == generation;
    }

    private void appendOracleSpeech(int generation, String delta) {
        if (delta == null || delta.isEmpty() || !isCurrentOracle(generation) || voice == null) return;
        VoiceController.StreamingSpeechSession session;
        boolean addPrefix = false;
        synchronized (oracleLock) {
            if (!isCurrentOracle(generation)) return;
            session = oracleSpeechSession;
            if (session == null) {
                session = voice.beginStreamingSpeech("oracle");
                oracleSpeechSession = session;
                addPrefix = true;
            }
        }
        session.append(addPrefix ? "占卜解读。" + delta : delta);
    }

    private void finishOracleSpeech(int generation) {
        VoiceController.StreamingSpeechSession session;
        synchronized (oracleLock) {
            if (!isCurrentOracle(generation)) return;
            session = oracleSpeechSession;
        }
        if (session != null) session.finish();
    }

    private void cancelOracleSpeech(int generation) {
        VoiceController.StreamingSpeechSession session;
        synchronized (oracleLock) {
            if (!isCurrentOracle(generation)) return;
            session = oracleSpeechSession;
            oracleSpeechSession = null;
        }
        if (session != null) session.cancel();
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
