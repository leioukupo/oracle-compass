package com.magneo.compass.cam;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/** MediaCodec H.264 硬编码器：喂 NV12 帧，输出裸 NAL（含 SPS/PPS 回调）。 */
public class H264Encoder {
    public interface Listener {
        void onSpsPps(byte[] sps, byte[] pps);
        void onFrame(byte[] nal, long ptsUs, boolean keyframe);
    }

    private final MediaCodec codec;
    private final Listener listener;
    private final int w, h;
    private final int colorFormat;
    private byte[] conv;
    private volatile byte[] sps, pps;
    private static volatile String fmtDiag = "";

    public H264Encoder(int w, int h, int fps, int bitrate, Listener l) throws Exception {
        this.w = w;
        this.h = h;
        listener = l;
        MediaCodec c = null;
        int cf = -1;
        Exception last = null;
        // MTK 硬编有的只认 NV12(21)，有的只认 Planar(19)，逐个试
        for (int fmt : new int[]{21, 19, 2130708361}) {
            try {
                MediaFormat mf = MediaFormat.createVideoFormat("video/avc", w, h);
                mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, fmt);
                mf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                mf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                // MTK 5.1 组件对自定义 key 敏感：只设标准 key，避免 configure 失败
                MediaCodec cc = MediaCodec.createEncoderByType("video/avc");
                cc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                cc.start();
                c = cc;
                cf = fmt;
                break;
            } catch (Exception e) {
                last = e;
                try { if (c != null) c.release(); } catch (Exception ignored) {}
                c = null;
            }
        }
        if (c == null) throw last != null ? last : new Exception("无可用 H.264 编码格式");
        codec = c;
        colorFormat = cf;
        fmtDiag = "fmt=" + cf + " size=" + w + "x" + h;
        conv = new byte[w * h * 3 / 2];
        startThread();
    }

    private final java.util.concurrent.ArrayBlockingQueue<FrameBuf> inQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(4);
    private volatile Thread encThread;

    private static class FrameBuf {
        final byte[] data;
        final long ptsUs;
        FrameBuf(byte[] d, long p) { data = d; ptsUs = p; }
    }

    private void startThread() {
        encThread = new Thread(this::encodeLoop, "h264-enc");
        encThread.setDaemon(true);
        encThread.start();
    }

    /** 喂一帧 NV21（采集线程只入队，不阻塞）。 */
    public void feed(byte[] nv21, long ptsUs) {
        if (encThread == null) return;
        if (!inQueue.offer(new FrameBuf(nv21.clone(), ptsUs))) {
            inQueue.poll();
            inQueue.offer(new FrameBuf(nv21.clone(), ptsUs));
        }
    }

    private void encodeLoop() {
        try {
            while (encThread != null && !encThread.isInterrupted()) {
                FrameBuf fb = inQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (fb == null) continue;
                if (colorFormat == 21) nv21ToNv12(fb.data, conv, w, h);
                else nv21ToI420(fb.data, conv, w, h);
                int inIdx = codec.dequeueInputBuffer(20000);
                if (inIdx < 0) continue;
                ByteBuffer in = codec.getInputBuffer(inIdx);
                in.clear();
                if (conv.length > in.capacity()) {
                    // 输入缓冲不足（不应发生）：丢弃该帧避免错乱
                    codec.queueInputBuffer(inIdx, 0, 0, fb.ptsUs, 0);
                    continue;
                }
                in.put(conv);
                codec.queueInputBuffer(inIdx, 0, conv.length, fb.ptsUs, 0);
                drain();
            }
        } catch (Exception e) {
            android.util.Log.w("CamStream", "encode loop exit", e);
        }
    }

    static void nv21ToNv12(byte[] src, byte[] dst, int w, int h) {
        int ySize = w * h;
        System.arraycopy(src, 0, dst, 0, ySize);
        for (int i = ySize; i < ySize + ySize / 2; i += 2) {
            dst[i] = src[i + 1];
            dst[i + 1] = src[i];
        }
    }

    static void nv21ToI420(byte[] src, byte[] dst, int w, int h) {
        int ySize = w * h;
        int uv = ySize / 4;
        System.arraycopy(src, 0, dst, 0, ySize);
        for (int i = 0; i < uv; i++) {
            // NV21 交错布局为 VU（V 在偶数位，U 在奇数位）；I420 为 U 平面 + V 平面
            dst[ySize + i] = src[ySize + i * 2 + 1];        // U
            dst[ySize + uv + i] = src[ySize + i * 2];        // V
        }
    }

    private void drain() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            int outIdx = codec.dequeueOutputBuffer(bi, 0);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (outIdx < 0) break;
            try {
                ByteBuffer out = codec.getOutputBuffer(outIdx);
                byte[] data = new byte[bi.size];
                out.position(bi.offset);
                out.get(data);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    parseSpsPps(data);
                } else {
                    // 去掉 00 00 00 01 / 00 00 01 前缀，逐个 NAL 回调
                    emitNals(data, bi.presentationTimeUs, (bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                }
            } catch (Exception ignored) {} finally {
                codec.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    private void parseSpsPps(byte[] config) {
        try {
            java.util.List<byte[]> nals = splitNals(config);
            for (byte[] n : nals) {
                if (n.length < 1) continue;
                int t = n[0] & 0x1F;
                if (t == 7) sps = n;
                else if (t == 8) pps = n;
            }
            if (sps != null && pps != null && listener != null) listener.onSpsPps(sps, pps);
        } catch (Exception ignored) {}
    }

    private void emitNals(byte[] data, long ptsUs, boolean keyframe) {
        java.util.List<byte[]> nals = splitNals(data);
        for (byte[] n : nals) {
            if (listener != null && n.length > 0) listener.onFrame(n, ptsUs, keyframe);
        }
    }

    private static java.util.List<byte[]> splitNals(byte[] data) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int i = 0, n = data.length;
        while (i < n) {
            if (i + 3 < n && (data[i] & 0xFF) == 0 && (data[i+1] & 0xFF) == 0 && (data[i+2] & 0xFF) == 0 && (data[i+3] & 0xFF) == 1) {
                i += 4;
                int s = i;
                while (i < n) {
                    if (i + 3 < n && (data[i] & 0xFF) == 0 && (data[i+1] & 0xFF) == 0 && (data[i+2] & 0xFF) == 0 && (data[i+3] & 0xFF) == 1) break;
                    if (i + 2 < n && (data[i] & 0xFF) == 0 && (data[i+1] & 0xFF) == 0 && (data[i+2] & 0xFF) == 1) break;
                    i++;
                }
                if (i > s) out.add(java.util.Arrays.copyOfRange(data, s, i));
            } else i++;
        }
        return out;
    }

    public void requestKeyframe() {
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {}
    }

    public static String fmtDiag() { return fmtDiag; }
    public byte[] getSps() { return sps; }
    public byte[] getPps() { return pps; }

    public void release() {
        if (encThread != null) { encThread.interrupt(); encThread = null; }
        inQueue.clear();
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }
}
