package com.magneo.compass.tts;

import android.speech.tts.TextToSpeech;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;

import com.magneo.compass.voice.LocalTts;
import com.magneo.compass.voice.NativeTts;

/** 注册为系统 TTS 引擎：装 app 即自带离线 TTS 包（eSpeak NG）。 */
public class BaguaTtsService extends TextToSpeechService {

    private static void synthAudio(SynthesisCallback cb, byte[] b) {
        try { cb.audioAvailable(b, 0, b.length); return; } catch (Throwable t) {}
        try {
            java.lang.reflect.Method m = cb.getClass().getMethod("audio", byte[].class);
            m.invoke(cb, (Object) b);
        } catch (Throwable ignored) {}
    }

    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override protected String[] onGetLanguage() {
        return new String[]{"cmn", "", ""};
    }

    @Override protected void onStop() {
        if (NativeTts.available) {
            try { NativeTts.nativeStop(); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        try {
            if (!NativeTts.available || !LocalTts.ensureInit(this)) {
                callback.error();
                return;
            }
            short[] pcm = NativeTts.nativeSpeak(request.getCharSequenceText().toString());
            if (pcm == null || pcm.length == 0) { callback.error(); return; }
            byte[] bytes = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                bytes[i * 2] = (byte) (pcm[i] & 0xff);
                bytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            if (callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1) != 0) {
                synthAudio(callback, bytes);
                callback.done();
            } else {
                callback.error();
            }
        } catch (Throwable t) {
            callback.error();
        }
    }
}
