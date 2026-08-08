package com.magneo.compass.frp;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** frpc 内网穿透客户端：assets 内置 32 位 ARM 二进制，网页配置 frpc.toml 启停。 */
public class FrpcManager {
    private static final String TAG = "Frpc";
    private static volatile Process proc;
    private static volatile File logFile;
    private static final Object LOCK = new Object();

    private FrpcManager() {}

    private static boolean alive(Process p) {
        try { p.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }

    public static boolean isRunning() {
        synchronized (LOCK) { return proc != null && alive(proc); }
    }

    public static String status() { return isRunning() ? "running" : "stopped"; }

    /** 启动 frpc；返回状态文本。 */
    public static String start(Context ctx) {
        synchronized (LOCK) {
            try {
                stopLocked();
                File dir = new File(ctx.getFilesDir(), "frp");
                if (!dir.exists() && !dir.mkdirs()) return "启动失败：无法创建 frp 目录";
                File bin = new File(dir, "frpc");
                if (!bin.exists() || bin.length() == 0) {
                    try (InputStream in = ctx.getAssets().open("frpc");
                         FileOutputStream fo = new FileOutputStream(bin)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    if (!bin.setExecutable(true, true)) return "启动失败：无法设置可执行权限";
                }
                String cfg = Prefs.get(ctx, Prefs.K_FRPC_CONFIG, "");
                if (cfg.trim().isEmpty()) return "配置为空：请先在网页填写 frpc.toml";
                File conf = new File(dir, "frpc.toml");
                try (FileOutputStream fo = new FileOutputStream(conf)) {
                    fo.write(cfg.getBytes(StandardCharsets.UTF_8));
                }
                logFile = new File(dir, "frpc.log");
                if (logFile.exists()) logFile.delete();
                ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-c", conf.getAbsolutePath());
                pb.redirectErrorStream(true);
                proc = pb.start();
                final Process p = proc;
                Thread t = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                         FileOutputStream fo = new FileOutputStream(logFile, true)) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                            fo.flush();
                        }
                    } catch (Exception ignored) {}
                }, "frpc-log");
                t.setDaemon(true);
                t.start();
                // 短暂观察：立即退出说明配置/环境有问题
                Thread.sleep(600);
                if (!alive(p)) {
                    String log = logTail(600);
                    proc = null;
                    return "frpc 启动后立即退出：\n" + log;
                }
                return "已启动";
            } catch (Exception e) {
                Log.w(TAG, "start failed", e);
                return "启动失败: " + e.getMessage();
            }
        }
    }

    /** 停止 frpc；返回状态文本。 */
    public static String stop() {
        synchronized (LOCK) { stopLocked(); return "已停止"; }
    }

    private static void stopLocked() {
        if (proc == null) return;
        Process p = proc;
        proc = null;
        try { p.destroy(); } catch (Exception ignored) {}
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        if (alive(p)) {
            // SIGTERM 未退出时强制 kill
            try { new ProcessBuilder("kill", "-9", String.valueOf(getPid(p))).start(); } catch (Exception ignored) {}
        }
    }

    private static int getPid(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) return Integer.parseInt((String) v);
        } catch (Exception ignored) {}
        return -1;
    }

    /** 读取日志尾部（约 maxChars 字符）。 */
    public static String logTail(int maxChars) {
        synchronized (LOCK) {
            if (logFile == null || !logFile.exists()) return "(暂无日志)";
            try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                long len = raf.length();
                long start = Math.max(0, len - maxChars * 2);
                raf.seek(start);
                byte[] b = new byte[(int) (len - start)];
                raf.readFully(b);
                String s = new String(b, StandardCharsets.UTF_8);
                int idx = s.indexOf('\n');
                if (idx > 0) s = s.substring(idx + 1);
                return s.trim().isEmpty() ? "(暂无日志)" : s;
            } catch (Exception e) {
                return "读取日志失败: " + e.getMessage();
            }
        }
    }
}
