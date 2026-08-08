package com.magneo.compass.cam;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** RTMP 推流客户端（H.264 live，无音频）：推给 SRS / nginx-rtmp / 直播平台等。 */
public class RtmpPusher {
    private static final String TAG = "Rtmp";
    private static final int MAX_QUEUE = 60;

    private final String host;
    private final int port;
    private final String app;
    private final String stream;
    private volatile Socket sock;
    private volatile OutputStream out;
    private volatile InputStream in;
    private volatile boolean running = false;
    private volatile byte[] sps, pps;
    private volatile int streamId = 1;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private long startMs = 0;
    private int chunkSize = 128;
    private String lastError = "";

    public RtmpPusher(String url) throws Exception {
        String u = url.trim();
        if (!u.startsWith("rtmp://")) throw new Exception("RTMP 地址需以 rtmp:// 开头");
        String rest = u.substring(7);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash + 1);
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            host = hostPort;
            port = 1935;
        }
        int slash2 = path.indexOf('/');
        app = slash2 < 0 ? path : path.substring(0, slash2);
        stream = slash2 < 0 ? "" : path.substring(slash2 + 1);
        if (stream.isEmpty()) throw new Exception("RTMP 地址缺少流名：rtmp://host/app/stream");
    }

    public void setSpsPps(byte[] sps, byte[] pps) { this.sps = sps; this.pps = pps; }

    public void start() throws Exception {
        stop();
        running = true;
        sock = new Socket(host, port);
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(10000);
        out = new BufferedOutputStream(sock.getOutputStream());
        in = new BufferedInputStream(sock.getInputStream());
        handshake();
        connect();
        Thread t = new Thread(this::readLoop, "rtmp-read");
        t.setDaemon(true);
        t.start();
        createStream();
        publish();
        Thread sender = new Thread(this::sendLoop, "rtmp-send");
        sender.setDaemon(true);
        sender.start();
    }

    public String lastError() { return lastError; }
    public boolean isRunning() { return running && sock != null && !sock.isClosed(); }

    private void handshake() throws Exception {
        byte[] c1 = new byte[1536];
        new java.util.Random().nextBytes(c1);
        System.arraycopy(new byte[]{0, 0, 0, 0}, 0, c1, 0, 4);   // time=0
        out.write(0x03);
        out.write(c1);
        out.flush();
        int s0 = in.read();
        if (s0 != 0x03) throw new Exception("RTMP 握手失败 S0=" + s0);
        byte[] s1 = new byte[1536];
        readFully(in, s1);
        byte[] s2 = new byte[1536];
        readFully(in, s2);
        out.write(s1);   // C2 = echo S1
        out.flush();
    }

    private void connect() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeAmfString(d, "connect");
        writeAmfNumber(d, 1.0);
        writeAmfNull(d);
        d.write(0x03);   // object
        writeAmfString(d, "app"); writeAmfString(d, app);
        writeAmfString(d, "tcUrl"); writeAmfString(d, "rtmp://" + host + ":" + port + "/" + app);
        writeAmfBoolean(d, false);   // fpad
        writeAmfNumber(d, 15.0);     // capabilities
        writeAmfString(d, "type"); writeAmfString(d, "nonprivate");
        writeAmfString(d, "flashVer"); writeAmfString(d, "FMLE/3.0 (compatible; compass)");
        d.writeByte(0);
        d.writeByte(0);
        d.writeByte(0x09);   // object end
        sendCommand(3, 1, b.toByteArray());
    }

    private void createStream() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeAmfString(d, "createStream");
        writeAmfNumber(d, 2.0);
        writeAmfNull(d);
        sendCommand(3, 0, b.toByteArray());
    }

    private void publish() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        writeAmfString(d, "publish");
        writeAmfNumber(d, 0.0);
        writeAmfNull(d);
        writeAmfString(d, stream);
        writeAmfString(d, "live");
        sendCommand(3, streamId, b.toByteArray());
    }

    // ---------- 消息发送 ----------
    private void sendCommand(int csid, int sid, byte[] data) throws Exception {
        sendChunked(csid, 20, sid, data);
    }

    private void sendChunked(int csid, int type, int sid, byte[] data) throws Exception {
        synchronized (out) {
            int ts = 0;
            out.write((byte) csid);                       // fmt=0 basic header
            write3(out, ts);                              // timestamp
            write3(out, data.length);                     // message length
            out.write(type);                              // message type
            writeLE32(out, sid);                          // stream id
            int off = 0;
            boolean first = true;
            while (off < data.length || (data.length == 0 && first)) {
                if (!first) out.write((byte) csid);       // fmt=3 basic header
                first = false;
                int n = Math.min(chunkSize, data.length - off);
                out.write(data, off, n);
                off += n;
                if (off >= data.length) break;
            }
            out.flush();
        }
    }

    // ---------- 接收循环：处理 _result / set chunk size ----------
    private void readLoop() {
        try {
            while (running && sock != null) {
                int b0 = in.read();
                if (b0 < 0) break;
                int fmt = (b0 >> 6) & 3;
                int csid = b0 & 0x3F;
                int ts = 0, len = 0, type = 0, sid = 0;
                if (fmt == 0) {
                    ts = read3(in);
                    len = read3(in);
                    type = in.read();
                    sid = readLE32(in);
                } else {
                    // fmt=1/2 简化：不完整支持；大多数服务器用 fmt=0 发送首 chunk
                    if (fmt == 1) { ts = read3(in); len = read3(in); type = in.read(); }
                    else if (fmt == 2) { ts = read3(in); }
                    else { /* fmt=3 续传，需要跟踪上一消息 */ }
                }
                byte[] body = new byte[len];
                readFully(in, body);
                if (type == 20) handleCommand(body);
                else if (type == 5) { /* window ack size */ }
                else if (type == 6) { /* set peer bw */ }
                else if (type == 1) { chunkSize = readBE32(body, 0); }
            }
        } catch (Exception e) {
            lastError = "RTMP 连接中断: " + e.getMessage();
        }
    }

    private void handleCommand(byte[] body) {
        try {
            AmfCursor c = new AmfCursor(body);
            String name = c.readString();
            double txn = c.readNumber();
            if ("_result".equals(name) && txn == 2.0) {
                c.skipValue();                 // properties object
                double id = c.readNumber();
                streamId = (int) id;
                if (streamId <= 0) streamId = 1;
            }
        } catch (Exception ignored) {}
    }

    // ---------- 媒体发送 ----------
    public void feed(byte[] nal, long ptsUs, boolean keyframe) {
        if (!running) return;
        if (sps == null || pps == null) return;
        try {
            byte[] tag = keyframe ? buildKeyTag(nal) : buildInterTag(nal, ptsUs);
            if (!queue.offer(tag)) { queue.poll(); queue.offer(tag); }
        } catch (Exception ignored) {}
    }

    private void sendLoop() {
        try {
            byte[] seq = buildAvcSequenceHeader();
            if (seq != null) sendMedia(seq, 0);
            startMs = System.currentTimeMillis();
            while (running) {
                byte[] tag = queue.poll(500, TimeUnit.MILLISECONDS);
                if (tag == null) continue;
                int ts = (int) (System.currentTimeMillis() - startMs);
                sendMedia(tag, ts);
            }
        } catch (Exception e) {
            lastError = "RTMP 发送失败: " + e.getMessage();
        }
    }

    private void sendMedia(byte[] flvTagData, int ts) throws Exception {
        // flvTagData = 11 字节 FLV tag 头 + payload
        byte[] header = Arrays.copyOf(flvTagData, 11);
        byte[] payload = Arrays.copyOfRange(flvTagData, 11, flvTagData.length);
        header[4] = (byte) (ts & 0xFF);        // timestamp 3+1
        header[3] = (byte) ((ts >> 8) & 0xFF);
        header[2] = (byte) ((ts >> 16) & 0xFF);
        header[7] = (byte) ((ts >> 24) & 0xFF);
        synchronized (out) {
            out.write((byte) 0x06);            // csid 6 fmt=0
            write3(out, 0);
            write3(out, payload.length);
            out.write(9);                      // video
            writeLE32(out, streamId);
            int off = 0;
            boolean first = true;
            while (off < payload.length) {
                if (!first) out.write((byte) 0x06);
                first = false;
                int n = Math.min(chunkSize, payload.length - off);
                out.write(payload, off, n);
                off += n;
            }
            out.flush();
        }
    }

    private byte[] buildKeyTag(byte[] nal) throws Exception {
        // 关键帧：SPS/PPS + IDR 一起发（前置 4 字节长度）
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x17); b.write(0x01); b.write(0); b.write(0); b.write(0);
        if (sps != null) { write4(b, sps.length); b.write(sps, 0, sps.length); }
        if (pps != null) { write4(b, pps.length); b.write(pps, 0, pps.length); }
        write4(b, nal.length); b.write(nal, 0, nal.length);
        return flvTag(9, 0, b.toByteArray());
    }

    private byte[] buildInterTag(byte[] nal, long ptsUs) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x27); b.write(0x01); b.write(0); b.write(0); b.write(0);
        write4(b, nal.length); b.write(nal, 0, nal.length);
        return flvTag(9, 0, b.toByteArray());
    }

    private byte[] buildAvcSequenceHeader() throws Exception {
        if (sps == null || pps == null || sps.length < 4 || pps.length < 4) return null;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x17); b.write(0x00); b.write(0); b.write(0); b.write(0);
        b.write(1);                        // configurationVersion
        b.write(sps[1]);                   // AVCProfileIndication
        b.write(sps[2]);                   // profile_compatibility
        b.write(sps[3]);                   // AVCLevelIndication
        b.write(0xFF);                     // lengthSizeMinusOne (4)
        b.write(0xE1);                     // numOfSPS
        write2(b, sps.length); b.write(sps, 0, sps.length);
        b.write(1);                        // numOfPPS
        write2(b, pps.length); b.write(pps, 0, pps.length);
        return flvTag(9, 0, b.toByteArray());
    }

    private byte[] flvTag(int type, int ts, byte[] payload) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(type);
        write3(b, payload.length);
        write3(b, ts);
        b.write(0);                        // timestamp ext
        b.write(0); b.write(0); b.write(0); // stream id
        b.write(payload, 0, payload.length);
        writeBE32(b, 11 + payload.length); // previous tag size
        return b.toByteArray();
    }

    public void stop() {
        running = false;
        queue.clear();
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
    }

    // ---------- 工具 ----------
    private static void writeAmfString(DataOutputStream d, String s) throws Exception {
        byte[] b = s.getBytes("UTF-8");
        d.write(0x02);
        d.writeShort(b.length);
        d.write(b);
    }

    private static void writeAmfNumber(DataOutputStream d, double v) throws Exception {
        d.write(0x00);
        d.writeLong(Double.doubleToLongBits(v));
    }

    private static void writeAmfBoolean(DataOutputStream d, boolean v) throws Exception {
        d.write(0x01);
        d.write(v ? 1 : 0);
    }

    private static void writeAmfNull(DataOutputStream d) { try { d.write(0x05); } catch (Exception ignored) {} }

    private static void write3(OutputStream o, int v) throws Exception {
        o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void writeLE32(OutputStream o, int v) throws Exception {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    private static void write2(ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 0xFF); b.write(v & 0xFF);
    }

    private static void write4(ByteArrayOutputStream b, int v) {
        b.write((v >> 24) & 0xFF); b.write((v >> 16) & 0xFF); b.write((v >> 8) & 0xFF); b.write(v & 0xFF);
    }

    private static void writeBE32(ByteArrayOutputStream b, int v) { write4(b, v); }

    private static int read3(InputStream in) throws Exception {
        return ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
    }

    private static int readLE32(InputStream in) throws Exception {
        return (in.read() & 0xFF) | ((in.read() & 0xFF) << 8) | ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 24);
    }

    private static int readBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off+1] & 0xFF) << 16) | ((b[off+2] & 0xFF) << 8) | (b[off+3] & 0xFF);
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new Exception("EOF");
            off += n;
        }
    }

    private static class AmfCursor {
        final byte[] b; int p = 0;
        AmfCursor(byte[] b) { this.b = b; }
        String readString() throws Exception {
            if (b[p++] != 0x02) throw new Exception("not string");
            int len = ((b[p] & 0xFF) << 8) | (b[p+1] & 0xFF); p += 2;
            String s = new String(b, p, len, "UTF-8"); p += len;
            return s;
        }
        double readNumber() throws Exception {
            if (b[p++] != 0x00) throw new Exception("not number");
            long bits = 0;
            for (int i = 0; i < 8; i++) bits = (bits << 8) | (b[p + i] & 0xFF);
            p += 8;
            return Double.longBitsToDouble(bits);
        }
        void skipValue() {
            if (p >= b.length) return;
            int t = b[p];
            if (t == 0x00) { p += 9; }
            else if (t == 0x01) { p += 2; }
            else if (t == 0x02) { int len = ((b[p+1] & 0xFF) << 8) | (b[p+2] & 0xFF); p += 3 + len; }
            else if (t == 0x03 || t == 0x08) {
                p++;
                if (t == 0x08) p += 4;   // ECMA array count
                while (p + 3 <= b.length) {
                    if (b[p] == 0 && b[p+1] == 0 && b[p+2] == 0x09) { p += 3; break; }
                    int len = ((b[p] & 0xFF) << 8) | (b[p+1] & 0xFF); p += 2 + len;
                    skipValue();
                }
            }
            else if (t == 0x05 || t == 0x06) p += 1;
        }
    }
}
