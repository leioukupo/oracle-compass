package com.magneo.compass.voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import com.magneo.compass.ConversationLog;

import java.io.File;
import java.io.FileOutputStream;

/** 云端 TTS 音频播放（临时文件 + MediaPlayer）。 */
public class CloudTts {
    private static final String TAG = "CloudTts";
    private static MediaPlayer mp;
    private static volatile boolean playing;

    public static synchronized void play(Context c, byte[] audio, Runnable done) {
        play(c, audio, "", done);
    }

    public static synchronized void play(Context c, byte[] audio, String contentType, Runnable done) {
        try {
            stop();
            playing = true;
            File f = new File(c.getCacheDir(),
                    "tts_" + System.currentTimeMillis() + extensionFor(audio, contentType));
            FileOutputStream out = new FileOutputStream(f);
            out.write(audio);
            out.close();
            ConversationLog.append(c, "system", "TTS play file=" + f.getName()
                    + " bytes=" + audio.length + " ct=" + contentType);
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                ConversationLog.append(c, "system", "TTS play complete");
                finish(m, f);
                if (done != null) done.run();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                ConversationLog.append(c, "error", "TTS 播放失败 what=" + what + " extra=" + extra);
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                finish(m, f);
                if (done != null) done.run();
                return true;
            });
            mp.prepare();
            mp.start();
            ConversationLog.append(c, "system", "TTS play start");
        } catch (Exception e) {
            playing = false;
            ConversationLog.append(c, "error", "TTS 播放异常：" + e.getMessage());
            Log.w(TAG, "play", e);
            if (done != null) done.run();
        }
    }

    private static synchronized void finish(MediaPlayer m, File f) {
        playing = false;
        if (mp == m) mp = null;
        try { m.release(); } catch (Throwable ignored) {}
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static synchronized void stop() {
        playing = false;
        if (mp != null) {
            try { mp.stop(); mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    public static boolean isPlaying() {
        return playing;
    }

    private static String extensionFor(byte[] audio, String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.US);
        if (ct.contains("wav") || ct.contains("wave")) return ".wav";
        if (ct.contains("ogg")) return ".ogg";
        if (ct.contains("opus")) return ".opus";
        if (ct.contains("mpeg") || ct.contains("mp3")) return ".mp3";
        if (audio != null && audio.length >= 12
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E') {
            return ".wav";
        }
        if (audio != null && audio.length >= 3
                && audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') {
            return ".mp3";
        }
        if (audio != null && audio.length >= 2
                && (audio[0] & 0xFF) == 0xFF && ((audio[1] & 0xE0) == 0xE0)) {
            return ".mp3";
        }
        return ".mp3";
    }
}
