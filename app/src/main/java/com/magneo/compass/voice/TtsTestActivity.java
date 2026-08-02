package com.magneo.compass.voice;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 调试用：验证本地 eSpeak TTS（nativeSpeak 返回采样数并播报）。 */
public class TtsTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        TextView tv = new TextView(this);
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(18);
        tv.setPadding(24, 24, 24, 24);
        root.addView(tv);
        setContentView(root);

        new Thread(() -> {
            boolean ok = LocalTts.ensureInit(this);
            int samples = -1;
            if (NativeTts.available) {
                try {
                    short[] pcm = NativeTts.nativeSpeak("你好，欢迎使用真理罗盘");
                    samples = pcm == null ? -1 : pcm.length;
                } catch (Throwable t) {
                    samples = -2;
                }
            }
            final int s = samples;
            final boolean okInit = ok;
            runOnUiThread(() -> {
                tv.setText("native=" + NativeTts.available + "\ninit=" + okInit + "\n采样数=" + s
                        + "\n开始播报…");
                if (s > 0) LocalTts.speak(this, "你好，欢迎使用真理罗盘");
                Toast.makeText(this, "TTS 自测完成，采样数 " + s, Toast.LENGTH_LONG).show();
            });
        }).start();
    }
}
