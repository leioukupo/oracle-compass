package com.magneo.compass;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Controls the Android PowerUI low-battery sound without changing battery reporting. */
public final class SystemLowBatterySoundManager {
    private static final String TAG = "LowBatterySound";
    private static final String DEFAULT_SOUND = "/system/media/audio/ui/LowBattery.ogg";
    private static final Object LOCK = new Object();
    private static volatile Snapshot cached = Snapshot.unknown(false, "尚未读取系统声音状态");
    private static volatile long cachedAt;

    private SystemLowBatterySoundManager() {}

    public static void applySavedAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> setEnabledBlocking(app,
                Prefs.lowBatterySoundEnabled(app), false), "low-battery-sound");
        thread.setDaemon(true);
        thread.start();
    }

    public static Snapshot setEnabledBlocking(Context context, boolean enabled, boolean persistPref) {
        synchronized (LOCK) {
            String current = readSoundPath();
            if (!enabled && isUsablePath(current)) {
                Prefs.put(context, Prefs.K_LOW_BATTERY_SOUND_PATH, current);
            }
            String target = enabled ? restorePath(context) : "";
            CommandResult result = root("settings put global low_battery_sound " + shellQuote(target));
            String actual = readSoundPath();
            boolean actualEnabled = isUsablePath(actual);
            boolean ok = result.ok && actualEnabled == enabled;
            String detail = ok
                    ? (enabled ? "已开启 · " + actual : "已关闭")
                    : compact(result.output + " 当前值=" + actual);
            Snapshot snapshot = new Snapshot(ok, enabled, actualEnabled, actual, detail);
            cached = snapshot;
            cachedAt = System.currentTimeMillis();
            if (ok && persistPref) Prefs.putB(context, Prefs.K_LOW_BATTERY_SOUND, enabled);
            Log.i(TAG, "requested=" + enabled + " actual=" + actualEnabled
                    + " path=" + actual + " exit=" + result.code);
            return snapshot;
        }
    }

    public static Snapshot status(Context context) {
        boolean requested = Prefs.lowBatterySoundEnabled(context);
        if (System.currentTimeMillis() - cachedAt < 15000L
                && cached.requestedEnabled == requested) return cached;
        synchronized (LOCK) {
            if (System.currentTimeMillis() - cachedAt < 15000L
                    && cached.requestedEnabled == requested) return cached;
            String path = readSoundPath();
            boolean actual = isUsablePath(path);
            cached = new Snapshot(true, requested, actual, path,
                    actual ? "当前启用" : "当前关闭");
            cachedAt = System.currentTimeMillis();
            return cached;
        }
    }

    private static String restorePath(Context context) {
        String path = Prefs.get(context, Prefs.K_LOW_BATTERY_SOUND_PATH, "").trim();
        return isUsablePath(path) ? path : DEFAULT_SOUND;
    }

    private static String readSoundPath() {
        CommandResult result = root("settings get global low_battery_sound");
        return result.output == null ? "" : result.output.trim();
    }

    private static boolean isUsablePath(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\\"'\\\"'")) + "'";
    }

    public static final class Snapshot {
        public final boolean ok;
        public final boolean requestedEnabled;
        public final boolean actualEnabled;
        public final String path;
        public final String detail;

        Snapshot(boolean ok, boolean requestedEnabled, boolean actualEnabled,
                 String path, String detail) {
            this.ok = ok;
            this.requestedEnabled = requestedEnabled;
            this.actualEnabled = actualEnabled;
            this.path = path == null ? "" : path;
            this.detail = detail == null ? "" : detail;
        }

        static Snapshot unknown(boolean requested, String detail) {
            return new Snapshot(false, requested, false, "", detail);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("requestedEnabled", requestedEnabled);
                o.put("actualEnabled", actualEnabled);
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
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (text.length() > 320) text = text.substring(0, 320);
        return text.isEmpty() ? "无法写入系统低电量声音设置" : text;
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
