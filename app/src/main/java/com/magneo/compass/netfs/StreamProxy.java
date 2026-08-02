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
        String encoded = Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
        return "http://127.0.0.1:" + PORT + "/" + connId + "/" + encoded;
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
        try {
            s.setSoTimeout(600000);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String reqLine = r.readLine();
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            long skip = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    try {
                        String rg = line.substring(line.indexOf('=') + 1).trim(); // bytes=start-
                        skip = Long.parseLong(rg.substring(0, rg.indexOf('-')).trim());
                    } catch (Exception ignored) {}
                }
            }
            String[] seg = path.substring(1).split("/", 2);
            if (seg.length < 2) { write404(s); return; }
            String connId = seg[0];
            String remotePath = new String(Base64.decode(seg[1], Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
            FsManager.Conn conn = FsManager.byId(app, connId);
            if (conn == null) { write404(s); return; }
            fs = FsManager.connect(conn);
            InputStream in = fs.open(remotePath);
            if (skip > 0) in.skip(skip);
            OutputStream out = s.getOutputStream();
            String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n";
            out.write(resp.getBytes("ISO-8859-1"));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "proxy handle", e);
        } finally {
            if (fs != null) try { fs.close(); } catch (Exception ignored) {}
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void write404(Socket s) {
        try {
            s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
        } catch (IOException ignored) {}
    }
}
