package com.magneo.compass;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * 所有页面的基类：绕过本机 ROM 的“防误退”机制（框架在 dispatchKeyEvent 里拦截返回键）。
 * 我们自己在最外层截获返回键并直接 finish()，不进入框架的检查路径。
 */
public class BaseActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QuitFix.apply(this);
    }

    /** 返回键退出前回调，子类可覆写（如保存当前设置）。 */
    protected void onBackExit() {}

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
        return super.dispatchKeyEvent(event);
    }
}
