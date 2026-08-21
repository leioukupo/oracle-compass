package com.magneo.compass.frp;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** frpc 内网穿透客户端：assets 内置 32 位 ARM 二进制，网页配置 frpc.toml 启停。 */
public class FrpcManager {
    private static final String TAG = "Frpc";
    private static final String ROOT_DIR = "/data/local/oracle-frp";
    private static final String ROOT_BIN = ROOT_DIR + "/frpc";
    private static final String ROOT_CONF = ROOT_DIR + "/frpc.toml";
    private static final String ROOT_LOG = ROOT_DIR + "/frpc.log";
    private static final String ROOT_PID = ROOT_DIR + "/frpc.pid";
    private static volatile Process proc;
    private static volatile File appLogFile;
    private static final Object LOCK = new Object();

    public static final class Snapshot {
        public final boolean appRunning;
        public final boolean rootRunning;
        public final String status;
        public final String detail;

        Snapshot(boolean appRunning, boolean rootRunning, String status, String detail) {
            this.appRunning = appRunning;
            this.rootRunning = rootRunning;
            this.status = status;
            this.detail = detail;
        }
    }

    private FrpcManager() {}

    private static boolean alive(Process p) {
        try { p.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            Snapshot s = snapshotLocked();
            return s.appRunning || s.rootRunning;
        }
    }

    public static String status() { return snapshot().status; }

    public static String detail() { return snapshot().detail; }

    public static Snapshot snapshot() {
        synchronized (LOCK) { return snapshotLocked(); }
    }

