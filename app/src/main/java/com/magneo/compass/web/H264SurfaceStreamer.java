package com.magneo.compass.web;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;

/**
 * 高速 H.264 推流（root screenrecord 版）：
 * screenrecord（虚拟显示+硬件编码，30fps）以 root 运行，不经 MediaProjection
 * （其授权弹窗在本 ROM 会崩溃 SystemUI）。应用实时解析输出 MP4 的 mdat
 * （AVCC 长度前缀 H.264），按访问单元重组为 fMP4 推给浏览器。
 */
public class H264SurfaceStreamer {
    private static final String TAG = "H264SurfaceStreamer";
    private static final String FILE = "/sdcard/truthscreen.mp4";
    private static final int W = 720, H = 720;
    private static final long DUR_US = 33333; // 30fps

    private static volatile boolean active;

    private H264SurfaceStreamer() {}

    public static boolean isActive() { return active; }

    /** 清理历史遗留的 screenrecord 编码链。
     *  应用崩溃/断连后，magiskd 派生的 su 包装 sh 会带着 "while true" 循环继续存活，
     *  每 180 秒重新拉起一个 screenrecord 编码器；多次崩溃可积累十几个编码器把 4 核 CPU 打满。
     *  这里按进程树精确杀掉 screenrecord 及其祖先（到 magiskd/init 为止），不影响其他 su 会话。 */
    public static void cleanupStale() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "for i in 1 2 3; do "
                    + "ps | grep \"[s]creenrecord\" | while read u p pp rest; do "
                    + "cur=$p; while :; do st=$(cat /proc/$cur/stat 2>/dev/null) || break; "
                    + "set -- $st; par=$4; kill -9 $cur 2>/dev/null; "
                    + "pc=$(cat /proc/$par/comm 2>/dev/null); "
                    + "[ \"$pc\" = \"magiskd\" ] && break; [ \"$par\" -le 2 ] && break; cur=$par; done; done; "
                    + "sleep 1; done; "
                    + "killall -9 screenrecord 2>/dev/null"});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    public static void serve(Socket s, Context ctx) {
        synchronized (H264SurfaceStreamer.class) {
            if (active || H264Streamer.isActive() || ScreenStreamer.isActive()) { writeBusy(s); return; }
            active = true;
        }
        Process sr = null;
        try {
            cleanupStale();                            // 先清掉历史残留编码器，避免新会话叠加
            ScreenAwake.on(ctx);                       // 推流期间屏幕常亮，防息屏黑屏
            Fmp4Muxer mux = new Fmp4Muxer();
            dbg(ctx, "serve enter");
            byte[] init = extractConfig(ctx);          // 2 秒短片提取 SPS/PPS -> init
            if (init == null) { dbg(ctx, "extractConfig NULL"); writeBusy(s); return; }
            dbg(ctx, "init ok " + init.length);
            sr = startScreenrecord(ctx);
            dbg(ctx, "screenrecord started");
            run(s, ctx, mux, init);
        } catch (Throwable t) {
            Log.w(TAG, "serve", t);
        } finally {
            active = false;
            stopScreenrecord(sr);
            ScreenAwake.off();                         // 恢复屏幕自动休眠
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** 跑一段 2 秒 screenrecord，从末尾 moov 的 avcC 提取 SPS/PPS 并生成 init 段。 */
    private static byte[] extractConfig(Context ctx) {
        try {
            String cfg = "/sdcard/truthcfg.mp4";
            int kbps = clamp(Prefs.getI(ctx, Prefs.K_STREAM_BITRATE, 8000), 300, 8000);
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "screenrecord --size " + W + "x" + H + " --bit-rate " + (kbps * 1000)
                    + " --time-limit 2 " + cfg + " 2>/dev/null"});
            p.waitFor();
            File f = new File(cfg);
            if (!f.exists() || f.length() < 100) return null;
            byte[] d = new byte[(int) f.length()];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.readFully(d); raf.close();
            // 找末尾 moov 里的 avcC
            int m = d.length - 1;
            for (; m >= 0; m--) {
                if (d[m] == 'C' && m >= 7 && d[m-1] == 'c' && d[m-2] == 'v' && d[m-3] == 'a') break;
            }
            if (m < 0) return null;
            // size 字段在类型 "avcC" 之前 4 字节：'a' 在 m-3，size 在 m-7..m-4
            int acsz = ((d[m-7]&0xff)<<24)|((d[m-6]&0xff)<<16)|((d[m-5]&0xff)<<8)|(d[m-4]&0xff);
            if (acsz <= 8 || m - 7 + acsz > d.length) return null;
            byte[] b = java.util.Arrays.copyOfRange(d, m + 1, m - 7 + acsz);
            int sl = ((b[6]&0xff)<<8)|(b[7]&0xff);
            if (sl <= 0 || sl > 64 || 8 + sl + 3 > b.length) return null;
            byte[] sps = java.util.Arrays.copyOfRange(b, 8, 8 + sl);
            int pl = ((b[8+sl+1]&0xff)<<8)|(b[8+sl+2]&0xff);
            if (pl <= 0 || 8 + sl + 3 + pl > b.length) return null;
            byte[] pps = java.util.Arrays.copyOfRange(b, 8 + sl + 3, 8 + sl + 3 + pl);
            return new Fmp4Muxer().init(W, H, sps, pps);
        } catch (Exception e) {
            Log.w(TAG, "extractConfig", e);
            return null;
        }
    }

    private static Process startScreenrecord(Context ctx) throws Exception {
        int kbps = clamp(Prefs.getI(ctx, Prefs.K_STREAM_BITRATE, 8000), 300, 8000);
        return Runtime.getRuntime().exec(new String[]{"su", "-c",
                "rm -f " + FILE + "; while true; do screenrecord --size " + W + "x" + H
                + " --bit-rate " + (kbps * 1000) + " --time-limit 180 " + FILE + "; done"});
    }

    private static void stopScreenrecord(Process sr) {
        try { if (sr != null) sr.destroy(); } catch (Exception ignored) {}
        cleanupStale();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "rm -f " + FILE});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static void run(Socket s, Context ctx, Fmp4Muxer mux, byte[] init) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.flush();

        // 发送 init 段（mdat 里没有 SPS/PPS，来自短片 moov）
        writeChunk(out, init);
        out.flush();
        dbg(ctx, "init sent");

        File f = new File(FILE);
        RandomAccessFile raf = null;
        byte[] leftover = new byte[0];
        long readPos = 0;
        boolean inMdat = false;
        int nullReads = 0;
        long frameIndex = 0;

        while (!Thread.currentThread().isInterrupted()) {
            if (!f.exists()) { Thread.sleep(50); continue; }
            if (raf == null) raf = new RandomAccessFile(f, "r");

            long len = f.length();
            if (len < readPos) {
                // screenrecord 重开新片段：重同步
                dbg(ctx, "clip restart len=" + len + " readPos=" + readPos);
                readPos = 0; inMdat = false; leftover = new byte[0];
                try { raf.close(); } catch (Exception ignored) {}
                raf = new RandomAccessFile(f, "r");
                nullReads = 0;
                continue;
            }
            if (len <= readPos) {
                if (++nullReads >= 12000) break; // 约 10 分钟无数据：静止画面不主动断流，仅兜底清理死连接
                Thread.sleep(50);
                continue;
            }
            nullReads = 0;
            long avail = Math.min(65536, len - readPos);
            raf.seek(readPos);
            byte[] buf = new byte[(int) avail];
            int n = raf.read(buf, 0, buf.length);
            if (n <= 0) { Thread.sleep(30); continue; }
            readPos += n;

            // 合并 leftover + 新数据
            byte[] data;
            if (leftover.length > 0) {
                data = new byte[leftover.length + n];
                System.arraycopy(leftover, 0, data, 0, leftover.length);
                System.arraycopy(buf, 0, data, leftover.length, n);
                leftover = new byte[0];
            } else {
                data = buf;
            }

            int off = 0;
            if (!inMdat) {
                // 找 mdat 盒起点
                int idx = indexOf(data, "mdat".getBytes("ISO-8859-1"), 0);
                if (idx < 0) { leftover = data; continue; }
                inMdat = true;
                off = idx + 4; // idx 是类型 "mdat" 起点，数据在类型后 4 字节
                dbg(ctx, "mdat found at " + idx);
            }

            // 解析 AVCC NAL
            int p = off;
            while (p + 4 <= data.length) {
                int nalLen = ((data[p]&0xff)<<24)|((data[p+1]&0xff)<<16)|((data[p+2]&0xff)<<8)|(data[p+3]&0xff);
                if (nalLen <= 0 || nalLen > 8 * 1024 * 1024) { p++; continue; }
                if (p + 4 + nalLen > data.length) break; // 不完整，留到下一段
                byte[] nal = new byte[nalLen];
                System.arraycopy(data, p + 4, nal, 0, nalLen);
                p += 4 + nalLen;
                frameIndex = onNal(nal, mux, out, frameIndex, init);
                if (frameIndex % 150 == 0) dbg(ctx, "frames=" + frameIndex);
            }
            if (p < data.length) {
                leftover = new byte[data.length - p];
                System.arraycopy(data, p, leftover, 0, leftover.length);
            } else {
                leftover = new byte[0];
            }
        }
        if (raf != null) try { raf.close(); } catch (Exception ignored) {}
    }

    // ---- NAL / 访问单元处理 ----
    private static ByteArrayOutputStream curAU = new ByteArrayOutputStream();
    private static boolean auHasVcl = false;

    private static long onNal(byte[] nal, Fmp4Muxer mux, OutputStream out, long frameIndex, byte[] init) throws Exception {
        if (nal.length == 0) return frameIndex;
        int type = nal[0] & 0x1f;
        boolean vcl = type >= 1 && type <= 5;
        boolean startsNewAU = false;

        if (vcl) {
            startsNewAU = firstMbInSlice(nal) == 0;
        } else if ((type == 7 || type == 8 || type == 9) && curAU.size() > 0) {
            startsNewAU = true; // SPS/PPS/AUD 一般开始新帧
        }

        if (startsNewAU && curAU.size() > 0) {
            frameIndex = flushAU(mux, out, frameIndex, init);
        }

        // 写入当前 AU：4 字节长度 + NAL
        curAU.write((nal.length >>> 24) & 0xff);
        curAU.write((nal.length >>> 16) & 0xff);
        curAU.write((nal.length >>> 8) & 0xff);
        curAU.write(nal.length & 0xff);
        curAU.write(nal, 0, nal.length);
        if (vcl) auHasVcl = true;
        return frameIndex;
    }

    private static long flushAU(Fmp4Muxer mux, OutputStream out, long frameIndex, byte[] init) throws Exception {
        byte[] au = curAU.toByteArray();
        curAU.reset();
        auHasVcl = false;
        if (au.length == 0) return frameIndex;

        boolean key = containsIdr(au);
        byte[] frag = mux.fragment(frameIndex * DUR_US, DUR_US, key, au);
        writeChunk(out, frag);
        out.flush();
        return frameIndex + 1;
    }

    private static boolean containsIdr(byte[] au) {
        int i = 0;
        while (i + 5 <= au.length) {
            int len = ((au[i]&0xff)<<24)|((au[i+1]&0xff)<<16)|((au[i+2]&0xff)<<8)|(au[i+3]&0xff);
            if (len > 0 && i + 4 + len <= au.length && (au[i+4] & 0x1f) == 5) return true;
            i += 4 + Math.max(0, len);
        }
        return false;
    }

    private static int firstMbInSlice(byte[] nal) {
        if (nal.length < 2) return 0;
        int bitPos = 0;
        int byteIdx = 1;
        // 读取 ue(v) first_mb_in_slice
        int zeroes = 0;
        while (true) {
            if (byteIdx >= nal.length) return 0;
            int bit = (nal[byteIdx] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) { bitPos = 0; byteIdx++; }
            if (bit == 1) break;
            zeroes++;
        }
        if (zeroes > 20) return 0;
        long val = (1L << zeroes) - 1;
        for (int i = 0; i < zeroes; i++) {
            if (byteIdx >= nal.length) return 0;
            int bit = (nal[byteIdx] >> (7 - bitPos)) & 1;
            bitPos++;
            if (bitPos == 8) { bitPos = 0; byteIdx++; }
            val += bit << (zeroes - 1 - i);
        }
        return (int) val;
    }

    private static int indexOf(byte[] data, byte[] pat, int from) {
        outer:
        for (int i = from; i + pat.length <= data.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void writeChunk(OutputStream out, byte[] data) throws Exception {
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes("ISO-8859-1"));
        out.write(data);
        out.write("\r\n".getBytes("ISO-8859-1"));
    }

    private static void writeBusy(Socket s) {
        try {
            OutputStream out = s.getOutputStream();
            String body = "h264fast unavailable: 另一路推流进行中";
            out.write(("HTTP/1.1 503 Busy\r\nContent-Length: " + body.getBytes("UTF-8").length
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + body).getBytes("ISO-8859-1"));
        } catch (Exception ignored) {}
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void dbg(Context ctx, String msg) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "h264fast.log");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            fo.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {}
    }
}
