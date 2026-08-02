package com.magneo.compass.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 语音闭环控制器：录音+VAD -> ASR -> LLM -> TTS（本地优先）。 */
public class VoiceController {
    private static final String TAG = "VoiceController";
    private static final int SAMPLE_RATE = 16000;

    public interface StatusListener { void onStatus(String s); }

    private static VoiceController instance;
    private final Context ctx;
    private final StatusListener status;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private AudioRecord recorder;
    private volatile boolean keepRecording;
    private ByteArrayOutputStream bos;
    private long lastSpeechMs;
    private boolean heard;

    public static synchronized VoiceController get(Context c, StatusListener l) {
        if (instance == null) instance = new VoiceController(c.getApplicationContext(), l);
        return instance;
    }

    private VoiceController(Context c, StatusListener l) { ctx = c; status = l; }

    public boolean isBusy() { return busy.get(); }

    public boolean isListening() { return listening.get(); }

    /** 开始录音；autoStop=true 时说话结束后静音 1.5s 自动停止（VAD 模式）。 */
    public void startListening(boolean autoStop) {
        if (listening.getAndSet(true)) return;
        keepRecording = true;
        heard = false;
        lastSpeechMs = 0;
        setStatus("聆听中…");
        exec.execute(() -> recordLoop(autoStop));
    }

    /** 停止录音并进入 ASR->LLM->TTS 流程。 */
    public void stopAndSend() {
        if (!listening.getAndSet(false)) return;
        setStatus("识别中…");
        keepRecording = false;
    }

    /** 中央太极点击：切换 开始/结束。 */
    public void toggle() {
        if (listening.get()) stopAndSend();
        else startListening(false);
    }

    public void shutdown() {
        keepRecording = false;
        if (recorder != null) { try { recorder.stop(); } catch (Throwable ignored) {} recorder.release(); recorder = null; }
        exec.shutdownNow();
        LocalTts.stopPlayback();
        CloudTts.stop();
    }

    private void setStatus(String s) { if (status != null) status.onStatus(s); }

