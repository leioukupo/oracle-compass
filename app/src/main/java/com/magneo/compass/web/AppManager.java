package com.magneo.compass.web;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Base64;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Root-backed APK upload, install, uninstall and package listing for the web UI. */
public class AppManager {
    private static final String SELF_COMPONENT = "com.magneo.compass/.MainActivity";
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final Object AUTH_LOCK = new Object();
    private static final Object TASK_LOCK = new Object();
    private static volatile String sessionToken = "";
    private static Task task = Task.idle();

    private AppManager() {}

    public static boolean hasPassword(Context ctx) {
        return !Prefs.get(ctx, Prefs.K_WEB_ADMIN_SALT, "").isEmpty()
                && !Prefs.get(ctx, Prefs.K_WEB_ADMIN_HASH, "").isEmpty();
    }

    public static boolean authorized(Context ctx, String token) {
        if (!hasPassword(ctx) || token == null || token.isEmpty()) return false;
        String cur = sessionToken;
        return !cur.isEmpty() && constEq(cur, token);
    }

    public static JSONObject state(Context ctx, String token) {
        JSONObject o = new JSONObject();
        try {
            boolean has = hasPassword(ctx);
            boolean authed = authorized(ctx, token);
            o.put("ok", true);
            o.put("hasPassword", has);
            o.put("authed", authed);
            if (authed) {
                o.put("upload", uploadJson(ctx));
                o.put("task", taskJson());
            }
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        return o;
    }

    public static JSONObject login(Context ctx, String body) {
        JSONObject o = new JSONObject();
        try {
            Map<String, String> f = form(body);
            String password = f.get("password");
            if (!hasPassword(ctx)) return err("请先设置管理密码");
            if (!checkPassword(ctx, password)) return err("密码不正确");
            String token = newToken();
            synchronized (AUTH_LOCK) { sessionToken = token; }
            o.put("ok", true);
            o.put("token", token);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        return o;
    }

    public static JSONObject setup(Context ctx, String body) {
        JSONObject o = new JSONObject();
        try {
            Map<String, String> f = form(body);
            String oldPassword = trim(f.get("oldPassword"));
            String password = trim(f.get("password"));
            if (password.length() < 4) return err("管理密码至少 4 位");
            if (hasPassword(ctx) && !checkPassword(ctx, oldPassword)) {
                return err("旧密码不正确");
            }
            byte[] saltBytes = new byte[18];
            new SecureRandom().nextBytes(saltBytes);
            String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
            Prefs.put(ctx, Prefs.K_WEB_ADMIN_SALT, salt);
            Prefs.put(ctx, Prefs.K_WEB_ADMIN_HASH, hash(salt, password));
            String token = newToken();
            synchronized (AUTH_LOCK) { sessionToken = token; }
            o.put("ok", true);
            o.put("token", token);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        return o;
    }

    public static JSONObject apps(Context ctx) {
        JSONObject root = new JSONObject();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<JSONObject> rows = new ArrayList<>();
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || pi.packageName == null) continue;
                JSONObject o = new JSONObject();
                String label;
                try { label = String.valueOf(pm.getApplicationLabel(ai)); }
                catch (Exception e) { label = pi.packageName; }
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                o.put("label", label);
                o.put("packageName", pi.packageName);
                o.put("versionName", pi.versionName == null ? "" : pi.versionName);
                o.put("versionCode", pi.versionCode);
                o.put("system", system);
                o.put("enabled", ai.enabled);
                o.put("self", pi.packageName.equals(ctx.getPackageName()));
                rows.add(o);
            }
            java.util.Collections.sort(rows, (a, b) -> {
                String al = a.optString("label", "").toLowerCase(Locale.US);
                String bl = b.optString("label", "").toLowerCase(Locale.US);
                int c = al.compareTo(bl);
                if (c != 0) return c;
                return a.optString("packageName", "").compareTo(b.optString("packageName", ""));
            });
            JSONArray arr = new JSONArray();
            for (JSONObject row : rows) arr.put(row);
            root.put("ok", true);
            root.put("apps", arr);
        } catch (Exception e) {
            putErr(root, e.getMessage());
        }
        return root;
    }

