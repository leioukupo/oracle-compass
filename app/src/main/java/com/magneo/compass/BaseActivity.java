package com.magneo.compass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;

import com.magneo.compass.voice.VadService;
import com.magneo.compass.voice.VoiceController;

/**
 * 所有页面的基类：绕过本机 ROM 的“防误退”机制（框架在 dispatchKeyEvent 里拦截返回键）。
 * 我们自己在最外层截获返回键并直接 finish()，不进入框架的检查路径。
 */
public class BaseActivity extends Activity {
    private static final long VOICE_KEY_DEBOUNCE_MS = 350L;
    private static long lastVoiceKeyAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QuitFix.apply(this);
    }

    /** 返回键退出前回调，子类可覆写（如保存当前设置）。 */
    protected void onBackExit() {}

    /** 实体功能键切换常驻监听后回调，子类可覆写刷新当前 UI。 */
    protected void onVoiceToggleChanged(boolean enabled) {}

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onBackExit();
                finish();
            }
            return true; // 吞掉按键，防止框架走 checkAllowQuitState 拦截
        }
        if (isVoiceToggleKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0
                    && !event.isCanceled()) {
                long now = System.currentTimeMillis();
                if (now - lastVoiceKeyAt > VOICE_KEY_DEBOUNCE_MS) {
                    lastVoiceKeyAt = now;
                    togglePersistentVoiceListening();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isVoiceToggleKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_FOCUS;
    }

    private void togglePersistentVoiceListening() {
        boolean enabled = !Prefs.vadEnabled(this);
        Prefs.putB(this, Prefs.K_VAD_ENABLED, enabled);
        Intent intent = new Intent(this, VadService.class);
        VoiceController voice = VoiceController.get(this, null);
        if (enabled) {
            startService(intent);
            voice.ensureContinuousListening();
        } else {
            stopService(intent);
            voice.stopContinuousListening();
        }
        String msg = enabled ? "常驻监听已开启" : "常驻监听已关闭";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        ConversationLog.append(this, "system", "实体功能键：" + msg);
        onVoiceToggleChanged(enabled);
    }
}
