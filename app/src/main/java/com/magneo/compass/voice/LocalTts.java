package com.magneo.compass.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 本地 TTS：优先 eSpeak NG（普通话/英文离线），不可用时回退系统 Pico（仅英文）。 */
public class LocalTts {
    private static final String TAG = "LocalTts";
    private static volatile boolean inited = false;
    private static volatile long playingUntilMs = 0;
    private static AudioTrack track;
    private static TextToSpeech sysTts;

    public static synchronized boolean ensureInit(Context c) {
        if (inited) return NativeTts.available;
        inited = true;
        if (!NativeTts.available) return false;
        try {
            File dir = new File(c.getFilesDir(), "espeak-ng-data");
            if (!new File(dir, "phontab").exists()) unpackAsset(c, "espeak-ng-data", dir);
            NativeTts.nativeInit(dir.getAbsolutePath(), "cmn");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "espeak init failed", t);
            return false;
        }
    }

    private static void unpackAsset(Context c, String assetDir, File dest) throws IOException {
        String[] list = c.getAssets().list(assetDir);
        if (list == null || list.length == 0) {
            InputStream in = c.getAssets().open(assetDir);
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            return;
        }
        if (!dest.exists()) dest.mkdirs();
        for (String name : list) {
            unpackAsset(c, assetDir + "/" + name, new File(dest, name));
        }
    }

    public static boolean containsCJK(String s) {
        for (char ch : s.toCharArray()) {
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /** 本地合成并播放；中英文均可（英文走 eSpeak 或系统 Pico）。 */
    public static synchronized void speak(Context c, String text) {
        stopPlayback();
        if (ensureInit(c)) {
            short[] pcm = NativeTts.nativeSpeak(text);
            if (pcm != null && pcm.length > 0) {
                playPcm(pcm);
                return;
            }
        }
        if (!containsCJK(text)) playSystem(c, text);
    }

    private static void playPcm(short[] pcm) {
        int rate = 22050;
        int bytes = pcm.length * 2;
        playingUntilMs = System.currentTimeMillis() + Math.max(400, pcm.length * 1000L / rate) + 300;
        if (bytes > 4 * 1024 * 1024) {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192),
                    AudioTrack.MODE_STREAM);
            track.play();
            int step = 8192;
            for (int off = 0; off < pcm.length; off += step) {
                track.write(pcm, off, Math.min(step, pcm.length - off));
            }
        } else {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes,
                    AudioTrack.MODE_STATIC);
            track.write(pcm, 0, pcm.length);
            track.play();
        }
    }

    private static void playSystem(Context c, String text) {
        playingUntilMs = System.currentTimeMillis() + Math.max(1200, text.length() * 90L);
        if (sysTts == null) {
            sysTts = new TextToSpeech(c, status -> {
                sysTts.setLanguage(Locale.ENGLISH);
            });
        }
        sysTts.setLanguage(Locale.ENGLISH);
        sysTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bagua");
    }

    public static synchronized void stopPlayback() {
        playingUntilMs = 0;
        if (track != null) {
            try { track.stop(); track.release(); } catch (Throwable ignored) {}
            track = null;
        }
        if (sysTts != null) {
            try { sysTts.stop(); } catch (Throwable ignored) {}
        }
        if (NativeTts.available) {
            try { NativeTts.nativeStop(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isPlaying() {
        return System.currentTimeMillis() < playingUntilMs;
    }

    public static void waitUntilIdle(long maxMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, maxMs);
        while (isPlaying() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
        }
    }
}
