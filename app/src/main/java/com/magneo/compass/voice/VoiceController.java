package com.magneo.compass.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;

/** Voice loop: continuous microphone + streaming ASR + LLM + queued cloud TTS. */
public class VoiceController {
    private static final String TAG = "VoiceController";
    private static final int SAMPLE_RATE = 16000;
    private static final int STREAM_FRAME_SAMPLES = 960; // 60ms @ 16kHz, matches FunASR sample clients.
    private static final long AUTO_LISTEN_WINDOW_MS = 120_000;
    private static final long MANUAL_MAX_MS = 30_000;
    private static final long STREAM_END_SILENCE_MS = 700;
    private static final long STREAM_MIN_SPEECH_MS = 140;
    private static final long STREAM_MIN_BARGE_MS = 120;
    private static final long STREAM_MAX_UTTERANCE_MS = 15_000;
    private static final int BARGE_PREROLL_FRAMES = 6; // 360ms, avoid feeding a long TTS tail.
    private static final int BARGE_THRESHOLD_MARGIN = 140;
    private static final long ASR_RETRY_DELAY_MS = 6_500;
    private static final long ASR_RETRY_TIMEOUT_MS = 7_000;
    private static final long TTS_START_HOLDOFF_MS = 350;
    private static final long TTS_END_HOLDOFF_MS = 900;
    private static final long FOLLOWUP_WINDOW_MS = 22_000;

    public interface StatusListener { void onStatus(String s); }
    public interface SpeechConsumer { boolean onFinalSpeech(String text); }

    private static VoiceController instance;

    private final Context ctx;
    private volatile StatusListener status;
    private final ExecutorService fallbackExec = Executors.newSingleThreadExecutor();
    private final ExecutorService turnExec = Executors.newCachedThreadPool();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean continuousRunning = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean ttsWorkerStarted = new AtomicBoolean(false);
    private final AtomicBoolean ttsSpeaking = new AtomicBoolean(false);
    private final AtomicBoolean bufferedAsrInFlight = new AtomicBoolean(false);
    private final AtomicInteger turnSerial = new AtomicInteger();
    private final AtomicInteger asrSessionSerial = new AtomicInteger();
    private final AtomicInteger asrFinalSerial = new AtomicInteger();
    private final AtomicInteger latestUtteranceAsrSession = new AtomicInteger();
    private final AtomicBoolean manualBoundaryRequested = new AtomicBoolean(false);
    private final Object standbyAsrLock = new Object();
    private final Set<Call> activeCalls = Collections.synchronizedSet(new HashSet<Call>());
    private final Set<FunAsrStreamingClient> asrSessions =
            Collections.synchronizedSet(new HashSet<FunAsrStreamingClient>());
    private final LinkedBlockingQueue<TtsJob> ttsQueue = new LinkedBlockingQueue<>(8);
    private final LinkedBlockingQueue<PreparedTts> readyTtsQueue =
            new LinkedBlockingQueue<>(3);
    private final List<LlmClient.Msg> dialogHistory = new ArrayList<>();
    private final StringBuilder streamPartial = new StringBuilder();

    private volatile AudioRecord recorder;
    private volatile boolean keepRecording;
    private volatile boolean cancelRequested;
    private volatile FunAsrStreamingClient streamingAsr;
    private volatile AsrSessionRef standbyAsr;
    private volatile Thread continuousThread;
    private volatile SpeechConsumer speechConsumer;
    private volatile long lastAssistantAt;
    private volatile long lastTtsStartAt;
    private volatile long lastTtsEndAt;
    private volatile long ttsHoldoffUntil;
    private volatile String recentSpokenText = "";
    private volatile String recentSpokenNorm = "";
    private volatile String lastFinalNorm = "";
    private volatile long lastFinalAt;
    private volatile byte[] lastBoundaryPcm;
    private AudioEffect aec;
    private AudioEffect ns;
    private AudioEffect agc;
    private ByteArrayOutputStream bos;
    private boolean heard;

    private static final class AsrSessionRef {
        final int id;
        final FunAsrStreamingClient client;

        AsrSessionRef(int id, FunAsrStreamingClient client) {
            this.id = id;
            this.client = client;
        }
    }

    public static synchronized VoiceController get(Context c, StatusListener l) {
        if (instance == null) instance = new VoiceController(c.getApplicationContext(), l);
        else if (l != null) instance.status = l;
        return instance;
    }

    private VoiceController(Context c, StatusListener l) {
        ctx = c;
        status = l;
        startTtsWorker();
    }

    public boolean isBusy() {
        return busy.get() || ttsSpeaking.get()
                || !ttsQueue.isEmpty() || !readyTtsQueue.isEmpty();
    }

    public boolean isListening() {
        return listening.get() || continuousRunning.get();
    }

    public boolean usesStreamingAsr() {
        return FunAsrStreamingClient.isStreamingUrl(new LlmClient(ctx).asrUrl);
    }

    public void setSpeechConsumer(SpeechConsumer consumer) {
        speechConsumer = consumer;
    }

    public void clearSpeechConsumer(SpeechConsumer consumer) {
        if (speechConsumer == consumer) speechConsumer = null;
    }

    /** Start continuous FunASR listening, or fall back to the legacy one-shot VAD. */
    public void ensureContinuousListening() {
        LlmClient llm = new LlmClient(ctx);
        if (!FunAsrStreamingClient.isStreamingUrl(llm.asrUrl)) {
            if (!listening.get() && !busy.get()) startFallbackListening(true);
            return;
        }
        if (!continuousRunning.compareAndSet(false, true)) return;
        shuttingDown.set(false);
        listening.set(true);
        ConversationLog.append(ctx, "system", "ASR mode=streaming endpoint="
                + FunAsrStreamingClient.normalizeWsUrl(llm.asrUrl));
        setStatus("常驻聆听中...");
        continuousThread = new Thread(this::continuousLoop, "voice-continuous");
        continuousThread.setDaemon(true);
        continuousThread.start();
    }

