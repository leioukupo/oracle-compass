#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <vector>
#include <string>
#include <cstring>

static std::vector<short> g_samples;
static int s_synth_cb(short *wav, int numsamples, espeak_EVENT *) {
    if (wav && numsamples > 0) {
        g_samples.insert(g_samples.end(), wav, wav + numsamples);
    }
    return 0;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_magneo_compass_voice_NativeTts_nativeInit(JNIEnv *env, jclass,
                                                   jstring dataPath, jstring voice) {
    const char *p = env->GetStringUTFChars(dataPath, nullptr);
    const char *v = env->GetStringUTFChars(voice, nullptr);
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, p, 0);
    if (rate >= 0) {
        espeak_SetVoiceByName(v);
        espeak_SetSynthCallback(s_synth_cb);
        espeak_SetParameter(espeakRATE, 170, 0);
    }
    env->ReleaseStringUTFChars(dataPath, p);
    env->ReleaseStringUTFChars(voice, v);
    return rate;
}

JNIEXPORT jshortArray JNICALL
Java_com_magneo_compass_voice_NativeTts_nativeSpeak(JNIEnv *env, jclass, jstring text) {
    const char *t = env->GetStringUTFChars(text, nullptr);
    g_samples.clear();
    unsigned int ident = 0;
    espeak_Synth(t, strlen(t), 0, POS_CHARACTER, 0, espeakCHARS_UTF8 | espeakENDPAUSE, &ident, nullptr);
    env->ReleaseStringUTFChars(text, t);
    jshortArray out = env->NewShortArray((jsize) g_samples.size());
    if (out) {
        env->SetShortArrayRegion(out, 0, (jsize) g_samples.size(), g_samples.data());
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_magneo_compass_voice_NativeTts_nativeStop(JNIEnv *, jclass) {
    espeak_Cancel();
}

JNIEXPORT void JNICALL
Java_com_magneo_compass_voice_NativeTts_nativeShutdown(JNIEnv *, jclass) {
    espeak_Terminate();
}

}