    public static JSONObject upload(Context ctx, InputStream in, long length, String encodedName) {
        File tmp = null;
        try {
            if (length <= 0) return err("上传内容为空");
            if (length > MAX_APK_BYTES) return err("APK 超过 512MB 上限");
            File dir = uploadDir(ctx);
            tmp = new File(dir, "latest.apk.uploading");
            if (tmp.exists()) tmp.delete();
            long copied = 0;
            byte[] buf = new byte[64 * 1024];
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                while (copied < length) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, length - copied));
                    if (n < 0) break;
                    fo.write(buf, 0, n);
                    copied += n;
                }
                fo.flush();
            }
            if (copied != length) {
                tmp.delete();
                return err("上传中断：" + copied + "/" + length + " bytes");
            }
            return finalizeApk(ctx, tmp, decodeHeader(encodedName));
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            return err(e.getMessage());
        }
    }

    public static JSONObject fetch(Context ctx, String body) {
        File tmp = null;
        try {
            Map<String, String> f = form(body);
            String url = trim(f.get("url"));
            if (url.isEmpty()) return err("请填写下载地址");
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            File dir = uploadDir(ctx);
            tmp = new File(dir, "latest.apk.downloading");
            if (tmp.exists()) tmp.delete();
            long copied = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fo = new FileOutputStream(tmp)) {
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    copied += n;
                    if (copied > MAX_APK_BYTES) {
                        tmp.delete();
                        return err("APK 超过 512MB 上限");
                    }
                    fo.write(buf, 0, n);
                }
                fo.flush();
            }
            if (copied <= 0) {
                tmp.delete();
                return err("下载内容为空");
            }
            JSONObject o = finalizeApk(ctx, tmp, url);
            if (o.optBoolean("ok", false)) o.put("sourceUrl", url);
            return o;
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            return err(e.getMessage());
        }
    }

    public static JSONObject install(Context ctx) {
        try {
            File apk = latestApk(ctx);
            if (!apk.exists() || apk.length() == 0) return err("还没有上传 APK");
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null || pi.packageName == null) return err("APK 解析失败，不能安装");
            boolean self = ctx.getPackageName().equals(pi.packageName);
            String rootApk = rootReadablePath(apk);
            String stage = "/data/local/tmp/oracle-compass-install.apk";
            String cmd = "mkdir -p /data/local/tmp; "
                    + "rm -f " + q(stage) + "; "
                    + "cat " + q(rootApk) + " > " + q(stage) + "; R=$?; "
                    + "if [ $R -ne 0 ]; then echo copy failed; exit $R; fi; "
                    + "chmod 644 " + q(stage) + "; "
                    + "pm install -r -d " + q(stage) + " 2>&1; R=$?; "
                    + "if [ $R -ne 0 ]; then echo retry without -d; pm install -r " + q(stage) + " 2>&1; R=$?; fi; "
                    + (self ? "if [ $R -eq 0 ]; then sleep 2; am start -n " + SELF_COMPONENT + " >/dev/null 2>&1; fi; " : "")
                    + "exit $R";
            return startTask(ctx, "install", pi.packageName, cmd);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject installBootModule(Context ctx) {
        try {
            File apk = latestApk(ctx);
            if (!apk.exists() || apk.length() == 0) return err("还没有上传 APK");
            String rootApk = rootReadablePath(apk);
            String stage = "/data/local/tmp/oracle-compass-bootanimation.zip";
            String busybox = "/sbin/.magisk/busybox/busybox";
            String asset = "assets/oracle-compass-bootanimation.zip";
            String cmd = "test -x " + q(busybox) + " || { echo busybox missing; exit 2; }; "
                    + q(busybox) + " unzip -p " + q(rootApk) + " " + q(asset)
                    + " > " + q(stage) + "; R=$?; "
                    + "if [ $R -ne 0 ] || [ ! -s " + q(stage)
                    + " ]; then echo boot module asset missing; exit 3; fi; "
                    + "chmod 600 " + q(stage) + "; "
                    + "magisk --install-module " + q(stage) + " 2>&1";
            return startTask(ctx, "boot-module", "oracle_compass_bootanimation", cmd);
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject reboot(Context ctx, String body) {
        try {
            Map<String, String> fields = form(body);
            if (!"REBOOT DEVICE".equals(fields.get("confirm"))) {
                return err("确认短语必须是 REBOOT DEVICE");
            }
            return startTask(ctx, "reboot", "device", "sync; sleep 2; reboot");
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    public static JSONObject uninstall(Context ctx, String body) {
        try {
            Map<String, String> f = form(body);
            String pkg = trim(f.get("packageName"));
            if (!validPackage(pkg)) return err("包名不合法");
            if (ctx.getPackageName().equals(pkg)) return err("不能从网页卸载当前管理 App");
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            String cmd = system
                    ? "pm uninstall --user 0 " + pkg + " 2>&1"
                    : "pm uninstall " + pkg + " 2>&1";
            return startTask(ctx, "uninstall", pkg, cmd);
        } catch (PackageManager.NameNotFoundException e) {
            return err("未找到这个包");
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    private static JSONObject startTask(Context ctx, String kind, String target, String cmd) {
        synchronized (TASK_LOCK) {
            if (task.running) return err("已有任务正在执行");
            try (FileOutputStream fo = new FileOutputStream(taskLog(ctx), false)) {
                fo.write(new byte[0]);
            } catch (Exception ignored) {}
            task = new Task(kind, target);
            appendLogLocked(ctx, task, "start " + kind + ": " + target);
            Task cur = task;
            Thread t = new Thread(() -> {
                int code = runRootCapture(ctx, cur, cmd);
                synchronized (TASK_LOCK) {
                    cur.running = false;
                    cur.ok = code == 0;
                    cur.finishedAt = System.currentTimeMillis();
                    appendLogLocked(ctx, cur, "exit=" + code);
                }
            }, "web-appmgr-" + kind);
            t.setDaemon(true);
            t.start();
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("task", taskJsonLocked());
            } catch (Exception e) {
                putErr(o, e.getMessage());
            }
            return o;
        }
    }

    private static int runRootCapture(Context ctx, Task t, String cmd) {
        int code = -1;
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (TASK_LOCK) { appendLogLocked(ctx, t, line); }
                }
            }
            code = p.waitFor();
        } catch (Exception e) {
            synchronized (TASK_LOCK) { appendLogLocked(ctx, t, "error: " + e.getMessage()); }
        }
        return code;
    }

    private static JSONObject taskJson() {
        synchronized (TASK_LOCK) { return taskJsonLocked(); }
    }

    private static JSONObject taskJsonLocked() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", task.running);
            o.put("kind", task.kind);
            o.put("target", task.target);
            o.put("ok", task.ok);
            o.put("startedAt", task.startedAt);
            o.put("finishedAt", task.finishedAt);
            o.put("log", task.log.toString());
        } catch (Exception ignored) {}
        return o;
    }

    private static void appendLogLocked(Context ctx, Task t, String line) {
        t.log.append(line == null ? "" : line).append('\n');
        int extra = t.log.length() - 20000;
        if (extra > 0) t.log.delete(0, extra);
        try (FileOutputStream fo = new FileOutputStream(taskLog(ctx), true)) {
            fo.write(((line == null ? "" : line) + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    private static JSONObject uploadJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            File apk = latestApk(ctx);
            o.put("exists", apk.exists() && apk.length() > 0);
            if (apk.exists() && apk.length() > 0) {
                o.put("path", apk.getAbsolutePath());
                o.put("size", apk.length());
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (pi != null) {
                    o.put("packageName", pi.packageName == null ? "" : pi.packageName);
                    o.put("versionName", pi.versionName == null ? "" : pi.versionName);
                    o.put("versionCode", pi.versionCode);
                    o.put("self", ctx.getPackageName().equals(pi.packageName));
                }
            }
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject finalizeApk(Context ctx, File tmp, String label) {
        JSONObject o = new JSONObject();
        try {
            if (tmp == null || !tmp.exists() || tmp.length() <= 0) return err("文件为空");
            tmp.setReadable(true, false);
            if (!isZip(tmp)) {
                tmp.delete();
                return err("这不是 APK 文件");
            }
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
            if (pi == null || pi.packageName == null) {
                tmp.delete();
                return err("APK 解析失败");
            }
            File latest = latestApk(ctx);
            if (latest.exists()) latest.delete();
            if (!tmp.renameTo(latest)) {
                copyFile(tmp, latest);
                tmp.delete();
            }
            latest.setReadable(true, false);
            o.put("ok", true);
            o.put("fileName", decodeHeader(label));
            o.put("upload", uploadJson(ctx));
        } catch (Exception e) {
            if (tmp != null) tmp.delete();
            putErr(o, e.getMessage());
        }
        return o;
    }

    private static File latestApk(Context ctx) {
        return new File(uploadDir(ctx), "latest.apk");
    }

    private static File taskLog(Context ctx) {
        return new File(uploadDir(ctx), "last-task.log");
    }

    private static String rootReadablePath(File file) {
        String path = file.getAbsolutePath();
        String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        String prefix = external.endsWith(File.separator) ? external : external + File.separator;
        if (path.startsWith(prefix)) {
            // Android 5.1 FUSE can deny uid 0 on /storage/emulated/0; use its backing path.
            return "/data/media/0/" + path.substring(prefix.length());
        }
        return path;
    }

    private static File uploadDir(Context ctx) {
        File d = new File(Environment.getExternalStorageDirectory(), "Download/oracle-compass/uploads");
        if (ensureDir(d)) return d;
        File ext = ctx.getExternalFilesDir("uploads");
        if (ext != null && ensureDir(ext)) return ext;
        File priv = new File(ctx.getFilesDir(), "uploads");
        ensureDir(priv);
        return priv;
    }

    private static boolean ensureDir(File d) {
        try {
            if (!d.exists() && !d.mkdirs()) return false;
            return d.isDirectory() && d.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkPassword(Context ctx, String password) {
        try {
            if (password == null) return false;
            String salt = Prefs.get(ctx, Prefs.K_WEB_ADMIN_SALT, "");
            String saved = Prefs.get(ctx, Prefs.K_WEB_ADMIN_HASH, "");
            return !salt.isEmpty() && !saved.isEmpty() && constEq(saved, hash(salt, password));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hash(String salt, String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] b = md.digest((salt + "\n" + password).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static boolean constEq(String a, String b) {
        if (a == null || b == null) return false;
        int diff = a.length() ^ b.length();
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
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

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private static boolean isZip(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String decodeHeader(String s) {
        try { return s == null ? "" : URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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
        } catch (Exception ignored) {}
    }

    private static class Task {
        boolean running;
        String kind;
        String target;
        boolean ok;
        long startedAt;
        long finishedAt;
        StringBuilder log = new StringBuilder();

        Task(String kind, String target) {
            this.running = true;
            this.kind = kind;
            this.target = target;
            this.startedAt = System.currentTimeMillis();
        }

        static Task idle() {
            Task t = new Task("idle", "");
            t.running = false;
            t.ok = true;
            t.finishedAt = t.startedAt;
            return t;
        }
    }
}
