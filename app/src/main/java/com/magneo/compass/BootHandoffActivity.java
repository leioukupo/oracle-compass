package com.magneo.compass;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Lightweight window prepared behind bootanimation so the vendor keyguard never becomes visible. */
public final class BootHandoffActivity extends Activity {
    private static final String TAG = "BootHandoff";
    private KeyguardManager keyguardManager;
    private KeyguardManager.KeyguardLock keyguardLock;
    private boolean homeStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        applyKeyguardPreference();

        StartupRevealView holdFrame = new StartupRevealView(this);
        holdFrame.hold(null);
        setContentView(holdFrame);

        // Prepare the real HOME while bootanimation still covers the display. Waiting until
        // bootanimation stops leaves a brief vendor-keyguard frame between two activities.
        getWindow().getDecorView().post(this::openHome);
    }

    private void disableNonSecureKeyguard() {
        if (Prefs.systemLockscreenEnabled(this)) return;
        if (keyguardManager == null || keyguardManager.isKeyguardSecure()) return;
        try {
            if (keyguardLock == null) {
                keyguardLock = keyguardManager.newKeyguardLock("OracleCompassBootHandoff");
            }
            keyguardLock.disableKeyguard();
        } catch (Throwable ignored) {
            keyguardLock = null;
        }
    }

    private void applyKeyguardPreference() {
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
        disableNonSecureKeyguard();
    }

    private void openHome() {
        if (homeStarted || isFinishing()) return;
        disableNonSecureKeyguard();
        boolean secure = keyguardManager != null && keyguardManager.isKeyguardSecure();
        boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        Log.i(TAG, "opening HOME secure=" + secure + " locked=" + locked
                + " behind bootanimation");
        homeStarted = true;
        Intent home = new Intent(this, MainActivity.class);
        home.setAction(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }
}
