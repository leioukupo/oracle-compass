package com.magneo.compass;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controls Android 5.1's real LockSettingsService value and the MTK compatibility props. */
public final class SystemLockscreenManager {
    private static final String TAG = "SystemLockscreen";
    private static final String STATE_DIR = "/data/adb/oracle-compass";
    private static final String ENABLED_MARKER = STATE_DIR + "/system-lockscreen-enabled";
    private static final Object LOCK = new Object();
    private static volatile Snapshot cached = Snapshot.unknown(false, "尚未读取系统锁屏状态");
    private static volatile long cachedAt;

    private SystemLockscreenManager() {}

    public static void applySavedAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> setEnabledBlocking(app,
                Prefs.systemLockscreenEnabled(app), false), "system-lockscreen");
        thread.setDaemon(true);
        thread.start();
    }

    public static Snapshot setEnabledBlocking(Context context, boolean enabled, boolean persistPref) {
        synchronized (LOCK) {
            KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (!enabled && manager != null && manager.isKeyguardSecure()) {
                Snapshot secure = new Snapshot(false, enabled, true, true,
                        "检测到 PIN、图案或密码，拒绝关闭安全锁屏");
                cached = secure;
                cachedAt = System.currentTimeMillis();
                return secure;
            }

            int disabled = enabled ? 0 : 1;
            String command = "service check lock_settings 2>/dev/null | grep -q 'found' || exit 8; "
                    + "service call lock_settings 1 s16 'lockscreen.disabled' i32 " + disabled
                    + " i32 0 >/dev/null 2>&1; "
                    + "GET=\"$(service call lock_settings 4 s16 'lockscreen.disabled' i32 0 i32 0 2>&1)\"; "
                    + "echo ORACLE_LOCKSETTINGS=\"$GET\"; "
                    + (disabled == 1
                    ? "echo \"$GET\" | grep -q '00000001' || exit 9; "
                    + "mkdir -p " + STATE_DIR + "; rm -f " + ENABLED_MARKER + "; "
                    + "resetprop -n curlockscreen 0; resetprop -n ro.lockscreen.disable.default true; "
                    : "echo \"$GET\" | grep -q '00000001' && exit 9; "
                    + "mkdir -p " + STATE_DIR + "; touch " + ENABLED_MARKER + "; "
                    + "resetprop -n curlockscreen 1; resetprop -n ro.lockscreen.disable.default false; ")
                    + "chmod 700 " + STATE_DIR + "; "
                    + (enabled ? "chmod 600 " + ENABLED_MARKER + "; " : "")
                    + "echo ORACLE_LOCK_DISABLED=" + disabled;
            CommandResult result = root(command);
            Snapshot snapshot;
            if (result.ok) {
                snapshot = new Snapshot(true, enabled, enabled, false,
                        enabled ? "系统滑动锁已启用" : "系统锁屏已关闭");
                if (persistPref) Prefs.putB(context, Prefs.K_SYSTEM_LOCKSCREEN_ENABLED, enabled);
            } else {
                snapshot = new Snapshot(false, enabled, !disabledFromParcel(result.output), false,
                        compact(result.output));
            }
            cached = snapshot;
            cachedAt = System.currentTimeMillis();
            Log.i(TAG, "requested=" + enabled + " actual=" + snapshot.actualEnabled
                    + " exit=" + result.code + " detail=" + snapshot.detail);
            MainActivity.applySystemLockscreenPrefToActive();
            return snapshot;
        }
    }

    public static Snapshot status(Context context) {
        boolean requested = Prefs.systemLockscreenEnabled(context);
        if (System.currentTimeMillis() - cachedAt < 15000L
                && cached.requestedEnabled == requested) return cached;
        synchronized (LOCK) {
            if (System.currentTimeMillis() - cachedAt < 15000L
                    && cached.requestedEnabled == requested) return cached;
            CommandResult result = root("service call lock_settings 4 s16 'lockscreen.disabled' "
                    + "i32 0 i32 0 2>&1");
            boolean disabled = disabledFromParcel(result.output);
            KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean secure = manager != null && manager.isKeyguardSecure();
            boolean ok = result.ok && result.output.contains("Parcel(");
            cached = new Snapshot(ok, requested, !disabled, secure,
                    ok ? (disabled ? "系统数据库：锁屏已关闭" : "系统数据库：锁屏已启用")
                            : compact(result.output));
            cachedAt = System.currentTimeMillis();
            return cached;
        }
    }

    private static boolean disabledFromParcel(String output) {
        Matcher matcher = Pattern.compile("Parcel\\([^)]*0000000([01])", Pattern.DOTALL)
                .matcher(output == null ? "" : output);
        return matcher.find() && "1".equals(matcher.group(1));
    }

    public static final class Snapshot {
        public final boolean ok;
        public final boolean requestedEnabled;
        public final boolean actualEnabled;
        public final boolean secureCredential;
        public final String detail;

        Snapshot(boolean ok, boolean requestedEnabled, boolean actualEnabled,
                 boolean secureCredential, String detail) {
            this.ok = ok;
            this.requestedEnabled = requestedEnabled;
            this.actualEnabled = actualEnabled;
            this.secureCredential = secureCredential;
            this.detail = detail == null ? "" : detail;
        }

        static Snapshot unknown(boolean requested, String detail) {
            return new Snapshot(false, requested, requested, false, detail);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("requestedEnabled", requestedEnabled);
                o.put("actualEnabled", actualEnabled);
                o.put("secureCredential", secureCredential);
                o.put("detail", detail);
            } catch (Exception ignored) {}
            return o;
        }
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
        return s.isEmpty() ? "LockSettingsService 没有返回状态" : s;
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
