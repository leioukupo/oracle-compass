package com.magneo.compass.voice;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.util.Log;

import com.magneo.compass.ConversationLog;

import java.io.File;
import java.io.FileOutputStream;

/** 云端 TTS 音频播放（临时文件 + MediaPlayer）。 */
public class CloudTts {
    private static final String TAG = "CloudTts";
    private static MediaPlayer mp;
    private static volatile boolean playing;
    private static volatile boolean fading;
    private static int playSerial;
    private static Visualizer visualizer;

    public static synchronized void play(Context c, byte[] audio, Runnable done) {
        play(c, audio, "", done);
    }

    public static synchronized void play(Context c, byte[] audio, String contentType, Runnable done) {
        try {
            stop();
            int serial = ++playSerial;
            fading = false;
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
            mp.setVolume(1f, 1f);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                ConversationLog.append(c, "system", "TTS play complete");
                finish(m, f, serial);
                if (done != null) done.run();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                ConversationLog.append(c, "error", "TTS 播放失败 what=" + what + " extra=" + extra);
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                VoiceVisualState.showError();
                finish(m, f, serial);
                if (done != null) done.run();
                return true;
            });
            mp.prepare();
            startVisualizer(mp, serial);
            mp.start();
            ConversationLog.append(c, "system", "TTS play start");
        } catch (Exception e) {
            playing = false;
            releaseVisualizer();
            VoiceVisualState.clearOutputLevel();
            VoiceVisualState.showError();
            if (mp != null) {
                try { mp.release(); } catch (Throwable ignored) {}
                mp = null;
            }
            ConversationLog.append(c, "error", "TTS 播放异常：" + e.getMessage());
            Log.w(TAG, "play", e);
            if (done != null) done.run();
        }
    }

    private static synchronized void finish(MediaPlayer m, File f, int serial) {
        playing = false;
        if (playSerial == serial) fading = false;
        releaseVisualizer();
        VoiceVisualState.clearOutputLevel();
        if (mp == m) {
            mp = null;
            playSerial++;
        }
        try { m.release(); } catch (Throwable ignored) {}
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static synchronized void stop() {
        playing = false;
        fading = false;
        playSerial++;
        releaseVisualizer();
        VoiceVisualState.clearOutputLevel();
        if (mp != null) {
            try { mp.stop(); mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
    }

    public static void fadeOutAndStop(long fadeMs) {
        final MediaPlayer target;
        final int serial;
        synchronized (CloudTts.class) {
            if (mp == null || !playing) return;
            target = mp;
            serial = playSerial;
            fading = true;
        }
        long duration = Math.max(120, Math.min(1200, fadeMs));
        Thread t = new Thread(() -> {
            int steps = 8;
            long sleep = Math.max(15, duration / steps);
            for (int i = 1; i <= steps; i++) {
                synchronized (CloudTts.class) {
                    if (mp != target || playSerial != serial || !playing) return;
                    float v = Math.max(0f, 1f - (i / (float) steps));
                    try { target.setVolume(v, v); } catch (Throwable ignored) {}
                }
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) { break; }
            }
            synchronized (CloudTts.class) {
                if (mp == target && playSerial == serial) stop();
            }
        }, "tts-fadeout");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static boolean isFading() {
        return fading;
    }

    private static void startVisualizer(MediaPlayer player, final int serial) {
        releaseVisualizer();
        try {
            final Visualizer capture = new Visualizer(player.getAudioSessionId());
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range != null && range.length > 0 ? range[0] : 128;
            capture.setCaptureSize(captureSize);
            capture.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            int rate = Math.max(1_000, Math.min(Visualizer.getMaxCaptureRate(), 12_000));
            capture.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer visualizer,
                                                            byte[] waveform,
                                                            int samplingRate) {
                    if (playSerial != serial || !playing || waveform == null
                            || waveform.length == 0) return;
                    double sum = 0.0;
                    for (byte sample : waveform) {
                        int centered = (sample & 0xFF) - 128;
                        sum += centered * centered;
                    }
                    float rms = (float) (Math.sqrt(sum / waveform.length) / 64.0);
                    VoiceVisualState.setOutputLevel(Math.min(1f, rms));
                }

                @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft,
                                                       int samplingRate) {}
            }, rate, true, false);
            capture.setEnabled(true);
            visualizer = capture;
        } catch (Throwable t) {
            releaseVisualizer();
            Log.i(TAG, "TTS waveform capture unavailable; using visual fallback");
        }
    }

    private static void releaseVisualizer() {
        Visualizer old = visualizer;
        visualizer = null;
        if (old == null) return;
        try { old.setEnabled(false); } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}
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
