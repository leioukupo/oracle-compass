package com.magneo.compass.voice;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** 持续监听服务：检测到说话（静音 1.5s 判定结束）自动发起对话；默认仅亮屏生效。 */
public class VadService extends Service {
    private static final String TAG = "VadService";
    private volatile boolean running = false;
    private Thread thread;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            thread = new Thread(this::loop, "vad");
            thread.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            try {
                VoiceController vc = VoiceController.get(this, s -> {});
                if (!vc.isBusy()) {
                    // 阻塞式 VAD 对话：听到说话→自动结束→处理；处理完继续监听
                    vc.startListening(true);
                    while (vc.isBusy() || vc.isListening()) Thread.sleep(200);
                } else {
                    Thread.sleep(500);
                }
            } catch (Throwable t) {
                Log.w(TAG, "vad loop", t);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }
}
