package com.magneo.compass.cam;

import com.magneo.compass.web.Fmp4Muxer;

import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** 摄像头网页流：H.264 -> fMP4 -> chunked HTTP，浏览器 MSE 播放（设备 WebRTC 硬编不兼容时的可靠方案）。 */
public class CameraHttpStreamer {
    public static class Frame {
        public final byte[] data;      // AVCC 格式（4 字节长度 + NAL）
        public final long ptsUs;
        public final boolean key;
        public Frame(byte[] data, long ptsUs, boolean key) { this.data = data; this.ptsUs = ptsUs; this.key = key; }
    }

    private static volatile CameraHttpStreamer inst;
    private final List<BlockingQueue<Frame>> clients = new CopyOnWriteArrayList<>();
    private volatile byte[] initData;
    private volatile long lastPtsUs;
    // 攒帧缓冲：同 pts 的 NAL（SPS/PPS/IDR）合并为一个 sample，避免参数集被当视频帧
    private volatile long curPts = -1;
    private volatile java.io.ByteArrayOutputStream curAvcc;
    private volatile boolean curKey;

    public static CameraHttpStreamer get() {
        if (inst == null) inst = new CameraHttpStreamer();
        return inst;
    }

    public void setSpsPps(int w, int h, byte[] sps, byte[] pps) {
        try {
            initData = new Fmp4Muxer().init(w, h, sps, pps);
        } catch (Exception ignored) {}
    }

    /** 编码器帧回调：裸 NAL 按 pts 聚合为一帧 AVCC，广播到客户端。 */
    public void feed(byte[] nal, long ptsUs, boolean key) {
        if (initData == null) return;
        synchronized (this) {
            if (ptsUs != curPts) {
                flushFrameLocked();
                curPts = ptsUs;
                curKey = key;
                curAvcc = new java.io.ByteArrayOutputStream();
            }
            if (curAvcc == null) return;
            curAvcc.write((nal.length >>> 24) & 0xFF);
            curAvcc.write((nal.length >>> 16) & 0xFF);
            curAvcc.write((nal.length >>> 8) & 0xFF);
            curAvcc.write(nal.length & 0xFF);
            curAvcc.write(nal, 0, nal.length);
        }
    }

    private synchronized void flushFrameLocked() {
        if (curAvcc == null) return;
        byte[] data = curAvcc.toByteArray();
        long pts = curPts;
        boolean key = curKey;
        curAvcc = null;
        curPts = -1;
        Frame f = new Frame(data, pts, key);
        for (BlockingQueue<Frame> q : clients) {
            if (!q.offer(f)) { q.poll(); q.offer(f); }
        }
    }

    /** 处理一个 HTTP 连接：发 chunked fMP4。 */
    public static void serve(Socket s) {
        CameraHttpStreamer self = get();
        BlockingQueue<Frame> q = new ArrayBlockingQueue<>(12);
        self.clients.add(q);
        try {
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            out.flush();
            // 等 init 就绪（最多 5 秒）
            byte[] init = self.initData;
            long wait = System.currentTimeMillis();
            while (init == null && System.currentTimeMillis() - wait < 5000) {
                Thread.sleep(100);
                init = self.initData;
            }
            if (init == null) return;
            writeChunk(out, init);
            Fmp4Muxer muxer = new Fmp4Muxer();
            long lastPts = 0;
            boolean gotKey = false;
            while (true) {
                Frame f = q.poll(800, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) break;
                if (!gotKey && !f.key) continue;   // 必须从关键帧开始
                if (f.key) gotKey = true;
                long dur = f.ptsUs - lastPts;
                if (dur <= 0) dur = 33000;
                lastPts = f.ptsUs;
                byte[] frag = muxer.fragment(f.ptsUs, dur, f.key, f.data);
                writeChunk(out, frag);
            }
        } catch (Exception ignored) {
        } finally {
            self.clients.remove(q);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeChunk(OutputStream out, byte[] data) throws Exception {
        out.write((Integer.toHexString(data.length) + "\r\n").getBytes("ISO-8859-1"));
        out.write(data);
        out.write("\r\n".getBytes("ISO-8859-1"));
        out.flush();
    }

    public void clear() {
        initData = null;
        clients.clear();
    }
}
