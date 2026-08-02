package com.magneo.compass.voice;

import android.content.Context;
import android.media.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;

/** 云端 TTS 音频播放（临时文件 + MediaPlayer）。 */
public class CloudTts {
    private static MediaPlayer mp;

    public static void play(Context c, byte[] audio, Runnable done) {
        try {
            stop();
            File f = new File(c.getCacheDir(), "tts_" + System.currentTimeMillis() + ".mp3");
            FileOutputStream out = new FileOutputStream(f);
            out.write(audio);
            out.close();
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                m.release();
                f.delete();
                if (done != null) done.run();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                m.release();
                f.delete();
                if (done != null) done.run();
                return true;
            });
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            if (done != null) done.run();
        }
    }

    public static void stop() {
        if (mp != null) {
            try { mp.stop(); mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }
}
