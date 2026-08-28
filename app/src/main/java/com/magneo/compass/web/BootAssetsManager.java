package com.magneo.compass.web;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restricted backup and flashing operations for this device's MTK boot resources. */
public final class BootAssetsManager {
    private static final long MAX_LOGO_BYTES = 64L * 1024L * 1024L;
    private static final String DEVICE_DIR = "C110001";
    private static final Object LOCK = new Object();
    private static final String LOGO_RAW = "logo-original.bin";
    private static final String LOGO_GZIP = "logo-original.bin.gz";
    private static final String BOOTANIM = "bootanimation-original.zip";
    private static final String SHUTANIM = "shutanimation-original.zip";
    private static final String MANIFEST = "manifest.json";
    private static final String SUMS = "SHA256SUMS";
    private static final String UPLOAD = "logo-custom.bin";

    private BootAssetsManager() {}

    public static JSONObject status(Context context) {
        synchronized (LOCK) {
            JSONObject root = new JSONObject();
            try {
                Probe probe = probe();
                root.put("ok", probe.ok);
                root.put("partition", probe.partition);
                root.put("partitionBytes", probe.bytes);
                root.put("mirrorBootanimation", probe.mirrorBootanimation);
                root.put("mirrorShutanimation", probe.mirrorShutanimation);
                root.put("probe", probe.detail);
                putFile(root, "logoRaw", file(context, LOGO_RAW));
                putFile(root, "logoGzip", file(context, LOGO_GZIP));
                putFile(root, "bootanimation", file(context, BOOTANIM));
                putFile(root, "shutanimation", file(context, SHUTANIM));
                putFile(root, "activeShutanimation", new File("/system/media/shutanimation.zip"));
                putFile(root, "manifest", file(context, MANIFEST));
                putFile(root, "uploadedLogo", file(context, UPLOAD));
                root.put("battery", battery(context));
            } catch (Exception e) {
                putErr(root, e.getMessage());
            }
            return root;
        }
    }

    public static JSONObject bootLog() {
        JSONObject result = new JSONObject();
        CommandResult command = root("F=/data/local/oracle-compass-boot.log; "
                + "[ -f \"$F\" ] || { echo boot_log_missing; exit 2; }; "
                + "BB=/sbin/.magisk/busybox/busybox; "
                + "if [ -x \"$BB\" ]; then \"$BB\" tail -n 160 \"$F\"; "
                + "else cat \"$F\"; fi");
        try {
            result.put("ok", command.ok);
            if (command.ok) result.put("log", command.output);
            else result.put("err", compact(command.output));
        } catch (Exception ignored) {}
        return result;
    }

    public static JSONObject backup(Context context) {
        synchronized (LOCK) {
            JSONObject root = new JSONObject();
            try {
                Probe probe = probe();
                if (!probe.ok) return err(probe.detail);
                if (probe.mirrorBootanimation.isEmpty()) {
                    return err("未找到 Magisk mirror 中的原厂 bootanimation，已拒绝把当前覆盖文件当作原厂备份");
                }
                if (probe.mirrorShutanimation.isEmpty()) {
                    return err("未找到 Magisk mirror 中的原厂 shutanimation，拒绝覆盖现有备份");
                }
                File dir = dir(context);
                File existingRaw = file(context, LOGO_RAW);
                boolean captureLogo = !existingRaw.isFile() || existingRaw.length() != probe.bytes;
                String logoBackup = captureLogo
                        ? "$BB dd if=\"$P\" of=\"$D/" + LOGO_RAW + ".part\" bs=1048576 2>&1 || exit 21; "
                        + "[ -s \"$D/" + LOGO_RAW + ".part\" ] || exit 22; "
                        + "$BB gzip -c \"$D/" + LOGO_RAW + ".part\" > \"$D/" + LOGO_GZIP + ".part\" || exit 23; "
                        + "$BB mv \"$D/" + LOGO_RAW + ".part\" \"$D/" + LOGO_RAW + "\"; "
                        + "$BB mv \"$D/" + LOGO_GZIP + ".part\" \"$D/" + LOGO_GZIP + "\"; "
                        : "";
                String command = busyboxPrefix()
                        + "P=" + q(probe.partition) + "; D=" + q(dir.getAbsolutePath()) + "; "
                        + "mkdir -p \"$D\"; rm -f \"$D/" + BOOTANIM + ".part\"; "
                        + "rm -f \"$D/" + SHUTANIM + ".part\"; "
                        + logoBackup
                        + "$BB cp " + q(probe.mirrorBootanimation) + " \"$D/" + BOOTANIM + ".part\" || exit 24; "
                        + "[ -s \"$D/" + BOOTANIM + ".part\" ] || exit 25; "
                        + "$BB cp " + q(probe.mirrorShutanimation) + " \"$D/" + SHUTANIM + ".part\" || exit 26; "
                        + "[ -s \"$D/" + SHUTANIM + ".part\" ] || exit 27; "
                        + "$BB mv \"$D/" + BOOTANIM + ".part\" \"$D/" + BOOTANIM + "\"; "
                        + "$BB mv \"$D/" + SHUTANIM + ".part\" \"$D/" + SHUTANIM + "\"; "
                        + "chmod 644 \"$D/" + LOGO_RAW + "\" \"$D/" + LOGO_GZIP + "\" "
                        + "\"$D/" + BOOTANIM + "\" \"$D/" + SHUTANIM + "\"";
                CommandResult result = root(command);
                if (!result.ok) return err("备份失败: " + compact(result.output));
                File raw = file(context, LOGO_RAW);
                File gzip = file(context, LOGO_GZIP);
                File animation = file(context, BOOTANIM);
                File shutdown = file(context, SHUTANIM);
                if (raw.length() != probe.bytes) {
                    return err("logo 备份容量不一致: " + raw.length() + " / " + probe.bytes);
                }
                writeManifest(context, probe, raw, gzip, animation, shutdown);
                root.put("ok", true);
                root.put("msg", "原厂 logo、开机动画与关机动画已备份并校验");
                root.put("status", status(context));
            } catch (Exception e) {
                putErr(root, e.getMessage());
            }
            return root;
        }
    }

