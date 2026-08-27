package com.magneo.compass;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps Magisk's per-UID grant notifications aligned with the Web preference. */
public final class RootGrantNotificationManager {
    private static final String TAG = "RootGrantNotice";
    private static final String STATE_DIR = "/data/adb/oracle-compass";
    private static final String ENABLED_MARKER = STATE_DIR + "/root-grant-notifications-enabled";
    private static final String TRIGGER = "oracle_compass_silent_policies";
    private static final Object LOCK = new Object();
    private static volatile Snapshot cached = Snapshot.unknown(false, "尚未读取 Magisk 数据库");
    private static volatile long cachedAt;

    private RootGrantNotificationManager() {}

    public static void applySaved(Context context) {
        applyAsync(context);
    }

    public static void applyAsync(Context context) {
        final Context app = context.getApplicationContext();
        final boolean enabled = Prefs.rootGrantNotifications(app);
        Thread thread = new Thread(() -> applyBlocking(enabled), "root-grant-notice");
        thread.setDaemon(true);
        thread.start();
    }

    public static Snapshot applyBlocking(boolean enabled) {
        synchronized (LOCK) {
            String sql;
            if (enabled) {
                sql = "DROP TRIGGER IF EXISTS " + TRIGGER + ";"
                        + "UPDATE policies SET notification=1;";
            } else {
                sql = "UPDATE policies SET notification=0;"
                        + "DROP TRIGGER IF EXISTS " + TRIGGER + ";"
                        + "CREATE TRIGGER " + TRIGGER
                        + " AFTER INSERT ON policies WHEN NEW.notification!=0 BEGIN "
                        + "UPDATE policies SET notification=0 WHERE uid=NEW.uid; END;";
            }
            String marker = enabled
                    ? "mkdir -p " + STATE_DIR + "; touch " + ENABLED_MARKER + "; chmod 700 "
                    + STATE_DIR + "; chmod 600 " + ENABLED_MARKER
                    : "mkdir -p " + STATE_DIR + "; rm -f " + ENABLED_MARKER
                    + "; chmod 700 " + STATE_DIR;
            CommandResult result = root("magisk --sqlite \"" + sql + "\" && " + marker);
            Snapshot snapshot = readActualLocked(enabled, result.ok ? "" : result.output);
            cached = snapshot;
            cachedAt = System.currentTimeMillis();
            Log.i(TAG, "requested=" + enabled + " actual=" + snapshot.actualEnabled
                    + " trigger=" + snapshot.triggerInstalled + " exit=" + result.code);
            return snapshot;
        }
    }

    public static Snapshot status(Context context) {
        boolean requested = Prefs.rootGrantNotifications(context);
        if (System.currentTimeMillis() - cachedAt < 15000L
                && cached.requestedEnabled == requested) return cached;
        synchronized (LOCK) {
            if (System.currentTimeMillis() - cachedAt < 15000L
                    && cached.requestedEnabled == requested) return cached;
            cached = readActualLocked(requested, "");
            cachedAt = System.currentTimeMillis();
            return cached;
        }
    }

    private static Snapshot readActualLocked(boolean requested, String priorError) {
        String query = "SELECT 'ORACLE_TOTAL='||COUNT(*) FROM policies;"
                + "SELECT 'ORACLE_NOTIFY='||COUNT(*) FROM policies WHERE notification!=0;"
                + "SELECT 'ORACLE_TRIGGER='||COUNT(*) FROM sqlite_master WHERE type='trigger' "
                + "AND name='" + TRIGGER + "';";
        CommandResult result = root("magisk --sqlite \"" + query + "\"");
        int total = labeledInt(result.output, "ORACLE_TOTAL", -1);
        int notified = labeledInt(result.output, "ORACLE_NOTIFY", -1);
        int triggers = labeledInt(result.output, "ORACLE_TRIGGER", -1);
        boolean trigger = triggers > 0;
        boolean actual = notified > 0 && !trigger;
        boolean ok = result.ok && total >= 0 && notified >= 0 && triggers >= 0;
        String detail = ok ? ("授权 " + total + " 项，提示开启 " + notified + " 项，静默触发器 "
                + (trigger ? "已启用" : "未启用")) : compact(priorError + " " + result.output);
        return new Snapshot(ok, requested, actual, trigger, total, notified, detail);
    }

    public static final class Snapshot {
        public final boolean ok;
        public final boolean requestedEnabled;
        public final boolean actualEnabled;
        public final boolean triggerInstalled;
        public final int policyCount;
        public final int notifiedPolicyCount;
        public final String detail;

        Snapshot(boolean ok, boolean requestedEnabled, boolean actualEnabled,
                 boolean triggerInstalled, int policyCount, int notifiedPolicyCount,
                 String detail) {
            this.ok = ok;
            this.requestedEnabled = requestedEnabled;
            this.actualEnabled = actualEnabled;
            this.triggerInstalled = triggerInstalled;
            this.policyCount = policyCount;
            this.notifiedPolicyCount = notifiedPolicyCount;
            this.detail = detail == null ? "" : detail;
        }

        static Snapshot unknown(boolean requested, String detail) {
            return new Snapshot(false, requested, requested, false, -1, -1, detail);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("requestedEnabled", requestedEnabled);
                o.put("actualEnabled", actualEnabled);
                o.put("triggerInstalled", triggerInstalled);
                o.put("policyCount", policyCount);
                o.put("notifiedPolicyCount", notifiedPolicyCount);
                o.put("detail", detail);
            } catch (Exception ignored) {}
            return o;
        }
    }

    private static int labeledInt(String text, String label, int fallback) {
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "\\s*=\\s*(\\d+)")
                .matcher(text == null ? "" : text);
        if (!matcher.find()) return fallback;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (Exception ignored) { return fallback; }
    }

    private static CommandResult root(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            int code = process.waitFor();
            return new CommandResult(code == 0, code, output.toString());
        } catch (Exception e) {
            return new CommandResult(false, -1, e.toString());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String compact(String value) {
        String s = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > 320) s = s.substring(0, 320);
        return s.isEmpty() ? "无法读取 Magisk 数据库" : s;
    }

    private static final class CommandResult {
        final boolean ok;
        final int code;
        final String output;
        CommandResult(boolean ok, int code, String output) {
            this.ok = ok;
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }
}
