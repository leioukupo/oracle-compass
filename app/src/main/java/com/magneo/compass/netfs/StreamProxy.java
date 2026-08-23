package com.magneo.compass.netfs;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 本地 HTTP 流代理：把 FTP/SMB/WebDAV 路径转成 http://127.0.0.1:18080 供播放器/查看器使用。 */
public class StreamProxy {
    private static final String TAG = "StreamProxy";
    public static final int PORT = 18080;
    private static volatile ServerSocket server;
    private static volatile Thread thread;
    private static volatile android.content.Context app;

    public static synchronized void ensure(android.content.Context c) {
        if (server != null) return;
        app = c.getApplicationContext();
        try {
            server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(StreamProxy::acceptLoop, "stream-proxy");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            Log.w(TAG, "proxy start failed", e);
        }
    }

    /** 生成可播放/可加载的本地 URL。 */
    public static String urlFor(String connId, String path) {
        return urlFor(connId, path, -1L, null);
    }

    public static String urlFor(String connId, String path, long size, String mime) {
        String encoded = Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
        StringBuilder sb = new StringBuilder("http://127.0.0.1:")
                .append(PORT).append("/").append(connId).append("/").append(encoded);
        if (size > 0) sb.append("?s=").append(size);
        if (mime != null && !mime.isEmpty()) sb.append(size > 0 ? "&" : "?").append("m=").append(mime);
        return sb.toString();
    }

    private static void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException ignored) {}
        }
    }

    private static void handle(Socket s) {
        NetFs fs = null;
        InputStream in = null;
        try {
            s.setSoTimeout(600000);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String reqLine = r.readLine();
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            String query = null;
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            long skip = 0;
            long rangeEnd = -1;
            boolean partial = false;
            long size = -1;
            String mime = "application/octet-stream";
            if (query != null) {
                size = parseLong(param(query, "s"), -1);
                String m = param(query, "m");
                if (m != null && !m.isEmpty()) mime = m;
            }
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    try {
                        String rg = line.substring(line.indexOf('=') + 1).trim(); // bytes=start-
                        int dash = rg.indexOf('-');
                        String start = dash >= 0 ? rg.substring(0, dash).trim() : rg.trim();
                        String end = dash >= 0 ? rg.substring(dash + 1).trim() : "";
                        skip = parseLong(start, 0);
                        rangeEnd = end.isEmpty() ? -1 : parseLong(end, -1);
                        partial = true;
                    } catch (Exception ignored) {}
                }
            }
            String[] seg = path.substring(1).split("/", 2);
            if (seg.length < 2) { write404(s); return; }
            String connId = seg[0];
            String remotePath = new String(Base64.decode(seg[1], Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
            FsManager.Conn conn = FsManager.byId(app, connId);
            if (conn == null) { write404(s); return; }
            fs = FsManager.connect(app, conn);
            in = fs.open(remotePath);
            if (size > 0 && partial && skip >= size) {
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + size + "\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
                out.flush();
                return;
            }
            if (skip > 0) skipFully(in, skip);
            OutputStream out = s.getOutputStream();
            StringBuilder resp = new StringBuilder();
            long sendLen = -1;
            if (size > 0 && partial) {
                if (rangeEnd < skip || rangeEnd >= size) rangeEnd = size - 1;
                sendLen = Math.max(0, rangeEnd - skip + 1);
                resp.append("HTTP/1.1 206 Partial Content\r\n")
                        .append("Content-Type: ").append(mime).append("\r\n")
                        .append("Content-Length: ").append(sendLen).append("\r\n")
                        .append("Content-Range: bytes ").append(skip).append("-").append(rangeEnd)
                        .append("/").append(size).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Connection: close\r\n\r\n");
            } else if (size > 0) {
                resp.append("HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: ").append(mime).append("\r\n")
                        .append("Content-Length: ").append(size).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Connection: close\r\n\r\n");
            } else {
                resp.append("HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: ").append(mime).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Connection: close\r\n\r\n");
            }
            out.write(resp.toString().getBytes("ISO-8859-1"));
            byte[] buf = new byte[16384];
            int n;
            long left = sendLen;
            while ((n = in.read(buf, 0, sendLen >= 0 ? (int) Math.min(buf.length, left) : buf.length)) > 0) {
                out.write(buf, 0, n);
                if (sendLen >= 0) {
                    left -= n;
                    if (left <= 0) break;
                }
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "proxy handle", e);
            try {
                s.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes("ISO-8859-1"));
                s.getOutputStream().flush();
            } catch (Exception ignored) {}
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (fs != null) try { fs.close(); } catch (Exception ignored) {}
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void write404(Socket s) {
        try {
            s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
        } catch (IOException ignored) {}
    }

    private static void skipFully(InputStream in, long skip) throws IOException {
        long left = skip;
        byte[] buf = new byte[8192];
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                s = n;
            }
            left -= s;
        }
    }

    private static String param(String query, String key) {
        if (query == null || key == null) return null;
        String[] parts = query.split("&");
        for (String p : parts) {
            int i = p.indexOf('=');
            String k = i >= 0 ? p.substring(0, i) : p;
            if (key.equals(k)) {
                return i >= 0 ? p.substring(i + 1) : "";
            }
        }
        return null;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); }
        catch (Exception ignored) { return def; }
    }
}
