package com.magneo.compass.web;

import android.content.Context;

import com.magneo.compass.Prefs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Root-backed ADB-over-TCP control so the device does not depend on an external ADBWireless app. */
public class AdbManager {
    private static final int DEFAULT_PORT = 5555;
    private static final Object LOCK = new Object();
    private static String lastLog = "";

    private AdbManager() {}

    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            int port = port(ctx);
            String servicePort = prop("service.adb.tcp.port");
            String persistPort = prop("persist.service.adb.tcp.port");
            boolean listening = isListening(port);
            o.put("ok", true);
            o.put("autoStart", Prefs.getB(ctx, Prefs.K_ADB_TCP_AUTO, false));
            o.put("port", port);
            o.put("servicePort", servicePort);
            o.put("persistPort", persistPort);
            o.put("listening", listening);
            o.put("running", listening || String.valueOf(port).equals(servicePort));
            o.put("log", lastLog);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        return o;
    }

    public static JSONObject save(Context ctx, String body) {
        JSONObject o = new JSONObject();
        try {
            Map<String, String> f = form(body);
            int port = parsePort(f.get("port"));
            boolean auto = "true".equalsIgnoreCase(f.get("autoStart")) || "1".equals(f.get("autoStart"));
            Prefs.putI(ctx, Prefs.K_ADB_TCP_PORT, port);
            Prefs.putB(ctx, Prefs.K_ADB_TCP_AUTO, auto);
            append("saved: port=" + port + " autoStart=" + auto);
            o.put("ok", true);
            o.put("status", status(ctx));
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        return o;
    }

    public static JSONObject start(Context ctx, String body) {
        try {
            int p = port(ctx);
            if (body != null && !body.trim().isEmpty()) {
                Map<String, String> f = form(body);
                if (f.containsKey("port")) {
                    p = parsePort(f.get("port"));
                    Prefs.putI(ctx, Prefs.K_ADB_TCP_PORT, p);
                }
            }
            String msg = startPort(p);
            JSONObject o = status(ctx);
            o.put("ok", true);
            o.put("msg", msg);
            return o;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject stop(Context ctx) {
        try {
            String out = runRoot("setprop service.adb.tcp.port -1; "
                    + "setprop persist.service.adb.tcp.port -1; "
                    + "stop adbd; sleep 1; start adbd; sleep 1; "
                    + "echo service=$(getprop service.adb.tcp.port); "
                    + "echo persist=$(getprop persist.service.adb.tcp.port)");
            append("stop adb tcp\n" + out);
            JSONObject o = status(ctx);
            o.put("ok", true);
            o.put("msg", "ADB TCP 已关闭");
            return o;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static String ensureAutoStart(Context ctx) {
        try {
            if (!Prefs.getB(ctx, Prefs.K_ADB_TCP_AUTO, false)) return "adb tcp auto disabled";
            return startPort(port(ctx));
        } catch (Exception e) {
            append("auto start failed: " + e.getMessage());
            return "adb tcp auto failed: " + e.getMessage();
        }
    }

    private static String startPort(int port) throws Exception {
        String out = runRoot("setprop service.adb.tcp.port " + port + "; "
                + "setprop persist.service.adb.tcp.port " + port + "; "
                + "stop adbd; sleep 1; start adbd; sleep 1; "
                + "echo service=$(getprop service.adb.tcp.port); "
                + "echo persist=$(getprop persist.service.adb.tcp.port)");
        String msg = "ADB TCP 已启动 port=" + port;
        append(msg + "\n" + out);
        return msg;
    }

    private static int port(Context ctx) {
        return clampPort(Prefs.getI(ctx, Prefs.K_ADB_TCP_PORT, DEFAULT_PORT));
    }

    private static int parsePort(String s) {
        try {
            return clampPort(Integer.parseInt(s == null ? "" : s.trim()));
        } catch (Exception e) {
            throw new IllegalArgumentException("端口必须是 1024-65535");
        }
    }

    private static int clampPort(int p) {
        if (p < 1024 || p > 65535) throw new IllegalArgumentException("端口必须是 1024-65535");
        return p;
    }

    private static String prop(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            String out = readAll(p);
            p.waitFor();
            return out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String runRoot(String cmd) throws Exception {
        synchronized (LOCK) {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            String out = readAll(p);
            int code = p.waitFor();
            if (code != 0) throw new IllegalStateException("su exit=" + code + "\n" + out);
            return out.trim();
        }
    }

    private static String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean isListening(int port) {
        return isListeningFile("/proc/net/tcp", port) || isListeningFile("/proc/net/tcp6", port);
    }

    private static boolean isListeningFile(String path, int port) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] p = line.trim().split("\\s+");
                if (p.length < 4 || !"0A".equals(p[3])) continue;
                int idx = p[1].lastIndexOf(':');
                if (idx < 0) continue;
                int lp = Integer.parseInt(p[1].substring(idx + 1), 16);
                if (lp == port) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void append(String s) {
        synchronized (LOCK) {
            String now = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date());
            lastLog = now + " " + (s == null ? "" : s.trim()) + "\n" + lastLog;
            if (lastLog.length() > 6000) lastLog = lastLog.substring(0, 6000);
        }
    }

    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (body == null || body.isEmpty()) return m;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length < 2) continue;
            m.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
        }
        return m;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        putErr(o, msg);
        return o;
    }

    private static void putErr(JSONObject o, String msg) {
        try {
            o.put("ok", false);
            o.put("err", msg == null || msg.isEmpty() ? "操作失败" : msg);
            o.put("log", lastLog);
        } catch (Exception ignored) {}
    }
}
