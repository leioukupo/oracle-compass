package com.magneo.compass.voice;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.llm.LlmClient;

/** 调试用：验证云端 CosyVoice TTS 合成和设备 MediaPlayer 播放。 */
public class TtsTestActivity extends com.magneo.compass.BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        TextView tv = new TextView(this);
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(18);
        tv.setPadding(com.magneo.compass.ui.Ui.dp(this, 12), com.magneo.compass.ui.Ui.dp(this, 12),
                com.magneo.compass.ui.Ui.dp(this, 12), com.magneo.compass.ui.Ui.dp(this, 12));
        root.addView(tv);
        setContentView(root);

        tv.setText("云端 TTS 自测\n准备合成...");
        LlmClient llm = new LlmClient(this);
        String text = "真理罗盘云端语音测试。";
        ConversationLog.append(this, "system", "云端 TTS 自测开始");
        llm.synthesize(text, new LlmClient.BytesCallback() {
            @Override public void onResult(byte[] audio) {
                onResult(audio, "");
            }

            @Override public void onResult(byte[] audio, String contentType) {
                runOnUiThread(() -> tv.setText("云端 TTS 自测\n音频字节=" + audio.length
                        + "\n类型=" + contentType + "\n开始播放..."));
                CloudTts.play(TtsTestActivity.this, audio, contentType, () -> runOnUiThread(() -> {
                    tv.setText("云端 TTS 自测\n播放完成");
                    Toast.makeText(TtsTestActivity.this, "云端 TTS 自测完成", Toast.LENGTH_LONG).show();
                }));
            }

            @Override public void onError(String msg) {
                ConversationLog.append(TtsTestActivity.this, "error", "云端 TTS 自测失败：" + msg);
                runOnUiThread(() -> {
                    tv.setText("云端 TTS 自测失败\n" + msg);
                    Toast.makeText(TtsTestActivity.this, "云端 TTS 自测失败", Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