    public void stopContinuousListening() {
        continuousRunning.set(false);
        listening.set(false);
        manualBoundaryRequested.set(false);
        cancelActiveCalls();
        synchronized (standbyAsrLock) {
            standbyAsr = null;
        }
        synchronized (asrSessions) {
            for (FunAsrStreamingClient session : asrSessions) {
                try { session.close(); } catch (Throwable ignored) {}
            }
            asrSessions.clear();
        }
        FunAsrStreamingClient c = streamingAsr;
        streamingAsr = null;
        if (c != null) c.close();
        AudioRecord r = recorder;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
        }
        setStatus("");
    }

    /** Start listening. With FunASR configured this keeps the continuous stream alive. */
    public void startListening(boolean autoStop) {
        if (FunAsrStreamingClient.isStreamingUrl(new LlmClient(ctx).asrUrl)) {
            ensureContinuousListening();
            return;
        }
        startFallbackListening(autoStop);
    }

    /** Stop the current legacy recording, or mark an utterance boundary in streaming mode. */
    public void stopAndSend() {
        if (continuousRunning.get()) {
            manualBoundaryRequested.set(true);
            setStatus("识别中...");
            return;
        }
        if (!listening.getAndSet(false)) return;
        setStatus("识别中...");
        keepRecording = false;
    }

    /** Cancel recording. Used when another owner must take the microphone. */
    public void cancelListening() {
        cancelRequested = true;
        keepRecording = false;
        listening.set(false);
        stopContinuousListening();
        AudioRecord r = recorder;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
        }
        setStatus("");
    }

    /** Center tap: toggle continuous listening when FunASR is configured. */
    public void toggle() {
        if (FunAsrStreamingClient.isStreamingUrl(new LlmClient(ctx).asrUrl)) {
            if (continuousRunning.get()) {
                stopContinuousListening();
                stopTtsPlayback();
                setStatus("语音已暂停");
            } else {
                ensureContinuousListening();
            }
            return;
        }
        if (listening.get()) stopAndSend();
        else startFallbackListening(false);
    }

    /** Speak an answer generated outside the normal text-only voice loop. */
    public void speakText(String text) {
        String t = compact(text, 500).trim();
        if (t.isEmpty()) return;
        int turnId = turnSerial.incrementAndGet();
        cancelActiveCalls();
        stopTtsPlayback();
        enqueueTts(turnId, t);
    }

    public void shutdown() {
        shuttingDown.set(true);
        keepRecording = false;
        stopContinuousListening();
        stopTtsPlayback();
        fallbackExec.shutdownNow();
        turnExec.shutdownNow();
        cancelActiveCalls();
        synchronized (VoiceController.class) {
            if (instance == this) instance = null;
        }
    }

    private void setStatus(String s) {
        StatusListener l = status;
        if (l != null) l.onStatus(s);
    }

    private void trackCall(Call call) {
        if (call != null) activeCalls.add(call);
    }

    private void untrackCall(Call call) {
        if (call != null) activeCalls.remove(call);
    }

    private void cancelActiveCalls() {
        synchronized (activeCalls) {
            for (Call call : activeCalls) {
                try { call.cancel(); } catch (Throwable ignored) {}
            }
            activeCalls.clear();
        }
    }

    private void continuousLoop() {
        LlmClient llm = new LlmClient(ctx);
        recorder = createRecorder();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            continuousRunning.set(false);
            listening.set(false);
            setStatus("录音失败（麦克风被占用）");
            return;
        }

        short[] buf = new short[STREAM_FRAME_SAMPLES];
        byte[][] preRoll = new byte[18][];
        int preIdx = 0;
        int preCount = 0;
        ByteArrayOutputStream utterancePcm = new ByteArrayOutputStream();
        boolean speechActive = false;
        boolean bargeUtterance = false;
        long voiceMs = 0;
        long silenceMs = 0;
        long speechMs = 0;
        int baseThreshold = Math.max(250, Prefs.getI(ctx, Prefs.K_VAD_SENSITIVITY, 600));
        long noiseFloor = 0;
        int sessionId = 0;
        FunAsrStreamingClient currentSession = null;

        try {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            } catch (Throwable t) {
                Log.w(TAG, "audio thread priority unavailable", t);
            }
            recorder.startRecording();
            prewarmAsrSession(llm.asrUrl);
            setStatus("常驻聆听中...");
            while (continuousRunning.get() && !shuttingDown.get()) {
                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;
                long frameMs = Math.max(10, n * 1000L / SAMPLE_RATE);
                long level = rms(buf, n);
                boolean ttsNow = ttsSpeaking.get();
                byte[] pcm = shortsToBytes(buf, n);

                if (!speechActive) {
                    if (!ttsNow) {
                        if (noiseFloor == 0) noiseFloor = level;
                        else noiseFloor = (noiseFloor * 31 + level) / 32;
                    }
                    int adaptiveThreshold = Math.max(baseThreshold, (int) Math.min(4000, noiseFloor * 2 + 100));
                    boolean voiced = isVoiceFrame(level, adaptiveThreshold, ttsNow);
                    preRoll[preIdx] = pcm;
                    preIdx = (preIdx + 1) % preRoll.length;
                    if (preCount < preRoll.length) preCount++;
                    voiceMs = voiced ? voiceMs + frameMs : 0;
                    long needMs = ttsNow ? STREAM_MIN_BARGE_MS : STREAM_MIN_SPEECH_MS;
                    if (voiceMs >= needMs) {
                        boolean bargeAtStart = ttsNow;
                        bargeUtterance = bargeAtStart;
                        speechActive = true;
                        speechMs = voiceMs;
                        silenceMs = 0;
                        utterancePcm.reset();
                        int speechPreCount = bargeAtStart
                                ? Math.min(preCount, BARGE_PREROLL_FRAMES) : preCount;
                        int start = (preIdx - speechPreCount + preRoll.length) % preRoll.length;
                        if (bargeAtStart) {
                            stopTtsPlayback();
                            cancelActiveCalls();
                            ConversationLog.append(ctx, "system",
                                    "Barge-in start level=" + level + " threshold=" + adaptiveThreshold);
                        }
                        for (int i = 0; i < speechPreCount; i++) {
                            byte[] b = preRoll[(start + i) % preRoll.length];
                            if (b != null) utterancePcm.write(b, 0, b.length);
                        }
                        synchronized (streamPartial) {
                            streamPartial.setLength(0);
                        }
                        AsrSessionRef prepared = takeStandbyAsrSession();
                        if (prepared != null) {
                            sessionId = prepared.id;
                            currentSession = prepared.client;
                        } else {
                            sessionId = asrSessionSerial.incrementAndGet();
                            currentSession = beginAsrSession(sessionId, llm.asrUrl);
                        }
                        latestUtteranceAsrSession.set(sessionId);
                        streamingAsr = currentSession;
                        for (int i = 0; i < speechPreCount; i++) {
                            byte[] b = preRoll[(start + i) % preRoll.length];
                            if (b != null) currentSession.sendPcm(b);
                        }
                        if (bargeAtStart) {
                            setStatus("收到打断...");
                        } else {
                            setStatus("听见声音...");
                        }
                    }
                    continue;
                }

                if (currentSession != null) currentSession.sendPcm(pcm);
                utterancePcm.write(pcm, 0, pcm.length);
                speechMs += frameMs;
                int adaptiveThreshold = Math.max(baseThreshold, (int) Math.min(4000, noiseFloor * 2 + 100));
                boolean voiced = isVoiceFrame(level, adaptiveThreshold, ttsNow);
                if (voiced) silenceMs = 0;
                else silenceMs += frameMs;

                boolean forceBoundary = manualBoundaryRequested.getAndSet(false);
                long endSilenceMs = bargeUtterance ? 450 : STREAM_END_SILENCE_MS;
                boolean endBySilence = speechMs > 650 && silenceMs > endSilenceMs;
                boolean endByLength = speechMs > STREAM_MAX_UTTERANCE_MS;
                if (forceBoundary || endBySilence || endByLength) {
                    byte[] boundaryPcm = utterancePcm.toByteArray();
                    if (boundaryPcm.length > 0) lastBoundaryPcm = boundaryPcm;
                    saveLastVoiceForDebug(boundaryPcm);
                    if (currentSession != null) currentSession.finishUtterance();
                    scheduleAsrRetry(sessionId, boundaryPcm);
                    prewarmAsrSession(llm.asrUrl);
                    if (streamingAsr == currentSession) streamingAsr = null;
                    currentSession = null;
                    utterancePcm.reset();
                    speechActive = false;
                    bargeUtterance = false;
                    voiceMs = 0;
                    silenceMs = 0;
                    speechMs = 0;
                }
            }
        } catch (SecurityException e) {
            setStatus("缺少麦克风权限");
        } catch (Throwable t) {
            Log.w(TAG, "continuous loop", t);
            setStatus("录音错误：" + t.getMessage());
        } finally {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
            releaseAudioEffects();
            FunAsrStreamingClient c = streamingAsr;
            streamingAsr = null;
            if (c != null) c.close();
            continuousRunning.set(false);
            listening.set(false);
        }
    }

    private FunAsrStreamingClient beginAsrSession(final int sessionId, String rawUrl) {
        final FunAsrStreamingClient[] ref = new FunAsrStreamingClient[1];
        ref[0] = new FunAsrStreamingClient(rawUrl, new FunAsrStreamingClient.Listener() {
            @Override public void onOpen() {
                ConversationLog.append(ctx, "system", "ASR session " + sessionId + " connected "
                        + FunAsrStreamingClient.normalizeWsUrl(rawUrl));
                if (sessionId == latestUtteranceAsrSession.get()) setStatus("聆听中...");
            }

            @Override public void onPartial(String text) {
                if (sessionId != latestUtteranceAsrSession.get()) return;
                String t = cleanupAsrText(text);
                if (!t.isEmpty()) {
                    Log.d(TAG, "ASR partial[" + sessionId + "]: " + compact(t, 80));
                    synchronized (streamPartial) {
                        streamPartial.setLength(0);
                        streamPartial.append(t);
                    }
                    if (!ttsSpeaking.get()) setStatus("听见：" + compact(t, 18));
                }
            }

            @Override public void onFinal(String text) {
                if (sessionId <= asrFinalSerial.get()) return;
                String clean = cleanupAsrText(text);
                if (!clean.isEmpty()) asrFinalSerial.set(sessionId);
                handleStreamingFinal(text, sessionId);
                clearStandbyAsrSession(ref[0]);
                asrSessions.remove(ref[0]);
                ref[0].close();
            }

            @Override public void onError(String msg) {
                ConversationLog.append(ctx, "error", "ASR session " + sessionId + ": " + msg);
                clearStandbyAsrSession(ref[0]);
                asrSessions.remove(ref[0]);
                if (sessionId == latestUtteranceAsrSession.get()) setStatus("正在重试识别...");
            }

            @Override public void onClosed() {
                clearStandbyAsrSession(ref[0]);
                asrSessions.remove(ref[0]);
                Log.d(TAG, "ASR session closed " + sessionId);
            }
        });
        asrSessions.add(ref[0]);
        ref[0].connect();
        return ref[0];
    }

    private void prewarmAsrSession(String rawUrl) {
        if (!continuousRunning.get() || shuttingDown.get()) return;
        synchronized (standbyAsrLock) {
            if (standbyAsr != null) return;
            int id = asrSessionSerial.incrementAndGet();
            FunAsrStreamingClient client = beginAsrSession(id, rawUrl);
            standbyAsr = new AsrSessionRef(id, client);
            ConversationLog.append(ctx, "system", "ASR session " + id + " prewarming");
        }
    }

    private AsrSessionRef takeStandbyAsrSession() {
        synchronized (standbyAsrLock) {
            AsrSessionRef result = standbyAsr;
            standbyAsr = null;
            return result;
        }
    }

    private void clearStandbyAsrSession(FunAsrStreamingClient client) {
        if (client == null) return;
        synchronized (standbyAsrLock) {
            if (standbyAsr != null && standbyAsr.client == client) standbyAsr = null;
        }
    }

    private boolean isVoiceFrame(long level, int threshold, boolean duringTts) {
        if (!duringTts) return level > threshold;
        int bargeThreshold = Math.max(threshold + BARGE_THRESHOLD_MARGIN,
                threshold * 4 / 3);
        return level > bargeThreshold;
    }

    private void handleStreamingFinal(String rawText, int sessionId) {
        if (sessionId != latestUtteranceAsrSession.get()) {
            ConversationLog.append(ctx, "system", "忽略过期 ASR final[" + sessionId + "]");
            return;
        }
        String text = cleanupAsrText(rawText);
        if (text.isEmpty()) {
            ConversationLog.append(ctx, "system", "ASR final[" + sessionId + "]: empty");
            if (sessionId == latestUtteranceAsrSession.get()) setStatus("常驻聆听中...");
            return;
        }
        if (isDuplicateFinal(text)) return;
        synchronized (streamPartial) { streamPartial.setLength(0); }
        asrFinalSerial.set(sessionId);
        Log.i(TAG, "ASR final[" + sessionId + "]: " + compact(text, 120));
        ConversationLog.append(ctx, "system", "ASR final[" + sessionId + "]: " + compact(text, 120));
        if (isLikelyEcho(text)) {
            ConversationLog.append(ctx, "heard", "[回声过滤] " + text);
            setStatus("回声已过滤");
            return;
        }
        SpeechConsumer consumer = speechConsumer;
        if (consumer != null) {
            try {
                if (consumer.onFinalSpeech(text)) return;
            } catch (Throwable t) {
                Log.w(TAG, "speech consumer", t);
            }
        }
        submitFinalText(text, true);
    }

    private void scheduleAsrRetry(final int sessionId, final byte[] pcm) {
        if (pcm == null || pcm.length <= SAMPLE_RATE) return;
        new Thread(() -> {
            try { Thread.sleep(ASR_RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
            if (!continuousRunning.get()) return;
            if (asrFinalSerial.get() >= sessionId) return;
            if (sessionId != latestUtteranceAsrSession.get()) return;
            ConversationLog.append(ctx, "system", "ASR offline final timeout, retry session "
                    + sessionId);
            recognizeBufferedWithFreshFunAsr(pcm, sessionId);
        }, "funasr-retry").start();
        setStatus("识别中...");
    }

    private void recognizeBufferedWithFreshFunAsr(final byte[] pcm, final int sessionId) {
        if (!bufferedAsrInFlight.compareAndSet(false, true)) {
            ConversationLog.append(ctx, "system", "ASR retry already running");
            return;
        }
        final FunAsrStreamingClient[] onceRef = new FunAsrStreamingClient[1];
        onceRef[0] = new FunAsrStreamingClient(new LlmClient(ctx).asrUrl,
                new FunAsrStreamingClient.Listener() {
            @Override public void onOpen() {
                new Thread(() -> {
                    try {
                        int off = 0;
                        while (off < pcm.length && continuousRunning.get()) {
                            int n = Math.min(STREAM_FRAME_SAMPLES * 2, pcm.length - off);
                            byte[] chunk = new byte[n];
                            System.arraycopy(pcm, off, chunk, 0, n);
                            onceRef[0].sendPcm(chunk);
                            off += n;
                            try { Thread.sleep(15); } catch (InterruptedException ignored) { break; }
                        }
                        onceRef[0].finishUtterance();
                    } catch (Throwable t) {
                        Log.w(TAG, "ASR retry send", t);
                    }
                }, "funasr-retry-send").start();
            }

            @Override public void onPartial(String text) {}

            @Override public void onFinal(String text) {
                String clean = cleanupAsrText(text);
                if (!clean.isEmpty() && asrFinalSerial.get() < sessionId) {
                    asrFinalSerial.set(sessionId);
                    ConversationLog.append(ctx, "system", "ASR retry final[" + sessionId + "]");
                    handleStreamingFinal(text, sessionId);
                } else if (clean.isEmpty()) {
                    ConversationLog.append(ctx, "system", "ASR retry final[" + sessionId + "]: empty");
                }
                bufferedAsrInFlight.set(false);
                asrSessions.remove(onceRef[0]);
                onceRef[0].close();
            }

            @Override public void onError(String msg) {
                ConversationLog.append(ctx, "error", "ASR retry: " + msg);
                bufferedAsrInFlight.set(false);
                asrSessions.remove(onceRef[0]);
            }

            @Override public void onClosed() {
                bufferedAsrInFlight.set(false);
                asrSessions.remove(onceRef[0]);
            }
        });
        asrSessions.add(onceRef[0]);
        onceRef[0].connect();
        new Thread(() -> {
            try { Thread.sleep(ASR_RETRY_TIMEOUT_MS); } catch (InterruptedException ignored) {}
            if (onceRef[0] != null) onceRef[0].close();
            bufferedAsrInFlight.set(false);
            if (continuousRunning.get() && asrFinalSerial.get() < sessionId) {
                ConversationLog.append(ctx, "error", "ASR retry timeout[" + sessionId + "]");
                setStatus("常驻聆听中...");
            }
        }, "funasr-retry-timeout").start();
    }

    private void saveLastVoiceForDebug(byte[] pcm) {
        if (pcm == null || pcm.length <= SAMPLE_RATE) return;
        try {
            java.io.FileOutputStream out = ctx.openFileOutput("last_voice.wav", Context.MODE_PRIVATE);
            out.write(toWav(pcm));
            out.close();
            ConversationLog.append(ctx, "system", "保存最近语音 bytes=" + pcm.length
                    + " durMs=" + (pcm.length / 2 * 1000 / SAMPLE_RATE));
        } catch (Throwable t) {
            Log.w(TAG, "save last voice", t);
        }
    }

    private void submitFinalText(String text, boolean autoMode) {
        turnExec.execute(() -> processFinalText(text, autoMode));
    }

    private void processFinalText(String heardText, boolean autoMode) {
        String text = cleanupAsrText(heardText);
        if (text.isEmpty()) return;
        LlmClient llm = new LlmClient(ctx);
        if (autoMode && !shouldReply(llm, text)) {
            ConversationLog.append(ctx, "heard", text);
            setStatus("听见：" + compact(text, 18));
            return;
        }
        if (llm.apiKey.isEmpty()) {
            ConversationLog.append(ctx, "heard", text);
            setStatus("未配置大模型 API Key");
            return;
        }

        boolean interrupted = busy.getAndSet(true);
        int turnId = turnSerial.incrementAndGet();
        try {
            if (interrupted) {
                ConversationLog.append(ctx, "system", "新语音打断上一轮：" + compact(text, 40));
            }
            cancelActiveCalls();
            stopTtsPlayback();
            ConversationLog.append(ctx, "user", text);
            setStatus("思考中...");

            List<LlmClient.Msg> msgs = new ArrayList<>();
            msgs.add(new LlmClient.Msg("system",
                    Prefs.get(ctx, Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE)
                            + "\n你正在常驻监听环境语音。只在用户确实需要你回应时回答。"
                            + "回答要像真人对话一样自然、简洁，不要解释你在监听。"));
            synchronized (dialogHistory) { msgs.addAll(dialogHistory); }
            msgs.add(new LlmClient.Msg("user", text));
            String reply = chatAndQueueTts(llm, msgs, turnId, false);
            if (turnId != turnSerial.get()) return;
            if (reply.startsWith("!")) {
                ConversationLog.append(ctx, "error", "LLM 失败：" + reply.substring(1));
                setStatus("LLM 失败：" + compact(reply.substring(1), 18));
                return;
            }
            reply = reply.trim();
            if (reply.isEmpty()) {
                setStatus("模型无返回");
                return;
            }
            ConversationLog.append(ctx, "assistant", reply);
            remember(text, reply);
            rememberSpoken(reply);
            lastAssistantAt = System.currentTimeMillis();
            setStatus(continuousRunning.get() ? "常驻聆听中..." : "");
        } catch (Throwable t) {
            Log.w(TAG, "process final", t);
            setStatus("错误：" + t.getMessage());
        } finally {
            if (turnId == turnSerial.get()) busy.set(false);
        }
    }

    private void startFallbackListening(boolean autoStop) {
        if (listening.getAndSet(true)) return;
        keepRecording = true;
        cancelRequested = false;
        heard = false;
        setStatus("聆听中...");
        fallbackExec.execute(() -> recordLoop(autoStop));
    }

    private void recordLoop(boolean autoStop) {
        recorder = createRecorder();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            listening.set(false);
            setStatus("录音失败（麦克风被占用）");
            return;
        }
        recorder.startRecording();
        short[] buf = new short[640];
        byte[][] preRoll = new byte[25][];
        int preIdx = 0;
        int preCount = 0;
        long silenceMs = 0;
        long voiceMs = 0;
        long speechMs = 0;
        long maxMs = autoStop ? AUTO_LISTEN_WINDOW_MS : MANUAL_MAX_MS;
        long started = System.currentTimeMillis();
        int threshold = Prefs.getI(ctx, Prefs.K_VAD_SENSITIVITY, 600);
        boolean recordingSpeech = !autoStop;
        bos = new ByteArrayOutputStream();

        try {
            while (keepRecording && System.currentTimeMillis() - started < maxMs) {
                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;
                long frameMs = Math.max(10, n * 1000L / SAMPLE_RATE);
                long level = rms(buf, n);
                boolean voiced = level > threshold;
                byte[] bytes = shortsToBytes(buf, n);
                if (!autoStop) {
                    if (voiced) heard = true;
                    bos.write(bytes, 0, bytes.length);
                    continue;
                }

                if (!recordingSpeech) {
                    preRoll[preIdx] = bytes;
                    preIdx = (preIdx + 1) % preRoll.length;
                    if (preCount < preRoll.length) preCount++;
                    voiceMs = voiced ? voiceMs + frameMs : 0;
                    if (voiceMs < STREAM_MIN_SPEECH_MS) continue;
                    recordingSpeech = true;
                    heard = true;
                    bos.reset();
                    int start = (preIdx - preCount + preRoll.length) % preRoll.length;
                    for (int i = 0; i < preCount; i++) {
                        byte[] b = preRoll[(start + i) % preRoll.length];
                        if (b != null) bos.write(b, 0, b.length);
                    }
                    silenceMs = 0;
                    speechMs = preCount * frameMs;
                    setStatus("听见声音...");
                    continue;
                }

                bos.write(bytes, 0, bytes.length);
                speechMs += frameMs;
                if (voiced) {
                    silenceMs = 0;
                    heard = true;
                } else {
                    silenceMs += frameMs;
                }
                if ((speechMs > 650 && silenceMs > 1000) || speechMs > 12_000) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "record", e);
        } finally {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
            releaseAudioEffects();
        }
        listening.set(false);
        if (heard && !cancelRequested && bos.size() > 0) {
            byte[] wav = toWav(bos.toByteArray());
            processLegacyWav(wav, autoStop);
        } else {
            setStatus("");
        }
    }

    private void processLegacyWav(byte[] wav, boolean autoMode) {
        turnExec.execute(() -> {
            LlmClient llm = new LlmClient(ctx);
            if (llm.asrUrl.isEmpty()) {
                setStatus("未配置 ASR，请在设置填写语音 API");
                return;
            }
            setStatus("识别中...");
            String asrText = transcribeBlocking(llm, wav, 60_000);
            if (asrText.startsWith("!")) {
                ConversationLog.append(ctx, "error", "ASR 失败：" + asrText.substring(1));
                setStatus("ASR 失败：" + compact(asrText.substring(1), 18));
                return;
            }
            submitFinalText(asrText, autoMode);
        });
    }

    private AudioRecord createRecorder() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(Math.max(minBuf, 2048) * 4, 8192);
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        AudioRecord r = null;
        int chosen = -1;
        for (int source : sources) {
            try {
                r = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    chosen = source;
                    break;
                }
                try { r.release(); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                if (r != null) {
                    try { r.release(); } catch (Throwable ignored) {}
                }
            }
            r = null;
        }
        if (r == null) {
            try {
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            } catch (Throwable ignored) {}
        }
        if (r != null && r.getState() == AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "AudioRecord source=" + sourceName(chosen) + " session=" + r.getAudioSessionId());
            enableAudioEffects(r.getAudioSessionId());
        }
        return r == null ? new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) : r;
    }

    private String sourceName(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        if (source == MediaRecorder.AudioSource.MIC) return "MIC";
        return String.valueOf(source);
    }

    private void enableAudioEffects(int sessionId) {
        releaseAudioEffects();
        aec = enableEffect("AEC", AcousticEchoCanceler.isAvailable(),
                () -> AcousticEchoCanceler.create(sessionId));
        ns = enableEffect("NS", NoiseSuppressor.isAvailable(),
                () -> NoiseSuppressor.create(sessionId));
        agc = enableEffect("AGC", AutomaticGainControl.isAvailable(),
                () -> AutomaticGainControl.create(sessionId));
    }

    private interface EffectFactory { AudioEffect create(); }

    private AudioEffect enableEffect(String name, boolean available, EffectFactory factory) {
        if (!available) {
            Log.w(TAG, name + " unavailable");
            ConversationLog.append(ctx, "system", name + " unavailable");
            return null;
        }
        try {
            AudioEffect effect = factory.create();
            if (effect == null) {
                Log.w(TAG, name + " create null");
                ConversationLog.append(ctx, "system", name + " create null");
                return null;
            }
            int result = effect.setEnabled(true);
            boolean enabled = effect.getEnabled();
            Log.i(TAG, name + " enable result=" + result + " enabled=" + enabled);
            ConversationLog.append(ctx, "system", name + " enabled=" + enabled);
            return effect;
        } catch (Throwable t) {
            Log.w(TAG, name + " enable failed", t);
            ConversationLog.append(ctx, "system", name + " failed: " + t.getMessage());
            return null;
        }
    }

    private void releaseAudioEffects() {
        if (aec != null) { try { aec.release(); } catch (Throwable ignored) {} aec = null; }
        if (ns != null) { try { ns.release(); } catch (Throwable ignored) {} ns = null; }
        if (agc != null) { try { agc.release(); } catch (Throwable ignored) {} agc = null; }
    }

    private String transcribeBlocking(LlmClient llm, byte[] wav, long timeoutMs) {
        final String[] text = {""};
        final Object lock = new Object();
        llm.transcribe(wav, new LlmClient.TextCallback() {
            @Override public void onResult(String t) {
                synchronized (lock) {
                    text[0] = t == null ? "" : t;
                    lock.notifyAll();
                }
            }

            @Override public void onError(String msg) {
                synchronized (lock) {
                    text[0] = "!" + (msg == null ? "未知错误" : msg);
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) { try { lock.wait(timeoutMs); } catch (InterruptedException ignored) {} }
        return text[0] == null ? "" : text[0];
    }

    private boolean shouldReply(LlmClient llm, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || isFiller(t)) return false;
        if (looksAddressedOrQuestion(t)) return true;
        if (looksLikeFollowup(t)) return true;
        if (llm.apiKey.isEmpty()) return false;
        String clean = normalizeSpeechText(t);
        if (clean.length() >= 8) return true;
        String verdict = chatBlocking(llm, gateMessages(t), 20_000).toUpperCase(Locale.US);
        return verdict.contains("REPLY");
    }

    private List<LlmClient.Msg> gateMessages(String text) {
        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("system",
                "你是常驻语音助手的门控，只能输出 REPLY 或 IGNORE。\n"
                        + "REPLY：用户明显在问你、叫你、命令你、请求帮助，或明确延续刚才和你的对话。\n"
                        + "IGNORE：背景闲聊、电视/音乐/广告口播、旁人互相说话、自言自语、无意义语气词、听错片段。\n"
                        + "不解释，不加标点。"));
        msgs.add(new LlmClient.Msg("user", "语音转写：" + text));
        return msgs;
    }

    private boolean looksAddressedOrQuestion(String text) {
        String t = text.toLowerCase(Locale.US);
        String[] hot = {"真理", "罗盘", "助手", "小罗", "ai", "帮我", "给我", "告诉我", "回答",
                "解释", "总结", "翻译", "查一下", "搜一下", "打开", "关闭", "播放", "暂停",
                "你好", "在吗", "嗨", "哈喽", "hello", "hi", "讲", "说", "请", "来一个",
                "下一首", "上一首", "怎么", "为什么", "为啥", "什么", "哪里", "哪个", "多少",
                "谁", "几", "如何", "能不能", "可不可以", "是不是", "有没有", "吗", "呢",
                "?", "？"};
        for (String s : hot) if (t.contains(s)) return true;
        return false;
    }

    private boolean looksLikeFollowup(String text) {
        if (System.currentTimeMillis() - lastAssistantAt > FOLLOWUP_WINDOW_MS) return false;
        String t = text.replaceAll("[\\s，。！？,.!?、~～…]", "");
        if (t.length() < 2 || t.length() > 40) return false;
        String[] starters = {"那", "然后", "继续", "还有", "这个", "那个", "它", "他", "她",
                "刚才", "你刚才", "再说", "换个", "具体点", "详细点", "简单点"};
        for (String s : starters) if (t.startsWith(s)) return true;
        return false;
    }

    private boolean isFiller(String text) {
        String t = text.replaceAll("[\\s，。！？,.!?、~～…]", "");
        if (t.length() <= 1) return true;
        String[] fillers = {"嗯", "啊", "哦", "喔", "呃", "额", "唉", "诶", "对", "是", "好",
                "好的", "行", "可以", "谢谢", "哈哈", "呵呵", "喂", "thankyou", "thanks"};
        String lower = t.toLowerCase(Locale.US);
        for (String f : fillers) if (lower.equals(f.toLowerCase(Locale.US))) return true;
        return false;
    }

    private String chatBlocking(LlmClient llm, List<LlmClient.Msg> msgs, long timeoutMs) {
        final StringBuilder reply = new StringBuilder();
        final Object doneLock = new Object();
        final boolean[] done = {false};
        Call call = llm.chat(msgs, false, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                synchronized (doneLock) { reply.append(s); }
            }

            @Override public void onDone(String full) {
                synchronized (doneLock) {
                    if (reply.length() == 0 && full != null) reply.append(full);
                    done[0] = true;
                    doneLock.notifyAll();
                }
            }

            @Override public void onError(String msg) {
                synchronized (doneLock) {
                    reply.setLength(0);
                    reply.append("!").append(msg);
                    done[0] = true;
                    doneLock.notifyAll();
                }
            }
        });
        trackCall(call);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (doneLock) {
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { doneLock.wait(250); } catch (InterruptedException ignored) { break; }
            }
        }
        untrackCall(call);
        return reply.toString();
    }

    private String chatAndQueueTts(LlmClient llm, List<LlmClient.Msg> msgs, int turnId, boolean vision) {
        final StringBuilder full = new StringBuilder();
        final StringBuilder pending = new StringBuilder();
        final Object doneLock = new Object();
        final boolean[] done = {false};
        final String[] error = {null};
        final boolean[] hadDelta = {false};
        Call call = llm.chat(msgs, vision, new LlmClient.StreamCallback() {
            @Override public void onDelta(String s) {
                if (turnId != turnSerial.get() || s == null || s.isEmpty()) return;
                List<String> segments;
                synchronized (doneLock) {
                    hadDelta[0] = true;
                    full.append(s);
                    pending.append(s);
                    segments = drainSpeakableSegments(pending, false);
                }
                for (String seg : segments) enqueueTts(turnId, seg);
            }

            @Override public void onDone(String finalText) {
                List<String> segments;
                synchronized (doneLock) {
                    if (!hadDelta[0] && finalText != null && !finalText.isEmpty()) {
                        full.append(finalText);
                        pending.append(finalText);
                    }
                    segments = drainSpeakableSegments(pending, true);
                    done[0] = true;
                    doneLock.notifyAll();
                }
                if (turnId == turnSerial.get()) {
                    for (String seg : segments) enqueueTts(turnId, seg);
                }
            }

            @Override public void onError(String msg) {
                synchronized (doneLock) {
                    error[0] = msg == null ? "未知错误" : msg;
                    done[0] = true;
                    doneLock.notifyAll();
                }
            }
        });
        trackCall(call);
        synchronized (doneLock) {
            long deadline = System.currentTimeMillis() + 120_000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { doneLock.wait(500); } catch (InterruptedException ignored) { break; }
                if (turnId != turnSerial.get()) break;
            }
        }
        untrackCall(call);
        if (error[0] != null) return "!" + error[0];
        return full.toString();
    }

    private List<String> drainSpeakableSegments(StringBuilder pending, boolean finishing) {
        List<String> out = new ArrayList<>();
        while (pending.length() > 0) {
            trimLeading(pending);
            int cut = findSentenceCut(pending, finishing);
            if (cut <= 0) break;
            String seg = pending.substring(0, cut).trim();
            pending.delete(0, cut);
            if (!seg.isEmpty()) out.add(seg);
        }
        return out;
    }

    private void trimLeading(StringBuilder sb) {
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(0))) sb.deleteCharAt(0);
    }

    private int findSentenceCut(StringBuilder sb, boolean finishing) {
        int len = sb.length();
        if (len == 0) return -1;
        String enders = "。！？!?；;\n";
        for (int i = 0; i < len; i++) {
            if (i >= 5 && enders.indexOf(sb.charAt(i)) >= 0) return i + 1;
        }
        if (!finishing && len > 38) {
            for (int i = Math.min(len - 1, 46); i >= 18; i--) {
                if ("，,、 ".indexOf(sb.charAt(i)) >= 0) return i + 1;
            }
            return Math.min(len, 42);
        }
        return finishing ? len : -1;
    }

    private void enqueueTts(int turnId, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || turnId != turnSerial.get()) return;
        rememberSpoken(t);
        ttsQueue.offer(new TtsJob(turnId, t));
    }

    private void startTtsWorker() {
        if (!ttsWorkerStarted.compareAndSet(false, true)) return;
        Thread synth = new Thread(this::ttsSynthesisLoop, "voice-tts-synth");
        Thread play = new Thread(this::ttsPlaybackLoop, "voice-tts-play");
        synth.setDaemon(true);
        play.setDaemon(true);
        synth.start();
        play.start();
    }

    /** Synthesize ahead while the playback thread is speaking the previous sentence. */
    private void ttsSynthesisLoop() {
        while (!shuttingDown.get()) {
            try {
                TtsJob job = ttsQueue.poll(300, TimeUnit.MILLISECONDS);
                if (job == null || job.turnId != turnSerial.get()) continue;
                PreparedTts prepared = synthesizeCloudTts(job);
                if (prepared == null || job.turnId != turnSerial.get()) continue;
                while (!readyTtsQueue.offer(prepared, 250, TimeUnit.MILLISECONDS)) {
                    if (shuttingDown.get() || job.turnId != turnSerial.get()) break;
                }
            } catch (InterruptedException ignored) {
                break;
            } catch (Throwable t) {
                Log.w(TAG, "tts synth loop", t);
            }
        }
    }

    private void ttsPlaybackLoop() {
        while (!shuttingDown.get()) {
            try {
                PreparedTts prepared = readyTtsQueue.poll(300, TimeUnit.MILLISECONDS);
                if (prepared == null || prepared.job.turnId != turnSerial.get()) continue;
                playPreparedTts(prepared);
            } catch (InterruptedException ignored) {
                break;
            } catch (Throwable t) {
                Log.w(TAG, "tts play loop", t);
            }
        }
    }

    private PreparedTts synthesizeCloudTts(TtsJob job) {
        LlmClient llm = new LlmClient(ctx);
        if (llm.ttsUrl.isEmpty()) {
            ConversationLog.append(ctx, "error", "TTS 失败：未配置云端 TTS，已禁止本地回退");
            setStatus("未配置云端 TTS");
            return null;
        }
        ConversationLog.append(ctx, "system", "TTS synth start: " + compact(job.text, 40));
        final byte[][] audio = {null};
        final String[] contentType = {""};
        final String[] error = {null};
        final boolean[] done = {false};
        final Object lock = new Object();
        Call call = llm.synthesize(job.text, new LlmClient.BytesCallback() {
            @Override public void onResult(byte[] bytes) {
                synchronized (lock) {
                    audio[0] = bytes;
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override public void onResult(byte[] bytes, String ct) {
                synchronized (lock) {
                    audio[0] = bytes;
                    contentType[0] = ct == null ? "" : ct;
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override public void onError(String msg) {
                synchronized (lock) {
                    error[0] = msg == null ? "未知错误" : msg;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        trackCall(call);
        if (!ttsSpeaking.get() && readyTtsQueue.isEmpty()) setStatus("合成中...");
        long synthDeadline = System.currentTimeMillis() + 60_000;
        synchronized (lock) {
            while (!done[0] && System.currentTimeMillis() < synthDeadline
                    && job.turnId == turnSerial.get()) {
                try { lock.wait(250); } catch (InterruptedException ignored) { break; }
            }
        }
        untrackCall(call);
        if (job.turnId != turnSerial.get()) {
            ConversationLog.append(ctx, "system", "TTS 已被新一轮对话取消");
            return null;
        }
        if (audio[0] == null || audio[0].length == 0) {
            String msg = error[0] == null ? "无音频返回" : error[0];
            ConversationLog.append(ctx, "error", "TTS 失败：" + msg);
            setStatus("TTS 失败：" + compact(msg, 18));
            return null;
        }
        return new PreparedTts(job, audio[0], contentType[0]);
    }

    private void playPreparedTts(PreparedTts prepared) {
        TtsJob job = prepared.job;
        if (job.turnId != turnSerial.get()) return;
        ConversationLog.append(ctx, "system", "TTS audio bytes=" + prepared.audio.length
                + " ct=" + compact(prepared.contentType, 40));

        lastTtsStartAt = System.currentTimeMillis();
        ttsHoldoffUntil = lastTtsStartAt + TTS_START_HOLDOFF_MS;
        ttsSpeaking.set(true);
        setStatus("播报中...");
        CloudTts.play(ctx, prepared.audio, prepared.contentType, null);
        long deadline = System.currentTimeMillis() + 90_000;
        while (CloudTts.isPlaying() && job.turnId == turnSerial.get()
                && !shuttingDown.get() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(60); } catch (InterruptedException ignored) { break; }
        }
        if (job.turnId != turnSerial.get()) CloudTts.stop();
        ttsSpeaking.set(false);
        lastTtsEndAt = System.currentTimeMillis();
        ttsHoldoffUntil = lastTtsEndAt + TTS_END_HOLDOFF_MS;
    }

    private void stopTtsPlayback() {
        ttsQueue.clear();
        readyTtsQueue.clear();
        CloudTts.stop();
        LocalTts.stopPlayback();
        ttsSpeaking.set(false);
        lastTtsEndAt = System.currentTimeMillis();
        ttsHoldoffUntil = lastTtsEndAt + TTS_END_HOLDOFF_MS;
    }

    private void remember(String user, String assistant) {
        synchronized (dialogHistory) {
            dialogHistory.add(new LlmClient.Msg("user", user));
            dialogHistory.add(new LlmClient.Msg("assistant", assistant));
            while (dialogHistory.size() > 8) dialogHistory.remove(0);
        }
    }

    private void rememberSpoken(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        recentSpokenText = compact((recentSpokenText + " " + t).trim(), 500);
        recentSpokenNorm = normalizeSpeechText(recentSpokenText);
    }

    private boolean isDuplicateFinal(String text) {
        String norm = normalizeSpeechText(text);
        long now = System.currentTimeMillis();
        if (!norm.isEmpty() && norm.equals(lastFinalNorm) && now - lastFinalAt < 1800) return true;
        lastFinalNorm = norm;
        lastFinalAt = now;
        return false;
    }

    private boolean isLikelyEcho(String text) {
        String norm = normalizeSpeechText(text);
        String spoken = recentSpokenNorm;
        if (norm.length() < 4 || spoken.length() < 4) return false;
        long now = System.currentTimeMillis();
        boolean echoWindow = ttsSpeaking.get()
                || now < ttsHoldoffUntil
                || now - lastTtsStartAt < 8000
                || now - lastTtsEndAt < 2500;
        if (!echoWindow) return false;
        if (spoken.contains(norm) || norm.contains(spoken)) return true;
        return lcsRatio(norm, spoken) > 0.68f;
    }

    private float lcsRatio(String a, String b) {
        if (a.length() > 120) a = a.substring(0, 120);
        if (b.length() > 120) b = b.substring(0, 120);
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) cur[j] = prev[j - 1] + 1;
                else cur[j] = Math.max(prev[j], cur[j - 1]);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()] / (float) Math.max(1, Math.min(a.length(), b.length()));
    }

    private String cleanupAsrText(String s) {
        if (s == null) return "";
        return s.replaceAll("<\\|[^|]+\\|>", "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private String normalizeSpeechText(String s) {
        if (s == null) return "";
        return cleanupAsrText(s).toLowerCase(Locale.US)
                .replaceAll("[\\s，。！？,.!?、~～…；;：:\"'“”‘’（）()\\[\\]{}<>《》-]", "");
    }

    private String compact(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(1, max - 1)) + "…";
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
        writeLEStr(w, "RIFF");
        writeLEInt(w, 36 + pcm.length);
        writeLEStr(w, "WAVE");
        writeLEStr(w, "fmt ");
        writeLEInt(w, 16);
        writeLEShort(w, 1);
        writeLEShort(w, 1);
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

    private static class TtsJob {
        final int turnId;
        final String text;

        TtsJob(int turnId, String text) {
            this.turnId = turnId;
            this.text = text;
        }
    }

    private static class PreparedTts {
        final TtsJob job;
        final byte[] audio;
        final String contentType;

        PreparedTts(TtsJob job, byte[] audio, String contentType) {
            this.job = job;
            this.audio = audio;
            this.contentType = contentType == null ? "" : contentType;
        }
    }
}
