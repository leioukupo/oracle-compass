package com.magneo.compass.voice;

import android.os.SystemClock;

import java.util.concurrent.CopyOnWriteArraySet;

/** Process-wide, allocation-light state sampled by the main-screen animation layer. */
public final class VoiceVisualState {
    public interface Listener {
        void onVoiceVisualStateChanged();
    }

    public static final class Snapshot {
        public final VoiceVisualPhase phase;
        public final float inputLevel;
        public final float outputLevel;
        public final long phaseStartedAtMs;
        public final long errorUntilMs;

        private Snapshot(VoiceVisualPhase phase, float inputLevel, float outputLevel,
                         long phaseStartedAtMs, long errorUntilMs) {
            this.phase = phase;
            this.inputLevel = inputLevel;
            this.outputLevel = outputLevel;
            this.phaseStartedAtMs = phaseStartedAtMs;
            this.errorUntilMs = errorUntilMs;
        }
    }

    private static final long ERROR_DURATION_MS = 1400L;
    private static final long REAL_OUTPUT_GRACE_MS = 500L;
    private static final CopyOnWriteArraySet<Listener> listeners =
            new CopyOnWriteArraySet<>();

    private static volatile VoiceVisualPhase basePhase = VoiceVisualPhase.IDLE;
    private static volatile long basePhaseStartedAtMs = SystemClock.uptimeMillis();
    private static volatile long errorStartedAtMs;
    private static volatile long errorUntilMs;
    private static volatile long outputStartedAtMs;
    private static volatile long realOutputAtMs;
    private static volatile float inputLevel;
    private static volatile float outputLevel;

    private VoiceVisualState() {}

    public static void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public static void setPhase(VoiceVisualPhase phase) {
        if (phase == null || phase == VoiceVisualPhase.ERROR) return;
        long now = SystemClock.uptimeMillis();
        boolean changed = basePhase != phase;
        if (changed) {
            basePhase = phase;
            basePhaseStartedAtMs = now;
            if (phase == VoiceVisualPhase.SPEAKING) {
                outputStartedAtMs = now;
                realOutputAtMs = 0L;
                outputLevel = 0f;
            } else {
                outputLevel = 0f;
                realOutputAtMs = 0L;
            }
        }
        if (phase == VoiceVisualPhase.IDLE) inputLevel = 0f;
        if (changed) notifyListeners();
    }

    public static void showError() {
        long now = SystemClock.uptimeMillis();
        errorStartedAtMs = now;
        errorUntilMs = now + ERROR_DURATION_MS;
        notifyListeners();
    }

    public static void setInputLevel(float level) {
        inputLevel = clamp(level);
    }

    public static void setOutputLevel(float level) {
        float target = clamp(level);
        float old = outputLevel;
        float alpha = target > old ? 0.48f : 0.16f;
        outputLevel = old + (target - old) * alpha;
        realOutputAtMs = SystemClock.uptimeMillis();
    }

    public static void clearOutputLevel() {
        outputLevel = 0f;
        realOutputAtMs = 0L;
    }

    public static Snapshot snapshot() {
        long now = SystemClock.uptimeMillis();
        boolean error = now < errorUntilMs;
        VoiceVisualPhase phase = error ? VoiceVisualPhase.ERROR : basePhase;
        float out = phase == VoiceVisualPhase.SPEAKING ? resolvedOutputLevel(now) : 0f;
        long started = error ? errorStartedAtMs : basePhaseStartedAtMs;
        return new Snapshot(phase, inputLevel, out, started, errorUntilMs);
    }

    public static void reset() {
        basePhase = VoiceVisualPhase.IDLE;
        basePhaseStartedAtMs = SystemClock.uptimeMillis();
        errorStartedAtMs = 0L;
        errorUntilMs = 0L;
        outputStartedAtMs = 0L;
        realOutputAtMs = 0L;
        inputLevel = 0f;
        outputLevel = 0f;
        notifyListeners();
    }

    private static float resolvedOutputLevel(long now) {
        if (realOutputAtMs > 0L && now - realOutputAtMs <= REAL_OUTPUT_GRACE_MS) {
            return clamp(outputLevel);
        }
        long elapsed = Math.max(0L, now - outputStartedAtMs);
        if (elapsed < REAL_OUTPUT_GRACE_MS) return 0.08f;
        double t = elapsed / 1000.0;
        double cadence = 0.46
                + 0.28 * Math.sin(t * 13.1)
                + 0.16 * Math.sin(t * 21.7 + 0.8)
                + 0.10 * Math.sin(t * 5.3 + 1.7);
        return clamp((float) cadence);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private static void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onVoiceVisualStateChanged();
            } catch (Throwable ignored) {}
        }
    }
}
