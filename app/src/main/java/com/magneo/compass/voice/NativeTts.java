package com.magneo.compass.voice;

/** eSpeak NG 的 JNI 封装（bagua_tts.so），本地离线中文/英文合成。 */
public class NativeTts {
    public static final boolean available;
    static {
        boolean ok = false;
        try { System.loadLibrary("bagua_tts"); ok = true; } catch (Throwable t) { ok = false; }
        available = ok;
    }
    public static native int nativeInit(String dataPath, String voice);
    public static native short[] nativeSpeak(String text);
    public static native void nativeStop();
    public static native void nativeShutdown();
}
