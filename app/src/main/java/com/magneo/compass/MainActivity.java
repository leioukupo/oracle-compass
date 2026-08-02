package com.magneo.compass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QuitFix.apply(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        if (Prefs.get(this, Prefs.K_PROVIDER, "").isEmpty()) {
            ProviderConfig.apply(this, ProviderConfig.qwen());
        }
        hub = new SensorHub(this, () -> view.postInvalidate());
        view = new CompassView(this, hub, this);
        setContentView(view);

        voice = VoiceController.get(this, view::setStatus);
        LocalTts.ensureInit(this);
        com.magneo.compass.web.SettingsWebServer.start(this);
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
        if (Prefs.getB(this, Prefs.K_VAD_ENABLED, false)) {
            startService(new Intent(this, VadService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.start();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        hub.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        voice.shutdown();
        super.onDestroy();
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
            case 3: voice.toggle(); break;
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            voice.startListening(false); // 按下开始（按住说话）
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            voice.stopAndSend(); // 松开发送
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
