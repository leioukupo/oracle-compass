package com.magneo.compass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.magneo.compass.browser.BrowserActivity;
import com.magneo.compass.netfs.FileBrowserActivity;
import com.magneo.compass.netfs.MusicPlayerActivity;
import com.magneo.compass.vision.VisionActivity;
import com.magneo.compass.voice.LocalTts;
import com.magneo.compass.voice.VadService;
import com.magneo.compass.voice.VoiceController;

/** 真理罗盘桌面主屏：HOME 桌面 + 罗盘 + 传感器 + 语音/灵眼入口。 */
public class MainActivity extends BaseActivity implements CompassView.Actions {

    private CompassView view;
    private SensorHub hub;
    private VoiceController voice;
    private WifiLocator locator;
    private final Handler uiTicker = new Handler();
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (view != null) view.postInvalidate();   // 每秒心跳：时钟/读数保证刷新，不依赖传感器事件
            uiTicker.postDelayed(this, 1000);
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
        // MT6580 软件渲染全屏罗盘较贵，传感器重绘限到约 8fps。
        long[] lastDraw = {0};
        hub = new SensorHub(this, () -> {
            long now = System.currentTimeMillis();
            if (now - lastDraw[0] >= 120) {
                lastDraw[0] = now;
                view.postInvalidate();
            }
        });
        view = new CompassView(this, hub, this);
        setContentView(view);
        // GPS 硬件不可用时用系统网络/WiFi 定位兜底（每 30s 更新一次，onResume 启动）
        locator = new WifiLocator(this);

        voice = VoiceController.get(this, this::setMainVoiceStatus);
        LocalTts.ensureInit(this);
        com.magneo.compass.web.SettingsWebServer.start(this);
        // 自动启动 ADB TCP / frpc（按网页配置）；摄像头推流不再随主屏冷启，改由 Vision 前后 onResume/onPause 或 web /cam/start|stop 按需启停，避免冷启即占 CPU
        new Thread(() -> {
            try {
                String adb = com.magneo.compass.web.AdbManager.ensureAutoStart(getApplicationContext());
                boolean hasCfg = !Prefs.get(MainActivity.this, Prefs.K_FRPC_CONFIG, "").trim().isEmpty();
                String r1 = "adb auto: " + adb + "\nfrpc auto: cfg=" + hasCfg + " run=" + com.magneo.compass.frp.FrpcManager.isRunning();
                if (hasCfg && !com.magneo.compass.frp.FrpcManager.isRunning()) {
                    r1 += " -> " + com.magneo.compass.frp.FrpcManager.start(getApplicationContext());
                }
                logAuto(r1);
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
        boolean showLoc = Prefs.getB(this, Prefs.K_SHOW_LOC, false);
        hub.gpsEnabled = showLoc;
        hub.start();
        if (locator != null) {
            if (showLoc) {
                locator.start((lat, lon, acc, src) -> {
                    hub.netLat = lat; hub.netLon = lon; hub.netAcc = acc; hub.netSrc = src;
                    view.postInvalidate();
                });
            } else {
                locator.stop();
            }
        }
        uiTicker.removeCallbacks(uiTick);
        uiTicker.post(uiTick);
        if (voice != null) voice = VoiceController.get(this, this::setMainVoiceStatus);
        syncVoiceServiceFromPrefs();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        hub.stop();
        if (locator != null) locator.stop();
        uiTicker.removeCallbacks(uiTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiTicker.removeCallbacks(uiTick);
        if (locator != null) locator.stop();
        voice.shutdown();
        super.onDestroy();
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
        voice.toggle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 主屏不响应返回键：吞掉，BaseActivity 默认会 finish()，桌面 launcher 不应被关
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }
}