    /** Restores the immutable stock backup file without writing the physical logo partition. */
    public static JSONObject uploadOriginalLogo(Context context, InputStream input, long length,
                                                String expectedSha) {
        synchronized (LOCK) {
            File part = file(context, LOGO_RAW + ".part");
            try {
                String expected = normalizeSha(expectedSha);
                if (expected.isEmpty()) return err("缺少原厂 logo 的预期 SHA-256");
                Probe probe = probe();
                if (!probe.ok || length != probe.bytes) {
                    return err("原厂备份容量与 logo 分区不一致: " + length + " / " + probe.bytes);
                }
                if (part.exists()) part.delete();
                long copied = copyExact(input, part, length);
                if (copied != length) {
                    part.delete();
                    return err("原厂备份上传中断: " + copied + " / " + length);
                }
                String actual = sha256(part);
                if (!expected.equals(actual)) {
                    part.delete();
                    return err("原厂备份 SHA-256 不一致");
                }
                File raw = file(context, LOGO_RAW);
                if (raw.exists()) raw.delete();
                if (!part.renameTo(raw)) return err("无法保存原厂 logo 备份");
                raw.setReadable(true, false);
                File gzip = file(context, LOGO_GZIP);
                File gzipPart = file(context, LOGO_GZIP + ".part");
                CommandResult result = root(busyboxPrefix()
                        + "$BB gzip -c " + q(raw.getAbsolutePath()) + " > "
                        + q(gzipPart.getAbsolutePath()) + " && $BB mv "
                        + q(gzipPart.getAbsolutePath()) + " " + q(gzip.getAbsolutePath())
                        + " && chmod 644 " + q(gzip.getAbsolutePath()));
                if (!result.ok) return err("原厂备份压缩失败: " + compact(result.output));
                File boot = file(context, BOOTANIM);
                File shutdown = file(context, SHUTANIM);
                if (boot.isFile() && shutdown.isFile()) {
                    writeManifest(context, probe, raw, gzip, boot, shutdown);
                }
                JSONObject root = new JSONObject();
                root.put("ok", true).put("bytes", raw.length()).put("sha256", actual)
                        .put("msg", "设备端原厂 logo 备份已恢复，未写入物理分区");
                return root;
            } catch (Exception e) {
                part.delete();
                return err(e.getMessage());
            }
        }
    }

