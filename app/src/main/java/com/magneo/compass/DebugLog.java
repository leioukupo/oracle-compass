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
        JSONArray arr = new JSONArray();
        try {
            byte[] all = readBytes(file(c));
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
        try { return file(c).length(); } catch (Exception e) { return 0; }
    }

    public static void clear(Context c) {
        try {
            synchronized (DebugLog.class) {
                //noinspection ResultOfMethodCallIgnored
                file(c).delete();
            }
        } catch (Exception ignored) {}
    }

    private static void enforceLimit(Context c, File f) {
        int maxKb = Math.max(256, Math.min(20480,
                Prefs.getI(c, Prefs.K_DEBUG_MAX_KB, DEFAULT_MAX_KB)));
        long max = maxKb * 1024L;
        if (!f.exists() || f.length() <= max) return;
        long keep = Math.max(128L * 1024, max / 2);
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
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(sb.toString().getBytes("UTF-8")); } finally { out.close(); }
            if (!tmp.renameTo(f)) {
                FileOutputStream out2 = new FileOutputStream(f, false);
                try { out2.write(sb.toString().getBytes("UTF-8")); } finally { out2.close(); }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "enforce", e);
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        if (f == null || !f.exists() || f.length() == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        if (v.length() <= max) return v;
        return v.substring(0, Math.max(1, max - 1)) + "...";
    }
}
