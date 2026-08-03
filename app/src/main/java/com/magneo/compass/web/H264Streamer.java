package com.magneo.compass.web;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * H.264 硬编推流：root screencap 采集 800x800 -> 采样转 720x720 NV12 ->
 * MediaCodec（MTK 硬件 AVC 编码器）-> fMP4 片段 -> chunked HTTP，浏览器 MSE 播放。
 */
public class H264Streamer {
    private static final String TAG = "H264Streamer";
    private static final int SW = 800, SH = 800;          // 采集尺寸
    private static final int EW = 720, EH = 720;          // 编码尺寸（MTK AVC 最大 1280x720）
    private static final int FRAME_LEN = 12 + SW * SH * 4;

    private static volatile boolean active;
    private static volatile int chosenFmt = -1;
    private static volatile byte[] init;
    private static volatile boolean nativeOk;

    static {
        try {
            System.loadLibrary("truthyuv");
            nativeOk = true;
        } catch (Throwable t) {
            nativeOk = false;
        }
    }

    private static native void rgbaToYuv(byte[] src, int sw, int sh, byte[] dst, int ew, int eh, int planar);

    private H264Streamer() {}

    public static String state() { return active ? "recording" : "idle"; }
    public static boolean isActive() { return active; }

    /** 处理 /h264 连接：拉起 root 采集、硬编、推 fMP4；断开即停。 */
    public static void serve(Socket s, Context ctx) {
        synchronized (H264Streamer.class) {
            if (active || ScreenStreamer.isActive() || H264SurfaceStreamer.isActive()) { writeBusy(s, "busy"); return; }
            active = true;
        }
        ScreenAwake.on(ctx);                           // 推流期间屏幕常亮，防息屏黑屏
        Process shell = null;
        MediaCodec codec = null;
        InputStream in = null;
        try {
            dbg(ctx, "serve enter");
            shell = FifoCapture.start(ctx);
            dbg(ctx, "fifo spawned");
            in = FifoCapture.openReader(ctx);
            dbg(ctx, "reader open");

            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            out.flush();
            dbg(ctx, "head written");

            codec = createEncoder(ctx);
            dbg(ctx, "encoder started: " + ci2name(codec));
            run(codec, s, out, ctx, in);
            dbg(ctx, "run finished");
        } catch (Throwable t) {
            Log.w(TAG, "serve", t);
            try { dbg(ctx, "serve error: " + t); } catch (Exception ignored) {}
            try { s.getOutputStream().write(("0\r\n\r\n").getBytes("ISO-8859-1")); } catch (Exception ignored) {}
        } finally {
            active = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            FifoCapture.stop(ctx, shell);
            ScreenAwake.off();                         // 恢复屏幕自动休眠
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static MediaCodec createEncoder(Context ctx) throws Exception {
        MediaCodec c;
        try {
            c = MediaCodec.createByCodecName("OMX.MTK.VIDEO.ENCODER.AVC");
        } catch (Exception e) {
            c = MediaCodec.createEncoderByType("video/avc");
        }
        MediaCodecInfo ci = c.getCodecInfo();
        MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType("video/avc");
        int fmt = -1;
        for (int cf : caps.colorFormats) {
            if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { fmt = cf; break; }
            if (fmt < 0 && cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) fmt = cf;
        }
        if (fmt < 0) throw new RuntimeException("no yuv420 format");
        chosenFmt = fmt;
        int kbps = clamp(Prefs.getI(ctx, Prefs.K_STREAM_BITRATE, 1500), 300, 8000);
        MediaFormat mf = MediaFormat.createVideoFormat("video/avc", EW, EH);
        mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, fmt);
        mf.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        mf.setInteger(MediaFormat.KEY_FRAME_RATE, 2);
        mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        c.start();
        dbg(ctx, "encoder " + ci.getName() + " fmt=" + fmt + " " + EW + "x" + EH + " " + kbps + "kbps");
        return c;
    }

    private static String ci2name(MediaCodec c) {
        try { return c.getCodecInfo().getName(); } catch (Exception e) { return "?"; }
    }

    private static void run(MediaCodec codec, Socket s, OutputStream out, Context ctx, InputStream in) throws Exception {
        Fmp4Muxer mux = new Fmp4Muxer();
        byte[] buf = new byte[FRAME_LEN];
        byte[] nv12 = new byte[EW * EH * 3 / 2];
        byte[] sps = new byte[64], pps = new byte[64];
        init = null;
        long base = System.currentTimeMillis();
        long lastPushed = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!Thread.currentThread().isInterrupted()) {
            int fps = clamp(Prefs.getI(ctx, Prefs.K_STREAM_FPS, 1), 1, 10);
            long durUs = 1_000_000L / fps;
            long interval = 1000L / fps;
            long now = System.currentTimeMillis();
            long wait = interval - (now - lastPushed);
            if (wait > 0) { Thread.sleep(wait); continue; }
            lastPushed = now;

            if (!FifoCapture.readFrame(in, buf)) break; // 写端退出/EOF
            int w = (buf[0] & 0xff) | ((buf[1] & 0xff) << 8) | ((buf[2] & 0xff) << 16) | ((buf[3] & 0xff) << 24);
            int h = (buf[4] & 0xff) | ((buf[5] & 0xff) << 8) | ((buf[6] & 0xff) << 16) | ((buf[7] & 0xff) << 24);
            int fmt = (buf[8] & 0xff) | ((buf[9] & 0xff) << 8) | ((buf[10] & 0xff) << 16) | ((buf[11] & 0xff) << 24);
            if (w != SW || h != SH || fmt != 1) continue;

            long tConv = System.currentTimeMillis();
            if (nativeOk) {
                rgbaToYuv(buf, SW, SH, nv12, EW, EH,
                        chosenFmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ? 0 : 1);
            } else {
                rgbaToNv12(buf, nv12);
            }
            tConv = System.currentTimeMillis() - tConv;

            int inIdx = codec.dequeueInputBuffer(300000);
            if (inIdx >= 0) {
                ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                inBuf.clear();
                inBuf.put(nv12);
                long ptsUs = (System.currentTimeMillis() - base) * 1000L;
                codec.queueInputBuffer(inIdx, 0, nv12.length, ptsUs, 0);
            }

            drainOutput(codec, out, ctx, mux, info, sps, pps, durUs);
            // 无新帧时也持续排空输出，避免编码器输出队列积压阻塞输入
            for (int k = 0; k < 3; k++) {
                if (!drainOutput(codec, out, ctx, mux, info, sps, pps, durUs)) break;
            }
        }
    }