    /** 启动 frpc；返回状态文本。 */
    public static String start(Context ctx) {
        synchronized (LOCK) {
            try {
                stopLocked();
                File dir = prepareAppFiles(ctx);
                File bin = new File(dir, "frpc");
                String cfg = Prefs.get(ctx, Prefs.K_FRPC_CONFIG, "");
                if (cfg.trim().isEmpty()) return "配置为空：请先在网页填写 frpc.toml";
                File conf = new File(dir, "frpc.toml");
                try (FileOutputStream fo = new FileOutputStream(conf)) {
                    fo.write(cfg.getBytes(StandardCharsets.UTF_8));
                }

                String root = startRootDaemon(bin, conf);
                if (root != null) return root;

                appLogFile = new File(dir, "frpc.log");
                if (appLogFile.exists()) appLogFile.delete();
                ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-c", conf.getAbsolutePath());
                pb.redirectErrorStream(true);
                proc = pb.start();
                final Process p = proc;
                Thread t = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                         FileOutputStream fo = new FileOutputStream(appLogFile, true)) {
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
                return "已启动（App 内兜底模式，更新 APK 时仍会掉线）";
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
        stopRootDaemon();
        if (proc != null) {
            Process p = proc;
            proc = null;
            try { p.destroy(); } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (alive(p)) {
                // SIGTERM 未退出时强制 kill
                try { new ProcessBuilder("kill", "-9", String.valueOf(getPid(p))).start(); } catch (Exception ignored) {}
            }
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
            String rootLog = rootLogTail(maxChars);
            if (rootLog != null && !rootLog.trim().isEmpty()) return rootLog;
            if (appLogFile == null || !appLogFile.exists()) return "(暂无日志)";
            try (RandomAccessFile raf = new RandomAccessFile(appLogFile, "r")) {
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

    private static File prepareAppFiles(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "frp");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 frp 目录");
        File bin = new File(dir, "frpc");
        if (!bin.exists() || bin.length() == 0) {
            try (InputStream in = ctx.getAssets().open("frpc");
                 FileOutputStream fo = new FileOutputStream(bin)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        }
        if (!bin.setExecutable(true, true)) throw new IOException("无法设置 frpc 可执行权限");
        return dir;
    }

    private static String startRootDaemon(File appBin, File appConf) {
        try {
            String install = "mkdir -p " + q(ROOT_DIR)
                    + " && cat " + q(appBin.getAbsolutePath()) + " > " + q(ROOT_BIN)
                    + " && chmod 755 " + q(ROOT_BIN)
                    + " && cat " + q(appConf.getAbsolutePath()) + " > " + q(ROOT_CONF)
                    + " && chmod 600 " + q(ROOT_CONF)
                    + " && : > " + q(ROOT_LOG)
                    + " && chmod 666 " + q(ROOT_LOG);
            String installOut = runSu(install);
            if (installOut.startsWith("!")) {
                Log.w(TAG, "root install failed: " + installOut);
                return null;
            }

            stopRootDaemon();
            String start = "cd " + q(ROOT_DIR) + " && "
                    + "if command -v nohup >/dev/null 2>&1; then "
                    + "nohup " + q(ROOT_BIN) + " -c " + q(ROOT_CONF)
                    + " >> " + q(ROOT_LOG) + " 2>&1 & echo $! > " + q(ROOT_PID) + "; "
                    + "else " + q(ROOT_BIN) + " -c " + q(ROOT_CONF)
                    + " >> " + q(ROOT_LOG) + " 2>&1 & echo $! > " + q(ROOT_PID) + "; fi";
            String startOut = runSu(start);
            if (startOut.startsWith("!")) {
                Log.w(TAG, "root start failed: " + startOut);
                return null;
            }
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            if (rootRunning()) return "已启动（root 独立守护，更新 APK 不会跟随 App 掉线）";
            String log = rootLogTail(1000);
            return "root frpc 启动后立即退出：\n" + (log == null ? "(暂无日志)" : log);
        } catch (Exception e) {
            Log.w(TAG, "root daemon failed", e);
            return null;
        }
    }

    private static boolean rootRunning() {
        String out = runSu(rootStatusCmd(false));
        return out != null && out.trim().equals("running");
    }

    private static Snapshot snapshotLocked() {
        boolean app = proc != null && alive(proc);
        boolean root = rootRunning(true);
        String status = (app || root) ? "running" : "stopped";
        String detail;
        if (app && root) detail = "root 守护 + App 兜底";
        else if (root) detail = "root 守护";
        else if (app) detail = "App 兜底";
        else detail = "未运行";
        return new Snapshot(app, root, status, detail);
    }

    private static String rootStatusCmd(boolean cleanStalePid) {
        StringBuilder sb = new StringBuilder();
        sb.append("PID=$(cat ").append(q(ROOT_PID)).append(" 2>/dev/null); ");
        sb.append("if [ -n \"$PID\" ] && [ -d /proc/$PID ]; then ");
        sb.append("C=$(tr '\\000' ' ' < /proc/$PID/cmdline 2>/dev/null); ");
        sb.append("E=$(readlink /proc/$PID/exe 2>/dev/null); ");
        sb.append("case \"$C $E\" in *").append(ROOT_BIN).append("*|*").append(ROOT_CONF)
                .append("*|*frpc*) echo running; exit 0;; esac; fi; ");
        sb.append("for P in /proc/[0-9]*; do ");
        sb.append("C=$(tr '\\000' ' ' < \"$P/cmdline\" 2>/dev/null); ");
        sb.append("E=$(readlink \"$P/exe\" 2>/dev/null); ");
        sb.append("case \"$C $E\" in *").append(ROOT_BIN).append("*|*").append(ROOT_CONF)
                .append("*|*frpc*) echo running; exit 0;; esac; ");
        sb.append("done; ");
        if (cleanStalePid) {
            sb.append("rm -f ").append(q(ROOT_PID)).append("; ");
        }
        sb.append("echo stopped");
        return sb.toString();
    }

    private static boolean rootRunning(boolean cleanStalePid) {
        String out = runSu(rootStatusCmd(cleanStalePid));
        return out != null && out.trim().equals("running");
    }

    private static void stopRootDaemon() {
        String cmd = "PID=$(cat " + q(ROOT_PID) + " 2>/dev/null); "
                + "if [ -n \"$PID\" ] && [ -d /proc/$PID ]; then "
                + "kill $PID 2>/dev/null; sleep 1; kill -9 $PID 2>/dev/null; fi; "
                + "for P in /proc/[0-9]*; do "
                + "PID=${P##*/}; C=$(tr '\\000' ' ' < \"$P/cmdline\" 2>/dev/null); "
                + "case \"$C\" in *" + ROOT_BIN + "*) kill $PID 2>/dev/null;; esac; "
                + "done; rm -f " + q(ROOT_PID);
        runSu(cmd);
    }

    private static String rootLogTail(int maxChars) {
        String cmd = "if [ -f " + q(ROOT_LOG) + " ]; then "
                + "(tail -c " + Math.max(200, maxChars * 2) + " " + q(ROOT_LOG)
                + " 2>/dev/null || cat " + q(ROOT_LOG) + " 2>/dev/null); "
                + "fi";
        String out = runSu(cmd);
        if (out == null || out.startsWith("!")) return null;
        int idx = out.indexOf('\n');
        if (idx > 0) out = out.substring(idx + 1);
        return out.trim().isEmpty() ? null : out.trim();
    }

    private static String runSu(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() < 8192) sb.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            String out = sb.toString().trim();
            if (code != 0) return "!" + (out.isEmpty() ? ("exit " + code) : out);
            return out;
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    private static String q(String s) {
        return "'" + String.valueOf(s).replace("'", "'\"'\"'") + "'";
    }
}
