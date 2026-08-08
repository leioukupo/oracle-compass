package com.magneo.compass;

import android.content.Context;
import android.content.SharedPreferences;

/** 统一的设置存取（SharedPreferences），含大模型/语音/视觉/浏览器/网盘配置。 */
public class Prefs {
    public static final String K_PROVIDER = "provider";
    public static final String K_BASE_URL = "baseUrl";
    public static final String K_API_KEY = "apiKey";
    public static final String K_TEXT_MODEL = "textModel";
    public static final String K_VISION_MODEL = "visionModel";
    public static final String K_ASR_URL = "asrUrl";
    public static final String K_ASR_MODEL = "asrModel";
    public static final String K_TTS_URL = "ttsUrl";
    public static final String K_TTS_MODEL = "ttsModel";
    public static final String K_TTS_VOICE = "ttsVoice";
    public static final String K_LOCAL_TTS_FIRST = "localTtsFirst";
    public static final String K_IGNORE_SSL = "ignoreSsl";
    public static final String K_NO_IMAGES = "noImages";
    public static final String K_UA_DESKTOP = "uaDesktop";
    public static final String K_SEARCH_ENGINE = "searchEngine";
    public static final String K_VISION_ENABLED = "visionEnabled";
    public static final String K_VISION_INTERVAL = "visionInterval";
    public static final String K_VAD_ENABLED = "vadEnabled";
    public static final String K_VAD_SENSITIVITY = "vadSensitivity";
    public static final String K_PINNED_APPS = "pinnedApps";
    public static final String K_FS_CONNECTIONS = "fsConnections";
    public static final String K_BOOKMARKS = "bookmarks";
    public static final String K_HISTORY = "history";
    public static final String K_CONV_MAX_KB = "convMaxKb";
    public static final String K_FRPC_CONFIG = "frpcConfig";
    public static final String K_CAM_ID = "camId";
    public static final String K_CAM_WIDTH = "camWidth";
    public static final String K_CAM_HEIGHT = "camHeight";
    public static final String K_CAM_FPS = "camFps";
    public static final String K_CAM_BITRATE = "camBitrate";
    public static final String K_RTSP_PORT = "rtspPort";
    public static final String K_RTMP_URL = "rtmpUrl";
    public static final String K_CAM_AUTO_START = "camAutoStart";
    public static final String K_CONV_CLEAN_MIN = "convCleanMin";
    public static final String K_SYS_PROMPT_VOICE = "sysPromptVoice";
    public static final String K_STREAM_FPS = "streamFps";
    public static final String K_STREAM_QUALITY = "streamQuality";
    public static final String K_STREAM_SCALE = "streamScale";
    public static final String K_STREAM_BITRATE = "streamBitrate";
    public static final String K_SYS_PROMPT_VISION = "sysPromptVision";
    public static final String K_SHOW_LOC = "showLoc";
    public static final String K_LOC_WIFI_URL = "locWifiUrl";
    public static final String K_LOC_IP_URL = "locIpUrl";
    public static final String DEFAULT_LOC_WIFI_URL = "https://location.services.mozilla.com/v1/geolocate?key=test";
    public static final String DEFAULT_LOC_IP_URL = "http://ip-api.com/json/?lang=zh-CN";
    public static final String DEFAULT_SYS_PROMPT_VOICE = "你是真理罗盘助手，回答简洁，中文回复。";
    public static final String DEFAULT_SYS_PROMPT_VISION = "你是圆屏设备“真理罗盘”的视觉感知模块。看图后用中文简述你看到的环境（物体/人/光线/场景），若与上一帧相比有明显变化，指出变化；若没有变化就说“无明显变化”。一句话即可。";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bagua", Context.MODE_PRIVATE);
    }

    public static String get(Context c, String k, String def) { return sp(c).getString(k, def); }
    public static void put(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }
    public static boolean getB(Context c, String k, boolean def) {
        try {
            return sp(c).getBoolean(k, def);
        } catch (ClassCastException e) {
            // 兼容历史误存为字符串的布尔值
            String v = sp(c).getString(k, null);
            if (v == null) return def;
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }
    }
    public static void putB(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
    public static int getI(Context c, String k, int def) {
        try {
            Object v = sp(c).getAll().get(k);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof String) return Integer.parseInt((String) v);
        } catch (Exception ignored) {}
        return def;
    }
    public static void putI(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
}