    private static boolean drainOutput(MediaCodec codec, OutputStream out, Context ctx, Fmp4Muxer mux,
                                       MediaCodec.BufferInfo info, byte[] sps, byte[] pps, long durUs) throws Exception {
        try {
        int outIdx = codec.dequeueOutputBuffer(info, 2000);
        if (outIdx < 0) return false;
        ByteBuffer ob = codec.getOutputBuffer(outIdx);
        byte[] data = new byte[info.size];
        ob.get(data);
        codec.releaseOutputBuffer(outIdx, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parseConfig(data, sps, pps);
            return true;
        }
        byte[] avcc = Fmp4Muxer.toAvcc(data, sps, pps);
        if (avcc.length == 0) return true;
        if (sps[0] == 0) return true; // 尚无 SPS，等关键帧
        if (init == null) {
            init = mux.init(EW, EH, trim(sps), trim(pps));
            writeChunk(out, init);
            out.flush();
            dbg(ctx, "init sent " + init.length + "B");
        }
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        byte[] frag = mux.fragment(info.presentationTimeUs, durUs, key, avcc);
        writeChunk(out, frag);
        out.flush();
        return true;
        } catch (Throwable t) {
            dbg(ctx, "drain error: " + t);
            throw t;
        }
    }

    private static int[] rgbBuf;