    private void recordLoop(boolean autoStop) {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 8192));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            listening.set(false);
            setStatus("录音失败（麦克风被占用）");
            return;
        }
        bos = new ByteArrayOutputStream();
        recorder.startRecording();
        short[] buf = new short[640]; // 20ms per frame @16k
        long frameMs = 20;
        long silenceMs = 0;
        long maxMs = 30_000;
        long started = System.currentTimeMillis();
        int threshold = Prefs.getI(ctx, Prefs.K_VAD_SENSITIVITY, 600);

        try {
            while (keepRecording && System.currentTimeMillis() - started < maxMs) {
                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;
                long rms = rms(buf, n);
                if (rms > threshold) { heard = true; silenceMs = 0; lastSpeechMs = System.currentTimeMillis(); }
                else silenceMs += frameMs;
                byte[] bytes = shortsToBytes(buf, n);
                bos.write(bytes, 0, bytes.length);
                if (autoStop && heard && silenceMs > 1500) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "record", e);
        } finally {
            try { recorder.stop(); } catch (Throwable ignored) {}
            recorder.release();
            recorder = null;
        }
        listening.set(false);
        if (heard) {
            byte[] wav = toWav(bos.toByteArray());
            process(wav);
        } else {
            setStatus("");
        }
    }

    private void process(byte[] wav) {
        if (busy.getAndSet(true)) { setStatus(""); return; }
        exec.execute(() -> {
            try {
                LlmClient llm = new LlmClient(ctx);
                String asrText = "";
                if (!llm.asrUrl.isEmpty()) {
                    setStatus("识别中…");
                    final String[] t = {""};
                    final Object lock = new Object();
                    llm.transcribe(wav, new LlmClient.TextCallback() {
                        @Override public void onResult(String text) { synchronized (lock) { t[0] = text; lock.notifyAll(); } }
                        @Override public void onError(String msg) { synchronized (lock) { t[0] = "!" + msg; lock.notifyAll(); } }
                    });
                    synchronized (lock) { try { lock.wait(60000); } catch (InterruptedException ignored) {} }
                    asrText = t[0];
                    if (asrText.startsWith("!")) { ConversationLog.append(ctx, "error", "ASR 失败：" + asrText.substring(1)); setStatus("ASR 失败：" + asrText.substring(1)); return; }
                } else {
                    setStatus("未配置 ASR，请在设置填写语音 API");
                    return;
                }
                if (asrText.trim().isEmpty()) { setStatus("没有听到内容"); return; }
                ConversationLog.append(ctx, "user", asrText);

                setStatus("思考中…");
                List<LlmClient.Msg> msgs = new ArrayList<>();
                msgs.add(new LlmClient.Msg("system",
                        Prefs.get(ctx, Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE)));
                msgs.add(new LlmClient.Msg("user", asrText));
                final StringBuilder reply = new StringBuilder();
                final Object doneLock = new Object();
                llm.chat(msgs, false, new LlmClient.StreamCallback() {
                    @Override public void onDelta(String s) { synchronized (doneLock) { reply.append(s); } }
                    @Override public void onDone(String full) { synchronized (doneLock) { doneLock.notifyAll(); } }
                    @Override public void onError(String msg) { synchronized (doneLock) { reply.setLength(0); reply.append("!").append(msg); doneLock.notifyAll(); } }
                });
                synchronized (doneLock) { try { doneLock.wait(120000); } catch (InterruptedException ignored) {} }
                String text = reply.toString();
                if (text.startsWith("!")) { ConversationLog.append(ctx, "error", "LLM 失败：" + text.substring(1)); setStatus("LLM 失败：" + text.substring(1)); return; }

                ConversationLog.append(ctx, "assistant", text);
                setStatus("播报中…");
                speakReply(text);
                setStatus("");
            } catch (Exception e) {
                setStatus("错误：" + e.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }

    private void speakReply(String text) {
        boolean localFirst = Prefs.getB(ctx, Prefs.K_LOCAL_TTS_FIRST, true);
        boolean hasCloudTts = !Prefs.get(ctx, Prefs.K_TTS_URL, "").isEmpty();
        boolean useCloud = !localFirst && hasCloudTts;
        if (!useCloud) {
            LocalTts.speak(ctx, text);
            return;
        }
        LlmClient llm = new LlmClient(ctx);
        final byte[][] result = {null};
        final Object lock = new Object();
        llm.synthesize(text, new LlmClient.BytesCallback() {
            @Override public void onResult(byte[] audio) { synchronized (lock) { result[0] = audio; lock.notifyAll(); } }
            @Override public void onError(String msg) { synchronized (lock) { lock.notifyAll(); } }
        });
        synchronized (lock) { try { lock.wait(60000); } catch (InterruptedException ignored) {} }
        if (result[0] != null) CloudTts.play(ctx, result[0], null);
        else LocalTts.speak(ctx, text);
    }

    private static long rms(short[] buf, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
        return (long) Math.sqrt(sum / Math.max(1, n));
    }

    private static byte[] shortsToBytes(short[] s, int n) {
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            b[i * 2] = (byte) (s[i] & 0xff);
            b[i * 2 + 1] = (byte) ((s[i] >> 8) & 0xff);
        }
        return b;
    }

    private static byte[] toWav(byte[] pcm) {
        ByteArrayOutputStream w = new ByteArrayOutputStream();
        // WAV 头要求小端序（DataOutputStream 是大端，会写反）
        writeLEStr(w, "RIFF");
        writeLEInt(w, 36 + pcm.length);
        writeLEStr(w, "WAVE");
        writeLEStr(w, "fmt ");
        writeLEInt(w, 16);
        writeLEShort(w, 1);      // PCM
        writeLEShort(w, 1);      // 单声道
        writeLEInt(w, SAMPLE_RATE);
        writeLEInt(w, SAMPLE_RATE * 2);
        writeLEShort(w, 2);
        writeLEShort(w, 16);
        writeLEStr(w, "data");
        writeLEInt(w, pcm.length);
        w.write(pcm, 0, pcm.length);
        return w.toByteArray();
    }

    private static void writeLEInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static void writeLEShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void writeLEStr(ByteArrayOutputStream o, String s) {
        for (byte b : s.getBytes()) o.write(b);
    }
}
