package com.magneo.compass;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;

/** Lightweight window prepared behind bootanimation so the vendor keyguard never becomes visible. */
public final class BootHandoffActivity extends Activity {
    private static final String QUICK_BOOT = "android.intent.action.QUICKBOOT_POWERON";

    private final Handler handler = new Handler();
    private KeyguardManager.KeyguardLock keyguardLock;
    private BroadcastReceiver bootReceiver;
    private boolean homeStarted;

    private final Runnable bootPoll = new Runnable() {
        @Override public void run() {
            if (systemBootCompleted()) {
                openHome();
            } else if (!isFinishing()) {
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        disableNonSecureKeyguard();

        StartupRevealView holdFrame = new StartupRevealView(this);
        holdFrame.hold(null);
        setContentView(holdFrame);

        if (systemBootCompleted()) {
            openHome();
            return;
        }
        bootReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                openHome();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(QUICK_BOOT);
        registerReceiver(bootReceiver, filter);
        handler.post(bootPoll);
    }

    private void disableNonSecureKeyguard() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || keyguard.isKeyguardSecure()) return;
        try {
            keyguardLock = keyguard.newKeyguardLock("OracleCompassBootHandoff");
            keyguardLock.disableKeyguard();
        } catch (Throwable ignored) {
            keyguardLock = null;
        }
    }

    private void openHome() {
        if (homeStarted || isFinishing()) return;
        homeStarted = true;
        handler.removeCallbacks(bootPoll);
        unregisterBootReceiver();
        Intent home = new Intent(this, MainActivity.class);
        home.setAction(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    private void unregisterBootReceiver() {
        if (bootReceiver == null) return;
        try {
            unregisterReceiver(bootReceiver);
        } catch (Throwable ignored) {
        }
        bootReceiver = null;
    }

    private static boolean systemBootCompleted() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Method get = properties.getMethod("get", String.class, String.class);
            return "1".equals(get.invoke(null, "sys.boot_completed", "0"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(bootPoll);
        unregisterBootReceiver();
        super.onDestroy();
    }
}
