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

/** Verbose chain diagnostics. Disabled by default because it may contain user text. */
public class DebugLog {
    private static final String TAG = "DebugLog";
    private static final String FILE = "debug-chain.log";
    private static final int DEFAULT_MAX_KB = 4096;
    private static final int HARD_MAX_KB = 4096;

    private DebugLog() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    public static void append(Context c, String stage, String text) {
        if (c == null || !Prefs.getB(c, Prefs.K_DEBUG_MODE, false)) return;
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.getDefault()).format(new Date()));
            o.put("stage", stage == null ? "debug" : stage);
            o.put("text", clip(text, 12000));
            byte[] line = (o.toString() + "\n").getBytes("UTF-8");
            File f = file(c);
            synchronized (DebugLog.class) {
                FileOutputStream out = new FileOutputStream(f, true);
                try { out.write(line); } finally { out.close(); }
                enforceLimit(c, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "append", e);
        }
    }

    public static JSONArray read(Context c) {
        return readTail(c, 600, 384 * 1024);
    }

    /** Bounded tail used by the Web debugger to protect the low-memory device. */
    public static JSONArray readTail(Context c, int maxEntries, int maxBytes) {
        JSONArray arr = new JSONArray();
        int entries = Math.max(1, Math.min(600, maxEntries));
        int bytes = Math.max(16 * 1024, Math.min(768 * 1024, maxBytes));
        File f = file(c);
        if (!f.exists() || f.length() == 0) return arr;
        try {
            long offset = Math.max(0L, f.length() - bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(bytes, f.length()));
            RandomAccessFile in = new RandomAccessFile(f, "r");
            try {
                in.seek(offset);
                if (offset > 0) in.readLine();
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
            synchronized (DebugLog.class) {
                //noinspection ResultOfMethodCallIgnored
                file(c).delete();
                File dir = file(c).getParentFile();
                new File(dir, FILE + ".1").delete();
                new File(dir, FILE + ".2").delete();
                new File(dir, FILE + ".tmp").delete();
            }
        } catch (Exception ignored) {}
    }

    private static void enforceLimit(Context c, File f) {
        int maxKb = maxKb(c);
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
            if (!f.renameTo(first)) trimTailInPlace(f, max);
            else if (first.exists() && first.length() > max) trimTailInPlace(first, max);
        } catch (Exception e) {
            Log.w(TAG, "enforce", e);
        }
    }

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
            byte[] buf = new byte[8192];
            FileInputStreamCompat.copy(tmp, out, buf);
            out.close();
            tmp.delete();
        }
    }

    private static long length(File dir, String name) {
        File f = new File(dir, name);
        return f.exists() ? f.length() : 0L;
    }

    public static int maxKb(Context c) {
        int configured = Prefs.getI(c, Prefs.K_DEBUG_MAX_KB, DEFAULT_MAX_KB);
        if (configured > HARD_MAX_KB) {
            Prefs.putI(c, Prefs.K_DEBUG_MAX_KB, HARD_MAX_KB);
            configured = HARD_MAX_KB;
        }
        return Math.max(256, Math.min(HARD_MAX_KB, configured));
    }

    private static final class FileInputStreamCompat {
        static void copy(File source, FileOutputStream out, byte[] buf) throws Exception {
            java.io.FileInputStream in = new java.io.FileInputStream(source);
            try {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { in.close(); }
        }
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        if (v.length() <= max) return v;
        return v.substring(0, Math.max(1, max - 1)) + "...";
    }
}
