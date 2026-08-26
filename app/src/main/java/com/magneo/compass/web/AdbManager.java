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
    private static final int FAILURE_THRESHOLD = 2;
    private static final int STALE_SOCKET_THRESHOLD = 2;
    private static final long RECOVERY_COOLDOWN_MS = 180000L;
    private static final long CHECK_FRESH_MS = 90000L;
    private static final Object LOCK = new Object();
    private static final Object HEALTH_LOCK = new Object();
    private static String lastLog = "";
    private static volatile boolean tuningApplied;
    private static volatile boolean lastCheckKnown;
    private static volatile boolean lastCheckHealthy;
    private static volatile int lastCheckPort = -1;
    private static volatile int lastCloseWaitCount;
    private static volatile int lastStuckSocketCount;
    private static volatile int consecutiveFailures;
    private static volatile long lastCheckAt;
    private static volatile long lastRecoveryAt;
    private static volatile long lastRestartAt;
    private static volatile String lastCheckDetail = "waiting for watchdog";

    private AdbManager() {}

    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            int port = port(ctx);
            String servicePort = prop("service.adb.tcp.port");
            String persistPort = prop("persist.service.adb.tcp.port");
            String daemonState = prop("init.svc.adbd");
            int activePort = firstValidPort(servicePort, persistPort);
            boolean configuredListening = isListening(port);
            boolean activeListening = activePort > 0 && isListening(activePort);
            boolean listening = configuredListening || activeListening;
            int checkedPort = activePort > 0 ? activePort : port;
            boolean checkFresh = lastCheckKnown && lastCheckPort == checkedPort
                    && System.currentTimeMillis() - lastCheckAt <= CHECK_FRESH_MS;
            String health = !listening ? "down"
                    : !checkFresh ? "checking"
                    : lastCheckHealthy ? "healthy" : "degraded";
            o.put("ok", true);
            o.put("autoStart", Prefs.getB(ctx, Prefs.K_ADB_TCP_AUTO, false));
            o.put("port", port);
            o.put("configuredPort", port);
            if (activePort > 0) o.put("activePort", activePort);
            else o.put("activePort", JSONObject.NULL);
            o.put("servicePort", servicePort);
            o.put("persistPort", persistPort);
            o.put("daemonState", daemonState);
            o.put("configuredListening", configuredListening);
            o.put("activeListening", activeListening);
            o.put("listening", listening);
            o.put("running", listening && "running".equals(daemonState));
            o.put("health", health);
            o.put("deviceHealthy", checkFresh ? lastCheckHealthy : JSONObject.NULL);
            o.put("hostState", "unknown");
            o.put("lastCheckAt", lastCheckAt > 0 ? lastCheckAt : JSONObject.NULL);
            o.put("lastCheckDetail", lastCheckDetail);
            o.put("consecutiveFailures", consecutiveFailures);
            o.put("closeWaitSockets", lastCloseWaitCount);
            o.put("stuckSockets", lastStuckSocketCount);
            o.put("lastRecoveryAt", lastRecoveryAt > 0 ? lastRecoveryAt : JSONObject.NULL);
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
            String msg = startPort(p, "manual restart");
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
            synchronized (HEALTH_LOCK) {
                lastCheckKnown = false;
                lastCheckHealthy = false;
                lastCheckPort = -1;
                consecutiveFailures = 0;
                lastCheckDetail = "ADB TCP disabled";
            }
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
            if (!Prefs.getB(ctx, Prefs.K_ADB_TCP_AUTO, false)) {
                synchronized (HEALTH_LOCK) {
                    lastCheckKnown = false;
                    consecutiveFailures = 0;
                    lastCheckDetail = "auto recovery disabled";
                }
                return "adb tcp auto disabled";
            }
            applyTcpHardening();
            int p = port(ctx);
            String servicePort = prop("service.adb.tcp.port");
            String persistPort = prop("persist.service.adb.tcp.port");
            String daemonState = prop("init.svc.adbd");
            int activePort = firstValidPort(servicePort, persistPort);
            int checkedPort = activePort > 0 ? activePort : p;
            boolean listening = isListening(checkedPort);
            if (!"running".equals(daemonState) || !listening) {
                return recoverIfAllowed(p, "daemon=" + daemonState + " listening=" + listening, true);
            }

            TcpSocketStats sockets = tcpSocketStats(checkedPort);
            boolean stale = sockets.closeWait >= 3
                    || sockets.stuck >= STALE_SOCKET_THRESHOLD
                    || sockets.established + sockets.closeWait >= 16;
            boolean healthy = !stale;
            int failures;
            synchronized (HEALTH_LOCK) {
                lastCheckKnown = true;
                lastCheckHealthy = healthy;
                lastCheckPort = checkedPort;
                lastCheckAt = System.currentTimeMillis();
                lastCloseWaitCount = sockets.closeWait;
                lastStuckSocketCount = sockets.stuck;
                lastCheckDetail = "listener ok closeWait=" + sockets.closeWait
                        + " stuck=" + sockets.stuck + " established=" + sockets.established;
                consecutiveFailures = healthy ? 0 : consecutiveFailures + 1;
                failures = consecutiveFailures;
            }
            if (healthy) {
                return "adb tcp device side healthy port=" + checkedPort;
            }
            String reason = "stale sockets closeWait=" + sockets.closeWait
                    + " stuck=" + sockets.stuck + " established=" + sockets.established;
            if (failures < FAILURE_THRESHOLD) {
                append("watchdog warning " + failures + "/" + FAILURE_THRESHOLD + ": " + reason);
                return "adb tcp degraded, confirming: " + reason;
            }
            return recoverIfAllowed(p, reason, false);
        } catch (Exception e) {
            append("auto start failed: " + e.getMessage());
            return "adb tcp auto failed: " + e.getMessage();
        }
    }

    private static String recoverIfAllowed(int port, String reason, boolean serviceDown) throws Exception {
        long now = System.currentTimeMillis();
        long sinceRestart = now - lastRestartAt;
        long cooldown = serviceDown ? 30000L : RECOVERY_COOLDOWN_MS;
        if (lastRestartAt > 0 && sinceRestart < cooldown) {
            String msg = "recovery cooldown " + ((cooldown - sinceRestart + 999L) / 1000L)
                    + "s: " + reason;
            append(msg);
            return msg;
        }
        return startPort(port, "watchdog: " + reason);
    }

    private static String startPort(int port, String reason) throws Exception {
        applyTcpHardening();
        String out = runRoot("setprop service.adb.tcp.port " + port + "; "
                + "setprop persist.service.adb.tcp.port " + port + "; "
                + "stop adbd; sleep 1; start adbd; sleep 2; "
                + "echo service=$(getprop service.adb.tcp.port); "
                + "echo persist=$(getprop persist.service.adb.tcp.port)");
        String daemonState = prop("init.svc.adbd");
        boolean listening = isListening(port);
        TcpSocketStats sockets = tcpSocketStats(port);
        boolean healthy = "running".equals(daemonState) && listening;
        long now = System.currentTimeMillis();
        synchronized (HEALTH_LOCK) {
            lastRestartAt = now;
            lastRecoveryAt = now;
            lastCheckKnown = true;
            lastCheckHealthy = healthy;
            lastCheckPort = port;
            lastCheckAt = now;
            lastCloseWaitCount = sockets.closeWait;
            lastStuckSocketCount = sockets.stuck;
            consecutiveFailures = healthy ? 0 : 1;
            lastCheckDetail = "daemon=" + daemonState + " listening=" + listening
                    + " closeWait=" + sockets.closeWait
                    + " stuck=" + sockets.stuck + " established=" + sockets.established;
        }
        if (!healthy) throw new IllegalStateException("adbd restart did not restore listener");
        String msg = "ADB TCP 已恢复 port=" + port;
        append(msg + " reason=" + reason + "\n" + out);
        return msg;
    }

    private static void applyTcpHardening() throws Exception {
        if (tuningApplied) return;
        String out = runRoot("[ ! -w /proc/sys/net/ipv4/tcp_keepalive_time ] || echo 60 > /proc/sys/net/ipv4/tcp_keepalive_time; "
                + "[ ! -w /proc/sys/net/ipv4/tcp_keepalive_intvl ] || echo 10 > /proc/sys/net/ipv4/tcp_keepalive_intvl; "
                + "[ ! -w /proc/sys/net/ipv4/tcp_keepalive_probes ] || echo 3 > /proc/sys/net/ipv4/tcp_keepalive_probes; "
                + "[ ! -w /proc/sys/net/ipv4/tcp_fin_timeout ] || echo 30 > /proc/sys/net/ipv4/tcp_fin_timeout; "
                + "echo keepalive=$(cat /proc/sys/net/ipv4/tcp_keepalive_time 2>/dev/null)/$(cat /proc/sys/net/ipv4/tcp_keepalive_intvl 2>/dev/null)/$(cat /proc/sys/net/ipv4/tcp_keepalive_probes 2>/dev/null) "
                + "fin=$(cat /proc/sys/net/ipv4/tcp_fin_timeout 2>/dev/null)");
        tuningApplied = true;
        append("tcp tuning applied: " + out);
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

    private static int firstValidPort(String... values) {
        for (String s : values) {
            int p = maybePort(s);
            if (p > 0) return p;
        }
        return -1;
    }

    private static int maybePort(String s) {
        try {
            return clampPort(Integer.parseInt(s == null ? "" : s.trim()));
        } catch (Exception e) {
            return -1;
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

    private static TcpSocketStats tcpSocketStats(int port) {
        TcpSocketStats out = new TcpSocketStats();
        readTcpSocketStats("/proc/net/tcp", port, out);
        readTcpSocketStats("/proc/net/tcp6", port, out);
        return out;
    }

    private static void readTcpSocketStats(String path, int port, TcpSocketStats out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) continue;
                int idx = p[1].lastIndexOf(':');
                if (idx < 0 || Integer.parseInt(p[1].substring(idx + 1), 16) != port) continue;
                String state = p[3];
                long rxQueue = 0;
                String[] queues = p[4].split(":", 2);
                if (queues.length == 2) rxQueue = Long.parseLong(queues[1], 16);
                if ("01".equals(state)) out.established++;
                if ("08".equals(state)) out.closeWait++;
                if (("01".equals(state) || "08".equals(state)) && rxQueue > 0) out.stuck++;
            }
        } catch (Exception ignored) {}
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

    private static final class TcpSocketStats {
        int established;
        int closeWait;
        int stuck;
    }
}