    /** RGBA(800x800) -> 720x720 YUV420：int[] 采样（快路径）后计算 YUV，按编码器格式输出 Planar/SemiPlanar。 */
    private static void rgbaToNv12(byte[] src, byte[] dst) {
        int yLen = EW * EH;
        if (rgbBuf == null) rgbBuf = new int[yLen];
        int k = 0;
        for (int y = 0; y < EH; y++) {
            int sy = (y * SH) / EH;
            int row = sy * SW * 4 + 12;
            for (int x = 0; x < EW; x++, k++) {
                int sx = (x * SW) / EW;
                int j = row + sx * 4;
                rgbBuf[k] = (src[j] & 0xff) | ((src[j + 1] & 0xff) << 8) | ((src[j + 2] & 0xff) << 16);
            }
        }
        int p = 0;
        for (int i = 0; i < yLen; i++) {
            int c = rgbBuf[i];
            int r = c & 0xff, g = (c >>> 8) & 0xff, b = (c >>> 16) & 0xff;
            int yv = (r * 19595 + g * 38470 + b * 7471) >> 16;
            if (yv < 0) yv = 0; else if (yv > 255) yv = 255;
            dst[p++] = (byte) yv;
        }
        boolean semi = chosenFmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        int uPos = yLen;
        int vPos = semi ? -1 : yLen + (yLen >> 2);
        for (int y = 0; y < EH; y += 2) {
            int r1 = y * EW, r2 = (y + 1) * EW;
            for (int x = 0; x < EW; x += 2) {
                int c1 = rgbBuf[r1 + x], c2 = rgbBuf[r1 + x + 1], c3 = rgbBuf[r2 + x], c4 = rgbBuf[r2 + x + 1];
                int r = (c1 & 0xff) + (c2 & 0xff) + (c3 & 0xff) + (c4 & 0xff);
                int g = ((c1 >>> 8) & 0xff) + ((c2 >>> 8) & 0xff) + ((c3 >>> 8) & 0xff) + ((c4 >>> 8) & 0xff);
                int b = ((c1 >>> 16) & 0xff) + ((c2 >>> 16) & 0xff) + ((c3 >>> 16) & 0xff) + ((c4 >>> 16) & 0xff);
                r >>= 2; g >>= 2; b >>= 2;
                int u = (-r * 100 - g * 208 + b * 308 + 32768) >> 8;
                int v = (r * 308 - g * 261 - b * 47 + 32768) >> 8;
                if (u < 0) u = 0; else if (u > 255) u = 255;
                if (v < 0) v = 0; else if (v > 255) v = 255;
                if (semi) {
                    dst[uPos++] = (byte) u;
                    dst[uPos++] = (byte) v;
                } else {
                    dst[uPos++] = (byte) u;
                    dst[vPos++] = (byte) v;
                }
            }
        }
    }

    private static void parseConfig(byte[] data, byte[] sps, byte[] pps) {
        Fmp4Muxer.toAvcc(data, sps, pps);
    }

    private static byte[] trim(byte[] a) {
        int n = a.length;
        while (n > 0 && a[n - 1] == 0) n--;
        return java.util.Arrays.copyOf(a, n);
    }

    private static void writeChunk(OutputStream out, byte[] data) throws Exception {
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes("ISO-8859-1"));
        out.write(data);
        out.write("\r\n".getBytes("ISO-8859-1"));
    }

    private static void dbg(Context ctx, String msg) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "h264_debug.log");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true);
            fo.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {}
    }

    private static void writeBusy(Socket s, String reason) {
        try {
            OutputStream out = s.getOutputStream();
            String body = "h264 unavailable: " + reason;
            out.write(("HTTP/1.1 503 " + reason + "\r\nContent-Length: " + body.getBytes("UTF-8").length
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + body).getBytes("ISO-8859-1"));
        } catch (Exception ignored) {}
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
