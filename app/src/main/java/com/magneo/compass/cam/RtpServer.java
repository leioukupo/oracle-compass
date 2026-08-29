package com.magneo.compass.cam;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** RTSP 服务器：VLC/ffplay 等可用 rtsp://IP:端口/cam 观看 H.264 摄像头画面。 */
public class RtpServer {
    private static final String TAG = "RtpServer";
    private static final int RTP_PT = 96;
    private static final int MAX_QUEUE = 15;

    public static class Frame {
        public final byte[] nal;
        public final long ptsUs;
        public Frame(byte[] nal, long ptsUs) { this.nal = nal; this.ptsUs = ptsUs; }
    }

    private final int port;
    private final List<Client> clients = new ArrayList<>();
    private final Object clientsLock = new Object();
    private volatile byte[] sps, pps;
    private ServerSocket server;
    private Thread acceptThread;

    public RtpServer(int port) { this.port = port; }

    public synchronized void start() throws Exception {
        if (server != null) return;
        server = new ServerSocket(port);
        acceptThread = new Thread(this::acceptLoop, "rtsp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void setSpsPps(byte[] sps, byte[] pps) { this.sps = sps; this.pps = pps; }

    /** 编码器帧回调：广播到所有客户端。 */
    public void feed(byte[] nal, long ptsUs) {
        Frame f = new Frame(nal, ptsUs);
        synchronized (clientsLock) {
            for (Client c : clients) c.offer(f);
        }
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                Client c = new Client(s);
                synchronized (clientsLock) { clients.add(c); }
                Thread t = new Thread(c::run, "rtsp-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception ignored) {}
        }
    }

    private class Client {
        final Socket sock;
        final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
        volatile DatagramSocket rtpSock;
        volatile InetAddress clientAddr;
        volatile int clientRtpPort = -1;
        volatile boolean playing = false;
        volatile long lastSeq = 0;
        volatile boolean tcpMode = false;      // RTSP over TCP (interleaved)
        volatile int tcpChannel = 0;

        Client(Socket s) {
            sock = s;
            try { s.setTcpNoDelay(true); } catch (Exception ignored) {}   // RTSP TCP 低延迟
        }

        void offer(Frame f) {
            if (!queue.offer(f)) { queue.poll(); queue.offer(f); }
        }

        void run() {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ISO-8859-1"));
                OutputStream w = sock.getOutputStream();
                String session = "cam" + (System.currentTimeMillis() % 100000);
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    String h;
                    while ((h = r.readLine()) != null && !h.trim().isEmpty()) {
                        int c = h.indexOf(':');
                        if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(), h.substring(c + 1).trim());
                    }
                    int cseq = 0;
                    try { cseq = Integer.parseInt(headers.get("cseq")); } catch (Exception ignored) {}
                    if (line.startsWith("OPTIONS ")) {
                        reply(w, cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n", "");
                    } else if (line.startsWith("DESCRIBE ")) {
                        reply(w, cseq, "Content-Type: application/sdp\r\n", sdp());
                    } else if (line.startsWith("SETUP ")) {
                        String tr = headers.get("transport");
                        if (tr != null && tr.toLowerCase().contains("interleaved")) {
                            tcpMode = true;
                            try {
                                String iv = tr.toLowerCase();
                                int a = iv.indexOf("interleaved=");
                                String rng = iv.substring(a + "interleaved=".length()).split(";")[0];
                                tcpChannel = Integer.parseInt(rng.split("-")[0].trim());
                            } catch (Exception ignored) {}
                            reply(w, cseq, "Session: " + session + "\r\nTransport: RTP/AVP/TCP;unicast;interleaved="
                                    + tcpChannel + "-" + (tcpChannel + 1) + "\r\n", "");
                        } else {
                            setup(tr);
                            reply(w, cseq, "Session: " + session + "\r\nTransport: RTP/AVP;unicast;client_port="
                                    + clientRtpPort + "-" + (clientRtpPort + 1) + ";server_port="
                                    + rtpSock.getLocalPort() + "-" + (rtpSock.getLocalPort() + 1) + "\r\n", "");
                        }
                    } else if (line.startsWith("PLAY ")) {
                        if (!playing) {
                            playing = true;
                            Thread t = new Thread(this::sendLoop, "rtp-send");
                            t.setDaemon(true);
                            t.start();
                        }
                        reply(w, cseq, "Session: " + session + "\r\n", "");
                    } else if (line.startsWith("TEARDOWN ")) {
                        reply(w, cseq, "", "");
                        break;
                    } else if (line.startsWith("GET_PARAMETER") || line.startsWith("SET_PARAMETER")) {
                        reply(w, cseq, "Session: " + session + "\r\n", "");
                    }
                }
            } catch (Exception ignored) {
            } finally {
                playing = false;
                closeSock(rtpSock);
                try { sock.close(); } catch (Exception ignored) {}
                synchronized (clientsLock) { clients.remove(this); }
            }
        }

        private void setup(String transport) throws Exception {
            int from = transport == null ? -1 : transport.indexOf("client_port=");
            if (from >= 0) {
                String p = transport.substring(from + "client_port=".length());
                int dash = p.indexOf('-');
                if (dash > 0) clientRtpPort = Integer.parseInt(p.substring(0, dash).trim());
            }
            if (clientRtpPort <= 0) throw new Exception("no client_port");
            clientAddr = sock.getInetAddress();
            rtpSock = new DatagramSocket();
            rtpSock.setSoTimeout(0);
        }

        private boolean socketAlive() {
            if (tcpMode) return sock != null && !sock.isClosed();
            return rtpSock != null && !rtpSock.isClosed();
        }

        void sendLoop() {
            while (playing && socketAlive()) {
                try {
                    Frame f = queue.take();
                    long ts = f.ptsUs * 90 / 1000;   // us -> 90kHz
                    if (ts <= 0) ts = System.nanoTime() / 1000 * 90 / 1000;
                    int ts32 = (int) (ts & 0xFFFFFFFFL);
                    sendNal(f.nal, ts32, true);
                } catch (Exception ignored) { break; }
            }
        }

        /** 打包发送一个 NAL（FU-A 分片 / 单包）。 */
        void sendNal(byte[] nal, int ts, boolean mark) {
            try {
                int type = nal[0] & 0x1F;
                int n = nal.length;
                if (n <= 1400) {
                    // 单 NAL 包
                    byte[] pkt = new byte[12 + n];
                    fillHeader(pkt, ts, ++lastSeq, mark);
                    System.arraycopy(nal, 0, pkt, 12, n);
                    send(pkt);
                } else {
                    // FU-A
                    int fuIndicator = (nal[0] & 0xE0) | 28;
                    int fuHeader0 = (nal[0] & 0x1F) | 0x80;   // S=1
                    int off = 1;
                    while (off < n) {
                        int len = Math.min(1400, n - off);
                        boolean last = off + len >= n;
                        byte[] pkt = new byte[12 + 2 + len];
                        fillHeader(pkt, ts, ++lastSeq, last);
                        pkt[12] = (byte) fuIndicator;
                        pkt[13] = (byte) (last ? fuHeader0 | 0x40 : fuHeader0);
                        System.arraycopy(nal, off, pkt, 14, len);
                        send(pkt);
                        fuHeader0 &= ~0x80;   // 后续分片 S=0
                        off += len;
                    }
                }
            } catch (Exception ignored) {}
        }

        private void fillHeader(byte[] pkt, int ts, long seq, boolean mark) {
            pkt[0] = (byte) 0x80;                       // V=2
            if (mark) pkt[1] = (byte) (0x80 | RTP_PT);  // M=1
            else pkt[1] = (byte) RTP_PT;
            pkt[2] = (byte) ((seq >> 8) & 0xFF);
            pkt[3] = (byte) (seq & 0xFF);
            pkt[4] = (byte) ((ts >> 24) & 0xFF);
            pkt[5] = (byte) ((ts >> 16) & 0xFF);
            pkt[6] = (byte) ((ts >> 8) & 0xFF);
            pkt[7] = (byte) (ts & 0xFF);
            long ssrc = 0x31415926L;
            pkt[8] = (byte) ((ssrc >> 24) & 0xFF);
            pkt[9] = (byte) ((ssrc >> 16) & 0xFF);
            pkt[10] = (byte) ((ssrc >> 8) & 0xFF);
            pkt[11] = (byte) (ssrc & 0xFF);
        }

        private void send(byte[] pkt) {
            try {
                if (tcpMode) {
                    synchronized (sock) {
                        OutputStream w = sock.getOutputStream();
                        w.write(0x24);                       // '$'
                        w.write((byte) tcpChannel);
                        w.write((byte) ((pkt.length >> 8) & 0xFF));
                        w.write((byte) (pkt.length & 0xFF));
                        w.write(pkt);
                        w.flush();
                    }
                } else {
                    rtpSock.send(new DatagramPacket(pkt, pkt.length, clientAddr, clientRtpPort));
                }
            } catch (Exception ignored) {}
        }

        private String sdp() {
            String sprop = "";
            if (sps != null && pps != null) {
                sprop = "a=fmtp:" + RTP_PT + " packetization-mode=1;profile-level-id=" + profileLevelId()
                        + ";sprop-parameter-sets=" + Base64.encodeToString(sps, Base64.NO_WRAP) + ","
                        + Base64.encodeToString(pps, Base64.NO_WRAP) + "\r\n";
            }
            return "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=cam\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n"
                    + "m=video 0 RTP/AVP " + RTP_PT + "\r\n"
                    + "a=rtpmap:" + RTP_PT + " H264/90000\r\n" + sprop
                    + "a=control:track1\r\n";
        }

        private String profileLevelId() {
            // SPS bytes 1,2,3 = profile_idc, constraint, level
            if (sps == null || sps.length < 4) return "42E01F";
            return String.format("%02X%02X%02X", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
        }
    }

    private static int readCseq(BufferedReader r) throws Exception {
        String h;
        while ((h = r.readLine()) != null && !h.trim().isEmpty()) {
            if (h.toLowerCase().startsWith("cseq:")) {
                try { return Integer.parseInt(h.substring(5).trim()); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static String readHeader(BufferedReader r, String name) throws Exception {
        String h;
        while ((h = r.readLine()) != null && !h.trim().isEmpty()) {
            if (h.toLowerCase().startsWith(name.toLowerCase() + ":")) return h.substring(name.length() + 1).trim();
        }
        return null;
    }

    private static void reply(OutputStream w, int cseq, String extraHeaders, String body) throws Exception {
        StringBuilder sb = new StringBuilder("RTSP/1.0 200 OK\r\nCSeq: ").append(cseq).append("\r\n");
        if (extraHeaders != null && !extraHeaders.isEmpty()) sb.append(extraHeaders);
        if (body != null && !body.isEmpty()) {
            sb.append("Content-Length: ").append(body.getBytes("ISO-8859-1").length).append("\r\n");
        }
        sb.append("\r\n");
        if (body != null && !body.isEmpty()) sb.append(body);
        w.write(sb.toString().getBytes("ISO-8859-1"));
        w.flush();
    }

    private static void closeSock(DatagramSocket s) {
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    public boolean isRunning() { return server != null && !server.isClosed(); }

    /** 已完成 PLAY 的 RTSP 客户端数；仅这些客户端需要编码帧。 */
    public int playingClientCount() {
        synchronized (clientsLock) {
            int count = 0;
            for (Client c : clients) if (c.playing) count++;
            return count;
        }
    }

    public void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        synchronized (clientsLock) {
            for (Client c : clients) {
                c.playing = false;
                closeSock(c.rtpSock);
                try { c.sock.close(); } catch (Exception ignored) {}
            }
            clients.clear();
        }
    }
}