    public static JSONObject uploadLogo(Context context, InputStream input, long length) {
        synchronized (LOCK) {
            File part = file(context, UPLOAD + ".part");
            try {
                if (length <= 0) return err("上传内容为空");
                if (length > MAX_LOGO_BYTES) return err("logo.bin 超过 64MB 上限");
                File original = file(context, LOGO_RAW);
                if (!original.isFile() || original.length() <= 0) return err("请先创建原厂 logo 备份");
                if (length != original.length()) {
                    return err("上传容量与原分区不一致: " + length + " / " + original.length());
                }
                if (part.exists()) part.delete();
                long copied = copyExact(input, part, length);
                if (copied != length) {
                    part.delete();
                    return err("上传中断: " + copied + " / " + length);
                }
                File target = file(context, UPLOAD);
                if (target.exists()) target.delete();
                if (!part.renameTo(target)) return err("无法保存上传的 logo.bin");
                target.setReadable(true, false);
                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("bytes", target.length());
                root.put("sha256", sha256(target));
                return root;
            } catch (Exception e) {
                part.delete();
                return err(e.getMessage());
            }
        }
    }

    public static JSONObject flashLogo(Context context, String body) {
        synchronized (LOCK) {
            try {
                JSONObject request = formJson(body);
                if (!"FLASH LOGO".equals(request.optString("confirm"))) {
                    return err("确认短语必须是 FLASH LOGO");
                }
                String expected = normalizeSha(request.optString("expectedSha"));
                if (expected.isEmpty()) return err("缺少预期 SHA-256");
                File upload = file(context, UPLOAD);
                File original = file(context, LOGO_RAW);
                if (!upload.isFile() || !original.isFile()) return err("缺少上传文件或原厂备份");
                if (upload.length() != original.length()) return err("上传容量与原厂备份不一致");
                String actual = sha256(upload);
                if (!actual.equals(expected)) return err("上传 SHA 与确认值不一致");
                String originalSha = sha256(original);
                JSONObject battery = battery(context);
                if (battery.optInt("level", 0) <= 50 && !battery.optBoolean("charging", false)) {
                    return err("刷写要求电量超过 50% 或正在充电");
                }
                Probe probe = probe();
                if (!probe.ok || probe.bytes != upload.length()) return err("logo 分区容量校验失败");
                String resultPath = new File(dir(context), "logo-readback.bin").getAbsolutePath();
                String command = flashCommand(probe.partition, upload.getAbsolutePath(),
                        original.getAbsolutePath(), resultPath, expected, originalSha);
                CommandResult result = root(command);
                File readback = new File(resultPath);
                String readbackSha = readback.isFile() ? sha256(readback) : "";
                readback.delete();
                if (!result.ok || !expected.equals(readbackSha)) {
                    return err("刷写核验失败，已尝试恢复原厂备份: " + compact(result.output));
                }
                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("sha256", readbackSha);
                root.put("msg", "自定义 logo 已刷写并完成整分区回读校验");
                return root;
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }

    public static JSONObject restoreLogo(Context context, String body) {
        synchronized (LOCK) {
            try {
                JSONObject request = formJson(body);
                if (!"RESTORE STOCK LOGO".equals(request.optString("confirm"))) {
                    return err("确认短语必须是 RESTORE STOCK LOGO");
                }
                File original = file(context, LOGO_RAW);
                if (!original.isFile()) return err("缺少原厂 logo 备份");
                String expected = normalizeSha(request.optString("expectedSha"));
                String originalSha = sha256(original);
                if (!originalSha.equals(expected)) return err("原厂备份 SHA 与确认值不一致");
                JSONObject battery = battery(context);
                if (battery.optInt("level", 0) <= 50 && !battery.optBoolean("charging", false)) {
                    return err("恢复要求电量超过 50% 或正在充电");
                }
                Probe probe = probe();
                if (!probe.ok || probe.bytes != original.length()) return err("logo 分区容量校验失败");
                String resultPath = new File(dir(context), "logo-readback.bin").getAbsolutePath();
                String command = busyboxPrefix() + "P=" + q(probe.partition) + "; "
                        + "$BB dd if=" + q(original.getAbsolutePath()) + " of=\"$P\" bs=1048576 conv=fsync 2>&1 || exit 31; "
                        + "$BB dd if=\"$P\" of=" + q(resultPath) + " bs=1048576 2>&1 || exit 32; chmod 644 " + q(resultPath);
                CommandResult result = root(command);
                File readback = new File(resultPath);
                String readbackSha = readback.isFile() ? sha256(readback) : "";
                readback.delete();
                if (!result.ok || !expected.equals(readbackSha)) return err("原厂 logo 恢复核验失败");
                JSONObject root = new JSONObject();
                root.put("ok", true).put("sha256", readbackSha)
                        .put("msg", "原厂 logo 已恢复并完成整分区回读校验");
                return root;
            } catch (Exception e) {
                return err(e.getMessage());
            }
        }
    }

    public static File downloadFile(Context context, String kind) {
        if ("logo".equals(kind)) return readable(file(context, LOGO_GZIP));
        if ("bootanimation".equals(kind)) return readable(file(context, BOOTANIM));
        if ("shutanimation".equals(kind)) return readable(file(context, SHUTANIM));
        if ("manifest".equals(kind)) return readable(file(context, MANIFEST));
        if ("sums".equals(kind)) return readable(file(context, SUMS));
        return null;
    }

    private static String flashCommand(String partition, String upload, String original,
                                       String readback, String expected, String originalExpected) {
        return busyboxPrefix() + "P=" + q(partition) + "; "
                + "FAIL=0; "
                + "$BB dd if=" + q(upload) + " of=\"$P\" bs=1048576 conv=fsync 2>&1 || FAIL=31; "
                + "if [ $FAIL -eq 0 ]; then $BB dd if=\"$P\" of=" + q(readback)
                + " bs=1048576 2>&1 || FAIL=32; fi; "
                + "ACT=''; if [ $FAIL -eq 0 ]; then chmod 644 " + q(readback)
                + "; ACT=\"$($BB sha256sum " + q(readback) + " | $BB awk '{print $1}')\"; fi; "
                + "echo ORACLE_READBACK_SHA=$ACT; "
                + "if [ $FAIL -ne 0 ] || [ \"$ACT\" != " + q(expected) + " ]; then "
                + "echo ORACLE_FLASH_FAILED=$FAIL; "
                + "$BB dd if=" + q(original) + " of=\"$P\" bs=1048576 conv=fsync 2>&1 || exit 39; "
                + "$BB dd if=\"$P\" of=" + q(readback) + " bs=1048576 2>&1 || exit 40; "
                + "chmod 644 " + q(readback) + "; "
                + "RESTORED=\"$($BB sha256sum " + q(readback) + " | $BB awk '{print $1}')\"; "
                + "echo ORACLE_RESTORED_SHA=$RESTORED; "
                + "[ \"$RESTORED\" = " + q(originalExpected) + " ] || exit 41; exit 33; fi";
    }

    private static void writeManifest(Context context, Probe probe, File raw, File gzip,
                                      File animation, File shutdown) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("schema", 1);
        manifest.put("device", DEVICE_DIR);
        manifest.put("androidApi", Build.VERSION.SDK_INT);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        manifest.put("capturedAtUtc", format.format(new Date()));
        JSONObject logo = new JSONObject();
        logo.put("file", LOGO_GZIP);
        logo.put("rawBytes", raw.length());
        logo.put("rawSha256", sha256(raw));
        logo.put("gzipBytes", gzip.length());
        logo.put("gzipSha256", sha256(gzip));
        manifest.put("logo", logo);
        JSONObject boot = new JSONObject();
        boot.put("file", BOOTANIM);
        boot.put("bytes", animation.length());
        boot.put("sha256", sha256(animation));
        boot.put("source", "Magisk mirror of stock /system/media/bootanimation.zip");
        manifest.put("bootanimation", boot);
        JSONObject shut = new JSONObject();
        shut.put("file", SHUTANIM);
        shut.put("bytes", shutdown.length());
        shut.put("sha256", sha256(shutdown));
        shut.put("source", "Magisk mirror of stock /system/media/shutanimation.zip");
        manifest.put("shutanimation", shut);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file(context, MANIFEST)), "UTF-8")) {
            writer.write(manifest.toString(2));
            writer.write('\n');
        }
        String sums = sha256(gzip) + "  " + LOGO_GZIP + "\n"
                + sha256(animation) + "  " + BOOTANIM + "\n"
                + sha256(shutdown) + "  " + SHUTANIM + "\n"
                + sha256(file(context, MANIFEST)) + "  " + MANIFEST + "\n";
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file(context, SUMS)), "UTF-8")) {
            writer.write(sums);
        }
    }

    private static Probe probe() {
        String command = busyboxPrefix()
                + "P=''; for X in /dev/block/platform/*/by-name/logo /dev/block/by-name/logo; do "
                + "[ -b \"$X\" ] && { P=\"$X\"; break; }; done; "
                + "[ -n \"$P\" ] || { echo ORACLE_ERROR=no_logo_partition; exit 41; }; "
                + "B=\"$($BB blockdev --getsize64 \"$P\" 2>/dev/null)\"; "
                + "[ -n \"$B\" ] || B=$(( $($BB blockdev --getsz \"$P\") * 512 )); "
                + "M=''; for X in /sbin/.magisk/mirror/system/media/bootanimation.zip "
                + "/sbin/.magisk/mirror/system_root/system/media/bootanimation.zip; do "
                + "[ -f \"$X\" ] && { M=\"$X\"; break; }; done; "
                + "S=''; for X in /sbin/.magisk/mirror/system/media/shutanimation.zip "
                + "/sbin/.magisk/mirror/system_root/system/media/shutanimation.zip; do "
                + "[ -f \"$X\" ] && { S=\"$X\"; break; }; done; "
                + "echo ORACLE_PARTITION=$P; echo ORACLE_BYTES=$B; "
                + "echo ORACLE_MIRROR=$M; echo ORACLE_SHUT_MIRROR=$S";
        CommandResult result = root(command);
        String partition = label(result.output, "ORACLE_PARTITION");
        String mirror = label(result.output, "ORACLE_MIRROR");
        String shutMirror = label(result.output, "ORACLE_SHUT_MIRROR");
        long bytes = parseLong(label(result.output, "ORACLE_BYTES"), -1);
        boolean ok = result.ok && !partition.isEmpty() && bytes > 0 && bytes <= MAX_LOGO_BYTES;
        return new Probe(ok, partition, bytes, mirror, shutMirror,
                ok ? "logo 分区已识别" : compact(result.output));
    }

    private static String busyboxPrefix() {
        return "BB=/sbin/.magisk/busybox/busybox; [ -x \"$BB\" ] || BB=busybox; ";
    }

    private static JSONObject battery(Context context) {
        JSONObject o = new JSONObject();
        try {
            Intent state = context.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = state == null ? 0 : state.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = state == null ? 100 : state.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = state == null ? 0 : state.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            o.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);
            o.put("charging", charging);
        } catch (Exception ignored) {}
        return o;
    }

    private static void putFile(JSONObject root, String key, File file) throws Exception {
        JSONObject o = new JSONObject();
        o.put("exists", file != null && file.isFile() && file.length() > 0);
        if (file != null && file.isFile() && file.length() > 0) {
            o.put("bytes", file.length());
            o.put("sha256", sha256(file));
        }
        root.put(key, o);
    }

    private static File readable(File file) {
        return file != null && file.isFile() && file.length() > 0 ? file : null;
    }

    private static File dir(Context context) {
        File root = new File(Environment.getExternalStorageDirectory(),
                "oracle-compass-boot-assets/" + DEVICE_DIR);
        if (!root.exists()) root.mkdirs();
        root.setReadable(true, false);
        root.setWritable(true, false);
        return root;
    }

    private static File file(Context context, String name) {
        return new File(dir(context), name);
    }

    private static JSONObject formJson(String body) {
        JSONObject o = new JSONObject();
        if (body == null) return o;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
                String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";
                o.put(key, value);
            } catch (Exception ignored) {}
        }
        return o;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static long copyExact(InputStream input, File target, long length) throws Exception {
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream output = new FileOutputStream(target)) {
            while (copied < length) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, length - copied));
                if (read < 0) break;
                if (read == 0) continue;
                output.write(buffer, 0, read);
                copied += read;
            }
            output.flush();
        }
        return copied;
    }

    private static String normalizeSha(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return s.matches("[0-9a-f]{64}") ? s : "";
    }

    private static String label(String output, String name) {
        Matcher matcher = Pattern.compile("(?:^|\\n)" + Pattern.quote(name) + "=([^\\r\\n]*)")
                .matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static String q(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
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

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        putErr(o, message);
        return o;
    }

    private static void putErr(JSONObject o, String message) {
        try {
            o.put("ok", false);
            o.put("err", message == null || message.trim().isEmpty() ? "操作失败" : message);
        } catch (Exception ignored) {}
    }

    private static String compact(String value) {
        String s = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > 420) s = s.substring(0, 420);
        return s.isEmpty() ? "Root 操作没有返回详情" : s;
    }

    private static final class Probe {
        final boolean ok;
        final String partition;
        final long bytes;
        final String mirrorBootanimation;
        final String mirrorShutanimation;
        final String detail;
        Probe(boolean ok, String partition, long bytes, String mirrorBootanimation,
              String mirrorShutanimation, String detail) {
            this.ok = ok;
            this.partition = partition == null ? "" : partition;
            this.bytes = bytes;
            this.mirrorBootanimation = mirrorBootanimation == null ? "" : mirrorBootanimation;
            this.mirrorShutanimation = mirrorShutanimation == null ? "" : mirrorShutanimation;
            this.detail = detail == null ? "" : detail;
        }
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
