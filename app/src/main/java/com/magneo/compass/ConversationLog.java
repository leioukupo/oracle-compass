package com.magneo.compass;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 对话记录：ASR 识别文本 + AI 回复，逐行 JSON 落盘；支持大小上限与定时清理。 */
public class ConversationLog {
    private static final String TAG = "ConversationLog";
    private static final String FILE = "conversations.log";
    private static final int DEFAULT_MAX_KB = 4096;   // 默认上限 4MB；轮转总量约 12MB
    private static final int HARD_MAX_KB = 4096;      // MT6580 上单文件硬上限 4MB
    private static final int DEFAULT_CLEAN_MIN = 60;  // 默认每 60 分钟检查一次

    private static volatile ScheduledExecutorService cleaner;
    private static volatile long lastCleanAt;

    private ConversationLog() {}

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    /** 追加一条记录；超限自动裁剪（保留最新内容）。 */
    public static void append(Context c, String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        final Context ac = c.getApplicationContext();
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            o.put("role", role);
            o.put("text", text);
            byte[] line = (o.toString() + "\n").getBytes("UTF-8");
            File f = file(ac);
            synchronized (ConversationLog.class) {
                FileOutputStream out = new FileOutputStream(f, true);
                try { out.write(line); } finally { out.close(); }
                enforceLimit(ac, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "append", e);
        }
    }

    /** 大小超限时轮转文件，避免把整个日志读入内存再重写。 */
    private static void enforceLimit(Context c, File f) {
        int maxKb = effectiveMaxKb(c);
        long max = maxKb * 1024L;
        if (!f.exists() || f.length() <= max) return;
        try {
            File first = new File(f.getParentFile(), FILE + ".1");
            File second = new File(f.getParentFile(), FILE + ".2");
            if (first.exists()) {
                if (second.exists()) second.delete();
                first.renameTo(second);
                if (second.exists() && second.length() > max) trimTailInPlace(second, max);
            }
            if (!f.renameTo(first)) {
                // 极少数文件系统不允许 rename 时只保留安全尾部，仍不读入整文件。
                trimTailInPlace(f, max);
            } else if (first.exists() && first.length() > max) {
                trimTailInPlace(first, max);
            }
        } catch (Exception e) {
            Log.w(TAG, "enforceLimit", e);
        }
    }

    private static int effectiveMaxKb(Context c) {
        return clamp(Prefs.getI(c, Prefs.K_CONV_MAX_KB, DEFAULT_MAX_KB), 100, HARD_MAX_KB);
    }

    /** 仅在 rename 不可用时使用固定大小窗口，窗口内仍不会构造整文件字符串。 */
    private static void trimTailInPlace(File f, long keep) throws Exception {
        long start = Math.max(0L, f.length() - keep);
        File tmp = new File(f.getParentFile(), FILE + ".tmp");
        RandomAccessFile in = new RandomAccessFile(f, "r");
        try {
            in.seek(start);
            if (start > 0) in.readLine();
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
        if (!tmp.renameTo(f)) {
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                FileInputStreamCompat.copy(tmp, out);
            } finally { out.close(); }
            tmp.delete();
        }
    }

    /** 读取有限的最新记录，避免历史调用者把大文件整体载入低内存设备。 */
    public static JSONArray read(Context c) {
        return readTail(c, 500, 256 * 1024);
    }

    /** Read a bounded tail for the Web console; never materialize a multi-MB log. */
    public static JSONArray readTail(Context c, int maxEntries, int maxBytes) {
        JSONArray arr = new JSONArray();
        int entries = Math.max(1, Math.min(500, maxEntries));
        int bytes = Math.max(8 * 1024, Math.min(512 * 1024, maxBytes));
        File f = file(c.getApplicationContext());
        if (!f.exists() || f.length() == 0) return arr;
        try {
            long offset = Math.max(0L, f.length() - bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(bytes, f.length()));
            RandomAccessFile in = new RandomAccessFile(f, "r");
            try {
                in.seek(offset);
                if (offset > 0) in.readLine(); // discard an incomplete JSON line
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < bytes) {
                    out.write(buf, 0, Math.min(n, bytes - out.size()));
                }
            } finally { in.close(); }
            String[] lines = new String(out.toByteArray(), "UTF-8").split("\\n", -1);
            int start = Math.max(0, lines.length - entries);
            for (int i = start; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                try { arr.put(new JSONObject(lines[i])); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "readTail", e);
        }
        return arr;
    }

    public static long size(Context c) {
        try {
            File dir = c.getApplicationContext().getFilesDir();
            return length(dir, FILE) + length(dir, FILE + ".1") + length(dir, FILE + ".2");
        } catch (Exception e) { return 0; }
    }

    public static void clear(Context c) {
        try {
            File f = file(c.getApplicationContext());
            synchronized (ConversationLog.class) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                new File(f.getParentFile(), FILE + ".1").delete();
                new File(f.getParentFile(), FILE + ".2").delete();
                new File(f.getParentFile(), FILE + ".tmp").delete();
            }
        } catch (Exception ignored) {}
    }

    /** 启动定时清理（幂等）：按设置间隔检查大小并裁剪；0=关闭定时清理。 */
    public static void startCleaner(Context c) {
        if (cleaner != null) return;
        final Context ac = c.getApplicationContext();
        migrateMaxSetting(ac);
        lastCleanAt = System.currentTimeMillis();
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conv-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(() -> {
            try {
                int min = Prefs.getI(ac, Prefs.K_CONV_CLEAN_MIN, DEFAULT_CLEAN_MIN);
                if (min <= 0) return;
                long now = System.currentTimeMillis();
                if (now - lastCleanAt < min * 60_000L) return;
                lastCleanAt = now;
                synchronized (ConversationLog.class) {
                    enforceLimit(ac, file(ac));
                }
            } catch (Throwable t) {
                Log.w(TAG, "cleaner", t);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static int maxKb(Context c) {
        return effectiveMaxKb(c);
    }

    private static void migrateMaxSetting(Context c) {
        int configured = Prefs.getI(c, Prefs.K_CONV_MAX_KB, DEFAULT_MAX_KB);
        if (configured > HARD_MAX_KB) Prefs.putI(c, Prefs.K_CONV_MAX_KB, HARD_MAX_KB);
    }

    private static long length(File dir, String name) {
        File f = new File(dir, name);
        return f.exists() ? f.length() : 0L;
    }

    /** Small streaming copy helper kept local for Android 5.1 compatibility. */
    private static final class FileInputStreamCompat {
        static void copy(File source, FileOutputStream out) throws Exception {
            java.io.FileInputStream in = new java.io.FileInputStream(source);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { in.close(); }
        }
    }
}
