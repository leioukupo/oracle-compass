package com.magneo.compass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;

/** 统一的设置存取（SharedPreferences），含大模型/语音/视觉/浏览器/网盘配置。 */
public class Prefs {
    private static final String BACKUP_DIR = "oracle-compass-backup";
    private static final String BACKUP_FILE = "prefs.json";

    public static final String K_PROVIDER = "provider";
    public static final String K_BASE_URL = "baseUrl";
    public static final String K_TEXT_BASE_URL = "textBaseUrl";
    public static final String K_VISION_BASE_URL = "visionBaseUrl";
    public static final String K_API_KEY = "apiKey";
    public static final String K_VOICE_API_KEY = "voiceApiKey";
    public static final String K_REASONING_EFFORT = "reasoningEffort";
    public static final String K_TEXT_MAX_TOKENS = "textMaxTokens";
    public static final String K_VOICE_MAX_TOKENS = "voiceMaxTokens";
    public static final String K_VISION_MAX_TOKENS = "visionMaxTokens";
    public static final String K_TEXT_TEMPERATURE = "textTemperature";
    public static final String K_VOICE_TEMPERATURE = "voiceTemperature";
    public static final String K_VISION_TEMPERATURE = "visionTemperature";
    public static final String K_TEXT_MODEL = "textModel";
    public static final String K_VISION_MODEL = "visionModel";
    public static final String K_ASR_URL = "asrUrl";
    public static final String K_ASR_FINAL_URL = "asrFinalUrl";
    public static final String K_ASR_MODEL = "asrModel";
    public static final String K_TTS_URL = "ttsUrl";
    public static final String K_TTS_MODEL = "ttsModel";
    public static final String K_TTS_VOICE = "ttsVoice";
    public static final String K_LOCAL_TTS_FIRST = "localTtsFirst";
    public static final String K_BARGE_MODE = "bargeMode";
    public static final String K_INTERACTION_MODE = "interactionMode";
    public static final String K_IGNORE_SSL = "ignoreSsl";
    public static final String K_NO_IMAGES = "noImages";
    public static final String K_UA_DESKTOP = "uaDesktop";
    public static final String K_BROWSER_ROUND_FIT = "browserRoundFit";
    public static final String K_SEARCH_ENGINE = "searchEngine";
    public static final String K_VISION_ENABLED = "visionEnabled";
    public static final String K_VISION_INTERVAL = "visionInterval";
    public static final String K_VISION_FRAME_SOURCE = "visionFrameSource";
    public static final String K_VAD_ENABLED = "vadEnabled";
    public static final String K_VAD_SENSITIVITY = "vadSensitivity";
    public static final String K_PINNED_APPS = "pinnedApps";
    public static final String K_FS_CONNECTIONS = "fsConnections";
    public static final String K_BOOKMARKS = "bookmarks";
    public static final String K_HISTORY = "history";
    public static final String K_CONV_MAX_KB = "convMaxKb";
    public static final String K_FRPC_CONFIG = "frpcConfig";
    public static final String K_WEB_ADMIN_SALT = "webAdminSalt";
    public static final String K_WEB_ADMIN_HASH = "webAdminHash";
    public static final String K_ADB_TCP_AUTO = "adbTcpAuto";
    public static final String K_ADB_TCP_PORT = "adbTcpPort";
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
    public static final String K_STREAM_MODE = "streamMode";
    public static final String K_STREAM_FPS = "streamFps";
    public static final String K_STREAM_QUALITY = "streamQuality";
    public static final String K_STREAM_SCALE = "streamScale";
    public static final String K_STREAM_BITRATE = "streamBitrate";
    public static final String K_MAIN_RENDERER = "mainRenderer";
    public static final String K_MAIN_FPS_MODE = "mainFpsMode";
    public static final String K_MAG_CAL_X = "magCalX";
    public static final String K_MAG_CAL_Y = "magCalY";
    public static final String K_MAG_CAL_Z = "magCalZ";
    public static final String K_MAG_CAL_QUALITY = "magCalQuality";
    public static final String K_ORACLE_SHAKE_LEVEL = "oracleShakeLevel";
    public static final String K_ORACLE_SHAKE_FORCE = "oracleShakeForce";
    public static final String K_SYS_PROMPT_VISION = "sysPromptVision";
    public static final String K_SHOW_LOC = "showLoc";
    public static final String K_LOC_WIFI_URL = "locWifiUrl";
    public static final String K_LOC_IP_URL = "locIpUrl";
    public static final String DEFAULT_LOC_WIFI_URL = "https://location.services.mozilla.com/v1/geolocate?key=test";
    public static final String DEFAULT_LOC_IP_URL = "";
    public static final String DEFAULT_SYS_PROMPT_VOICE = "你是真理罗盘助手，回答简洁，中文回复。";
    public static final String DEFAULT_SYS_PROMPT_VISION = "你是圆屏设备“真理罗盘”的视觉感知模块。看图后用中文简述你看到的环境（物体/人/光线/场景），若与上一帧相比有明显变化，指出变化；若没有变化就说“无明显变化”。一句话即可。";
    public static final String DEFAULT_REASONING_EFFORT = "auto";
    public static final boolean DEFAULT_VAD_ENABLED = true;
    public static final String VISION_FRAME_SOURCE_HAL = "hal";
    public static final String VISION_FRAME_SOURCE_RTSP = "rtsp";
    public static final String DEFAULT_VISION_FRAME_SOURCE = VISION_FRAME_SOURCE_HAL;
    public static final String MAIN_RENDERER_GL = "gl";
    public static final String MAIN_RENDERER_CANVAS = "canvas";
    public static final String DEFAULT_MAIN_RENDERER = MAIN_RENDERER_GL;
    public static final String MAIN_FPS_ADAPTIVE = "adaptive";
    public static final String MAIN_FPS_POWER = "power";
    public static final String MAIN_FPS_SMOOTH = "smooth";
    public static final String DEFAULT_MAIN_FPS_MODE = MAIN_FPS_ADAPTIVE;
    public static final int DEFAULT_TEXT_MAX_TOKENS = 1024;
    public static final int DEFAULT_VOICE_MAX_TOKENS = 800;
    public static final int DEFAULT_VISION_MAX_TOKENS = 512;
    public static final int DEFAULT_ORACLE_SHAKE_LEVEL = 4;
    public static final int DEFAULT_ORACLE_SHAKE_FORCE = 70;
    public static final float DEFAULT_TEXT_TEMPERATURE = 0.3f;
    public static final float DEFAULT_VOICE_TEMPERATURE = 0.2f;
    public static final float DEFAULT_VISION_TEMPERATURE = 0.2f;
    public static final String BARGE_MODE_OFF = "off";
    public static final String BARGE_MODE_STEADY = "steady";
    public static final String BARGE_MODE_SENSITIVE = "sensitive";
    public static final String DEFAULT_BARGE_MODE = BARGE_MODE_STEADY;
    public static final String INTERACTION_QUIET = "quiet";
    public static final String INTERACTION_NATURAL = "natural";
    public static final String INTERACTION_ACTIVE = "active";
    public static final String DEFAULT_INTERACTION_MODE = INTERACTION_NATURAL;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bagua", Context.MODE_PRIVATE);
    }

    public static String get(Context c, String k, String def) { return sp(c).getString(k, def); }
    public static void put(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
        exportBackup(c);
    }
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
    public static boolean vadEnabled(Context c) { return getB(c, K_VAD_ENABLED, DEFAULT_VAD_ENABLED); }
    public static void putB(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
        exportBackup(c);
    }
    public static boolean contains(Context c, String k) {
        return sp(c).contains(k);
    }
    public static int getI(Context c, String k, int def) {
        try {
            Object v = sp(c).getAll().get(k);
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof String) return Integer.parseInt((String) v);
        } catch (Exception ignored) {}
        return def;
    }
    public static void putI(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
        exportBackup(c);
    }
    public static float getF(Context c, String k, float def) {
        try {
            Object v = sp(c).getAll().get(k);
            if (v instanceof Float) return (Float) v;
            if (v instanceof Number) return ((Number) v).floatValue();
            if (v instanceof String) return Float.parseFloat((String) v);
        } catch (Exception ignored) {}
        return def;
    }
    public static void putF(Context c, String k, float v) {
        sp(c).edit().putFloat(k, v).apply();
        exportBackup(c);
    }

    public static String normalizeVisionFrameSource(String v) {
        return VISION_FRAME_SOURCE_RTSP.equalsIgnoreCase(v) ? VISION_FRAME_SOURCE_RTSP : VISION_FRAME_SOURCE_HAL;
    }

    public static String visionFrameSource(Context c) {
        return normalizeVisionFrameSource(get(c, K_VISION_FRAME_SOURCE, DEFAULT_VISION_FRAME_SOURCE));
    }

    public static String visionFrameSourceLabel(Context c) {
        return VISION_FRAME_SOURCE_RTSP.equals(visionFrameSource(c)) ? "RTSP同源" : "HAL直出";
    }

    public static String normalizeMainRenderer(String v) {
        return MAIN_RENDERER_CANVAS.equalsIgnoreCase(v) ? MAIN_RENDERER_CANVAS : MAIN_RENDERER_GL;
    }

    public static String mainRenderer(Context c) {
        return normalizeMainRenderer(get(c, K_MAIN_RENDERER, DEFAULT_MAIN_RENDERER));
    }

    public static boolean mainRendererGl(Context c) {
        return MAIN_RENDERER_GL.equals(mainRenderer(c));
    }

    public static String normalizeMainFpsMode(String v) {
        if (MAIN_FPS_POWER.equalsIgnoreCase(v)) return MAIN_FPS_POWER;
        if (MAIN_FPS_SMOOTH.equalsIgnoreCase(v)) return MAIN_FPS_SMOOTH;
        return MAIN_FPS_ADAPTIVE;
    }

    public static String mainFpsMode(Context c) {
        return normalizeMainFpsMode(get(c, K_MAIN_FPS_MODE, DEFAULT_MAIN_FPS_MODE));
    }

    public static String normalizeInteractionMode(String v) {
        if (INTERACTION_QUIET.equalsIgnoreCase(v)) return INTERACTION_QUIET;
        if (INTERACTION_ACTIVE.equalsIgnoreCase(v)) return INTERACTION_ACTIVE;
        return INTERACTION_NATURAL;
    }

    public static String interactionMode(Context c) {
        return normalizeInteractionMode(get(c, K_INTERACTION_MODE, DEFAULT_INTERACTION_MODE));
    }

    public static String interactionModeLabel(Context c) {
        String mode = interactionMode(c);
        if (INTERACTION_QUIET.equals(mode)) return "安静";
        if (INTERACTION_ACTIVE.equals(mode)) return "积极";
        return "自然";
    }

    public static boolean restoreBackupIfPresent(Context c) {
        File f = backupFile();
        if (!f.exists() || f.length() <= 0) return false;
        try {
            JSONObject o = new JSONObject(readAll(f));
            SharedPreferences.Editor e = sp(c).edit();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.get(k);
                if (v == JSONObject.NULL || k == null) continue;
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else if (v instanceof Number) e.putFloat(k, ((Number) v).floatValue());
                else e.putString(k, String.valueOf(v));
            }
            e.apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void exportBackup(Context c) {
        try {
            File dir = backupDir();
            if (!dir.exists() && !dir.mkdirs()) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                o.put(e.getKey(), v);
            }
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(backupFile()), "UTF-8")) {
                w.write(o.toString(2));
            }
        } catch (Exception ignored) {}
    }

    private static File backupDir() {
        return new File(Environment.getExternalStorageDirectory(), BACKUP_DIR);
    }

    private static File backupFile() {
        return new File(backupDir(), BACKUP_FILE);
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
