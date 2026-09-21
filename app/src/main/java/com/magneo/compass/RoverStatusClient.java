package com.magneo.compass;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Optional read-only K230 status stream.  UDP control never depends on it. */
public final class RoverStatusClient {
    public interface Listener {
        void onMode(String mode);
        void onStatus(String status);
    }

    private static final int PORT = 7788;
    private final Listener listener;
    private volatile boolean running;
    /** Prevents an interrupted worker from becoming live again after restart. */
    private volatile int lifecycleGeneration;
    private volatile String host = "";
    private Thread thread;
    private volatile Socket socket;

    public RoverStatusClient(Listener l) { listener = l; }

    public synchronized void start(String h) {
        host = h == null ? "" : h.trim();
        if (running) return;
        final int generation = ++lifecycleGeneration;
        running = true;
        thread = new Thread(() -> runLoop(generation), "rover-k230-status");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void setHost(String h) {
        host = h == null ? "" : h.trim();
        Socket s = socket;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    public synchronized void stop() {
        ++lifecycleGeneration;
        running = false;
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void runLoop(final int generation) {
        while (running && generation == lifecycleGeneration) {
            String h = host;
            if (h.length() == 0) {
                publish("K230 状态未连接");
                sleep(1000);
                continue;
            }
            Socket s = new Socket();
            try {
                socket = s;
                s.connect(new InetSocketAddress(h, PORT), 900);
                s.setSoTimeout(1500);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        s.getInputStream(), StandardCharsets.UTF_8));
                publish("K230 状态在线");
                String line;
                while (running && generation == lifecycleGeneration &&
                        h.equals(host) && (line = reader.readLine()) != null) {
                    parse(line);
                }
            } catch (Exception e) {
                if (running && generation == lifecycleGeneration && h.equals(host)) {
                    publish("K230 状态不可用");
                }
            } finally {
                if (socket == s) socket = null;
                try { s.close(); } catch (Exception ignored) {}
            }
            sleep(1200);
        }
    }

    private void parse(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            String t = obj.optString("t", obj.optString("type", ""));
            if ("gimbal/mode".equals(t)) {
                String mode = obj.optString("mode", obj.optString("value", ""));
                if (mode.length() > 0 && listener != null) listener.onMode(mode);
            } else if ("status".equals(t)) {
                String mode = obj.optString("mode", obj.optString("gimbal_mode", ""));
                if (mode.length() > 0 && listener != null) listener.onMode(mode);
                publish("K230 状态在线");
            }
        } catch (Exception ignored) {
            // The K230 may interleave diagnostic lines; ignore malformed frames.
        }
    }

    private void publish(final String value) {
        if (listener != null) listener.onStatus(value);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
