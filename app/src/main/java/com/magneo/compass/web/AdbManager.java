package com.magneo.compass.web;

import android.content.Context;
import android.os.SystemClock;

import com.magneo.compass.Prefs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Root-backed ADB-over-TCP control so the device does not depend on an external ADBWireless app. */
public class AdbManager {
    private static final int DEFAULT_PORT = 5555;
    private static final int FAILURE_THRESHOLD = 3;
    private static final int CLOSE_WAIT_THRESHOLD = 2;
    private static final long RECOVERY_COOLDOWN_MS = 180000L;
    private static final long CHECK_FRESH_MS = 90000L;
    private static final Object LOCK = new Object();
    private static final Object HEALTH_LOCK = new Object();
    private static final Object TUNNEL_SYNC_LOCK = new Object();
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
    private static volatile boolean lastProtocolHealthy;
    private static volatile String lastProtocolDetail = "not probed";
    private static volatile long lastProtocolAt;
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
            o.put("tunnelSynchronized", bootIdentity().equals(
                    Prefs.get(ctx, Prefs.K_ADB_TUNNEL_SYNC_BOOT, "")));
            o.put("systemBootComplete", isSystemBootComplete());
            o.put("protocolHealthy", lastProtocolHealthy);
            o.put("protocolDetail", lastProtocolDetail);
            o.put("protocolCheckedAt", lastProtocolAt > 0 ? lastProtocolAt : JSONObject.NULL);
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
            if (sockets.established > 0) {
                // This Android 5.1 adbd accepts the real host transport but does not answer a
                // second CNXN probe concurrently. Probing here creates CLOSE_WAIT entries and
                // can kill a perfectly healthy remote session.
                lastProtocolHealthy = true;
                lastProtocolDetail = "active transport";
                lastProtocolAt = System.currentTimeMillis();
            } else if (lastProtocolAt == 0L) {
                // A successful once-per-boot synchronization may have happened in a previous
                // app process. Trust that persisted result instead of opening another half-auth
                // transport against this old adbd implementation.
                lastProtocolHealthy = bootIdentity().equals(
                        Prefs.get(ctx, Prefs.K_ADB_TUNNEL_SYNC_BOOT, ""));
                lastProtocolDetail = lastProtocolHealthy
                        ? "startup synchronized" : "startup sync pending";
                lastProtocolAt = System.currentTimeMillis();
            }
            // ESTABLISHED sockets and a non-zero receive queue are normal while FRP carries
            // a high-latency ADB stream. Only CLOSE_WAIT or a failed protocol handshake is
            // actionable; the old queue heuristic caused a restart loop and offline clients.
            boolean stale = sockets.established == 0
                    && (sockets.closeWait >= CLOSE_WAIT_THRESHOLD || !lastProtocolHealthy);
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
                    + " stuck=" + sockets.stuck + " established=" + sockets.established
                    + " protocol=" + lastProtocolDetail;
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

    public static boolean isSystemBootComplete() {
        return "1".equals(prop("sys.boot_completed"))
                && "1".equals(prop("dev.bootcomplete"));
    }

    public static boolean isTunnelSynchronized(Context ctx) {
        return bootIdentity().equals(Prefs.get(ctx, Prefs.K_ADB_TUNNEL_SYNC_BOOT, ""));
    }

