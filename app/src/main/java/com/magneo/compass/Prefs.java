package com.magneo.compass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
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
    public static final String K_VISION_OVERLAY_STYLE = "visionOverlayStyle";
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
    public static final String K_LOC_SOURCE = "locSource";
    public static final String K_LOC_WIFI_URL = "locWifiUrl";
    public static final String K_LOC_IP_URL = "locIpUrl";
    public static final String K_MCP_ENABLED = "mcpEnabled";
    public static final String K_MCP_SERVERS = "mcpServers";
    public static final String K_MCP_MAX_TOOL_ROUNDS = "mcpMaxToolRounds";
    public static final String K_MCP_SLOW_HINT_ENABLED = "mcpSlowHintEnabled";
    public static final String K_MCP_SLOW_HINT_MS = "mcpSlowHintMs";
    public static final String K_MCP_SLOW_HINT_SCHEDULE_MS = "mcpSlowHintScheduleMs";
    public static final String K_MCP_SLOW_HINT_MAX_COUNT = "mcpSlowHintMaxCount";
    public static final String K_MCP_SLOW_HINT_PHRASES = "mcpSlowHintPhrases";
    public static final String K_DEBUG_MODE = "debugMode";
    public static final String K_DEBUG_MAX_KB = "debugMaxKb";
    public static final String DEFAULT_LOC_WIFI_URL = "";
    public static final String DEFAULT_LOC_IP_URL = "http://ip-api.com/json/?fields=status,lat,lon,query,city,regionName,country,isp";
    public static final String DEFAULT_SYS_PROMPT_VOICE = "你是真理罗盘助手，回答简洁，中文回复。";
    public static final String DEFAULT_SYS_PROMPT_VISION = "你是圆屏设备“真理罗盘”的视觉感知模块。看图后用中文简述你看到的环境（物体/人/光线/场景），若与上一帧相比有明显变化，指出变化；若没有变化就说“无明显变化”。一句话即可。";
    public static final String DEFAULT_REASONING_EFFORT = "auto";
    public static final boolean DEFAULT_VAD_ENABLED = true;
    public static final String VISION_FRAME_SOURCE_HAL = "hal";
    public static final String VISION_FRAME_SOURCE_RTSP = "rtsp";
    public static final String DEFAULT_VISION_FRAME_SOURCE = VISION_FRAME_SOURCE_HAL;
    public static final String VISION_OVERLAY_MECHANICAL = "mechanical";
    public static final String VISION_OVERLAY_PLAIN = "plain";
    public static final String DEFAULT_VISION_OVERLAY_STYLE = VISION_OVERLAY_MECHANICAL;
    public static final String LOC_SOURCE_OFF = "off";
    public static final String LOC_SOURCE_WIFI_IP = "wifi_ip";
    public static final String LOC_SOURCE_GPS_DIAG = "gps_diag";
    public static final String DEFAULT_LOC_SOURCE = LOC_SOURCE_WIFI_IP;
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
    public static final int DEFAULT_MCP_MAX_TOOL_ROUNDS = 3;
    public static final int DEFAULT_MCP_TOOL_TIMEOUT_MS = 12000;
    public static final boolean DEFAULT_MCP_SLOW_HINT_ENABLED = true;
    public static final int DEFAULT_MCP_SLOW_HINT_MS = 900;
    public static final String DEFAULT_MCP_SLOW_HINT_SCHEDULE_MS = "900,2600,3800,4700,5300";
    public static final int DEFAULT_MCP_SLOW_HINT_MAX_COUNT = 5;
    public static final String DEFAULT_MCP_SLOW_HINT_PHRASES =
            "我查一下。\n正在查询中。\n我看一下资料。\n稍等，我找一下。\n我检索一下。\n"
                    + "我确认一下。\n我翻一下资料。\n等我取一下结果。\n我看看最新信息。\n我连一下工具。\n"
                    + "我问一下知识库。\n我查查有没有更新。\n稍等，我整理一下。\n我核对一下。\n我找找相关资料。\n"
                    + "这个我查一下更稳。\n我正在看结果。\n我帮你搜一下。\n我拉一下数据。\n马上给你。";

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

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

    public static boolean mcpEnabled(Context c) {
        return getB(c, K_MCP_ENABLED, false);
    }

    public static int mcpMaxToolRounds(Context c) {
        return clampInt(getI(c, K_MCP_MAX_TOOL_ROUNDS, DEFAULT_MCP_MAX_TOOL_ROUNDS), 0, 6);
    }

    public static boolean mcpSlowHintEnabled(Context c) {
        return getB(c, K_MCP_SLOW_HINT_ENABLED, DEFAULT_MCP_SLOW_HINT_ENABLED);
    }

    public static int mcpSlowHintMs(Context c) {
        return clampInt(getI(c, K_MCP_SLOW_HINT_MS, DEFAULT_MCP_SLOW_HINT_MS), 0, 10000);
    }

    public static int mcpSlowHintMaxCount(Context c) {
        return clampInt(getI(c, K_MCP_SLOW_HINT_MAX_COUNT, DEFAULT_MCP_SLOW_HINT_MAX_COUNT), 0, 10);
    }

    public static int[] mcpSlowHintScheduleMs(Context c) {
        String s = get(c, K_MCP_SLOW_HINT_SCHEDULE_MS, DEFAULT_MCP_SLOW_HINT_SCHEDULE_MS);
        ArrayList<Integer> out = new ArrayList<>();
        if (s != null) {
            String[] parts = s.split("[,，\\s]+");
            for (String p : parts) {
                try {
                    int v = Integer.parseInt(p.trim());
                    if (v >= 0 && v <= 30000 && !out.contains(v)) out.add(v);
                } catch (Exception ignored) {}
            }
        }
        if (out.isEmpty()) out.add(DEFAULT_MCP_SLOW_HINT_MS);
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    public static ArrayList<String> mcpSlowHintPhrases(Context c) {
        String raw = get(c, K_MCP_SLOW_HINT_PHRASES, DEFAULT_MCP_SLOW_HINT_PHRASES);
        ArrayList<String> out = new ArrayList<>();
        if (raw != null) {
            String t = raw.trim();
            if (t.startsWith("[")) {
                try {
                    JSONArray arr = new JSONArray(t);
                    for (int i = 0; i < arr.length(); i++) addPhrase(out, arr.optString(i, ""));
                } catch (Exception ignored) {}
            }
            if (out.isEmpty()) {
                String[] parts = raw.split("\\r?\\n");
                for (String p : parts) addPhrase(out, p);
            }
        }
        if (out.isEmpty()) {
            String[] parts = DEFAULT_MCP_SLOW_HINT_PHRASES.split("\\r?\\n");
            for (String p : parts) addPhrase(out, p);
        }
        return out;
    }

    private static void addPhrase(ArrayList<String> out, String phrase) {
        String p = phrase == null ? "" : phrase.trim();
        if (p.isEmpty() || out.contains(p)) return;
        out.add(p);
    }

    public static String normalizeLocSource(String v) {
        if (v == null) return DEFAULT_LOC_SOURCE;
        String s = v.trim().toLowerCase();
        if (LOC_SOURCE_OFF.equals(s) || "关闭".equals(s) || "false".equals(s)) return LOC_SOURCE_OFF;
        if (LOC_SOURCE_GPS_DIAG.equals(s) || "gps".equals(s) || "gps_diag".equals(s)
                || "gps诊断".equals(s)) return LOC_SOURCE_GPS_DIAG;
        return LOC_SOURCE_WIFI_IP;
    }

    public static String locSource(Context c) {
        return normalizeLocSource(get(c, K_LOC_SOURCE, DEFAULT_LOC_SOURCE));
    }

    public static boolean locSourceOff(Context c) {
        return LOC_SOURCE_OFF.equals(locSource(c));
    }

    public static boolean locSourceWifiIp(Context c) {
        return LOC_SOURCE_WIFI_IP.equals(locSource(c));
    }

    public static boolean locSourceGpsDiag(Context c) {
        return LOC_SOURCE_GPS_DIAG.equals(locSource(c));
    }

    public static boolean locationDisplayEnabled(Context c) {
        return !locSourceOff(c);
    }

    public static String locSourceLabel(Context c) {
        String s = locSource(c);
        if (LOC_SOURCE_OFF.equals(s)) return "关闭";
        if (LOC_SOURCE_GPS_DIAG.equals(s)) return "GPS诊断";
        return "WiFi-IP";
    }

    public static String locWifiUrl(Context c) {
        String v = get(c, K_LOC_WIFI_URL, DEFAULT_LOC_WIFI_URL);
        if (v == null) return "";
        String s = v.trim();
        if (s.contains("location.services.mozilla.com")) return DEFAULT_LOC_WIFI_URL;
        return s;
    }

    public static String locIpUrl(Context c) {
        String v = get(c, K_LOC_IP_URL, DEFAULT_LOC_IP_URL);
        if (v == null || v.trim().isEmpty()) return DEFAULT_LOC_IP_URL;
        return v.trim();
    }

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

    public static String normalizeVisionOverlayStyle(String v) {
        return VISION_OVERLAY_PLAIN.equalsIgnoreCase(v)
                ? VISION_OVERLAY_PLAIN : VISION_OVERLAY_MECHANICAL;
    }

    public static String visionOverlayStyle(Context c) {
        return normalizeVisionOverlayStyle(get(c, K_VISION_OVERLAY_STYLE,
                DEFAULT_VISION_OVERLAY_STYLE));
    }

    public static boolean visionOverlayMechanical(Context c) {
        return VISION_OVERLAY_MECHANICAL.equals(visionOverlayStyle(c));
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
