package com.magneo.compass;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
    private static final int DEFAULT_MAX_KB = 1024;   // 默认上限 1MB
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

    /** 大小超限时丢弃旧记录，保留最新约一半容量。 */
    private static void enforceLimit(Context c, File f) {
        int maxKb = clamp(Prefs.getI(c, Prefs.K_CONV_MAX_KB, DEFAULT_MAX_KB), 100, 20480);
        long max = maxKb * 1024L;
        if (!f.exists() || f.length() <= max) return;
        long keep = Math.max(64L * 1024, max / 2);
        try {
            byte[] all = readBytes(f);
            String s = new String(all, "UTF-8");
            String[] lines = s.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1; i >= 0 && sb.length() < keep; i--) {
                String l = lines[i];
                if (l.trim().isEmpty()) continue;
                sb.insert(0, l + "\n");
            }
            byte[] b = sb.toString().getBytes("UTF-8");
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(b); } finally { out.close(); }
            if (!tmp.renameTo(f)) {
                FileOutputStream o2 = new FileOutputStream(f);
                try { o2.write(b); } finally { o2.close(); }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "enforceLimit", e);
        }
    }

    /** 读取全部记录，返回 JSONArray（旧→新）。 */
    public static JSONArray read(Context c) {
        JSONArray arr = new JSONArray();
        try {
            byte[] all = readBytes(file(c.getApplicationContext()));
            String s = new String(all, "UTF-8");
            for (String line : s.split("\n", -1)) {
                if (line.trim().isEmpty()) continue;
                try { arr.put(new JSONObject(line)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "read", e);
        }
        return arr;
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
        try { return file(c.getApplicationContext()).length(); } catch (Exception e) { return 0; }
    }

    public static void clear(Context c) {
        try {
            File f = file(c.getApplicationContext());
            synchronized (ConversationLog.class) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {}
    }

    /** 启动定时清理（幂等）：按设置间隔检查大小并裁剪；0=关闭定时清理。 */
    public static void startCleaner(Context c) {
        if (cleaner != null) return;
        final Context ac = c.getApplicationContext();
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

    private static byte[] readBytes(File f) throws Exception {
        if (!f.exists() || f.length() == 0) return new byte[0];
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) Math.min(f.length(), Integer.MAX_VALUE)];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return off == b.length ? b : java.util.Arrays.copyOf(b, off);
        } finally {
            in.close();
        }
    }
}