    /** Refreshes adbd once per physical boot before exposing it through the FRP tunnel. */
    public static String ensureTunnelSynchronized(Context ctx) throws Exception {
        if (!Prefs.getB(ctx, Prefs.K_ADB_TCP_AUTO, false)) {
            return "adb tcp auto disabled";
        }
        synchronized (TUNNEL_SYNC_LOCK) {
            String boot = bootIdentity();
            if (isTunnelSynchronized(ctx)) {
                return "startup tunnel already synchronized";
            }
            String result = startPort(port(ctx), "startup tunnel synchronization");
            Prefs.put(ctx, Prefs.K_ADB_TUNNEL_SYNC_BOOT, boot);
            append("startup tunnel synchronized boot=" + boot);
            return result;
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

    public static long lastRecoveryAt() {
        return lastRecoveryAt;
    }

    private static String startPort(int port, String reason) throws Exception {
        applyTcpHardening();
        String out = runRoot("setprop service.adb.tcp.port " + port + "; "
                + "setprop persist.service.adb.tcp.port " + port + "; "
                + "stop adbd; I=0; while [ \"$(getprop init.svc.adbd)\" != stopped ] "
                + "&& [ $I -lt 3 ]; do sleep 1; I=$((I+1)); done; "
                + "start adbd; I=0; while [ \"$(getprop init.svc.adbd)\" != running ] "
                + "&& [ $I -lt 5 ]; do sleep 1; I=$((I+1)); done; sleep 1; "
                + "echo service=$(getprop service.adb.tcp.port); "
                + "echo persist=$(getprop persist.service.adb.tcp.port)");
        String daemonState = prop("init.svc.adbd");
        boolean listening = isListening(port);
        ProtocolProbe protocol = listening ? probeProtocol(port) : ProtocolProbe.failed("not listening");
        TcpSocketStats sockets = tcpSocketStats(port);
        boolean healthy = "running".equals(daemonState) && listening && protocol.ok;
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
            lastProtocolHealthy = protocol.ok;
            lastProtocolDetail = protocol.detail;
            lastProtocolAt = now;
            consecutiveFailures = healthy ? 0 : 1;
            lastCheckDetail = "daemon=" + daemonState + " listening=" + listening
                    + " closeWait=" + sockets.closeWait
                    + " stuck=" + sockets.stuck + " established=" + sockets.established
                    + " protocol=" + protocol.detail;
        }
        if (!healthy) throw new IllegalStateException(
                "adbd restart did not restore protocol: " + protocol.detail);
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

    private static String bootIdentity() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/sys/kernel/random/boot_id"), "US-ASCII"))) {
            String value = reader.readLine();
            if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (Exception ignored) {}
        long bootEpochMinute = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000L;
        return "boot-" + bootEpochMinute;
    }

    private static ProtocolProbe probeProtocol(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1800);
            socket.setSoTimeout(2500);
            byte[] payload = new byte[]{'h', 'o', 's', 't', ':', ':', 0};
            int command = 0x4e584e43; // CNXN
            int checksum = 0;
            for (byte value : payload) checksum += value & 0xff;
            byte[] header = new byte[24];
            putLe32(header, 0, command);
            putLe32(header, 4, 0x01000000);
            putLe32(header, 8, 4096);
            putLe32(header, 12, payload.length);
            putLe32(header, 16, checksum);
            putLe32(header, 20, command ^ 0xffffffff);
            OutputStream output = socket.getOutputStream();
            output.write(header);
            output.write(payload);
            output.flush();
            byte[] response = new byte[24];
            InputStream input = socket.getInputStream();
            int read = 0;
            while (read < response.length) {
                int count = input.read(response, read, response.length - read);
                if (count < 0) break;
                read += count;
            }
            if (read != response.length) return ProtocolProbe.failed("short response=" + read);
            int reply = le32(response, 0);
            if (reply == 0x48545541) return ProtocolProbe.ok("AUTH");
            if (reply == 0x4e584e43) return ProtocolProbe.ok("CNXN");
            return ProtocolProbe.failed(String.format(Locale.US, "reply=%08x", reply));
        } catch (Exception e) {
            return ProtocolProbe.failed(e.getClass().getSimpleName());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static int le32(byte[] value, int offset) {
        return (value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24);
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

    private static final class ProtocolProbe {
        final boolean ok;
        final String detail;
        ProtocolProbe(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
        static ProtocolProbe ok(String detail) { return new ProtocolProbe(true, detail); }
        static ProtocolProbe failed(String detail) { return new ProtocolProbe(false, detail); }
    }
}
