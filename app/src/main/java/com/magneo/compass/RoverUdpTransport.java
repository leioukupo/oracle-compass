package com.magneo.compass;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Locale;

/** ESP32-compatible 50Hz joy sender plus K230 UDP discovery. */
public final class RoverUdpTransport {
    public interface Listener {
        void onTargetChanged(String host);
        void onTransportState(String target, long sent, long errors, float rate, String error);
    }

    private static final int DISCOVERY_PORT = 7789;
    // Keep the wire format unchanged, but sample/send often enough for the
    // K230 trajectory controller to receive a fresh stick value every 20 ms.
    private static final long PERIOD_MS = 20L;

    private final Context context;
    private final Listener listener;
    private final WifiManager wifi;
    private final Object sendLock = new Object();
    private volatile boolean running;
    /** Invalidates send/discovery workers across a fast pause/resume cycle. */
    private volatile int lifecycleGeneration;
    private volatile int lx, ly, rx, ry;
    private volatile boolean swL, swR;
    private volatile String configuredHost;
    private volatile int port = Prefs.DEFAULT_ROVER_UDP_PORT;
    private volatile boolean autoDiscovery = true;
    private volatile boolean broadcast = true;
    private volatile String discoveredHost = "";
    private volatile long discoveredAtMs;
    private volatile DatagramSocket socket;
    private volatile DatagramSocket discoverySocket;
    private Thread sendThread;
    private Thread discoveryThread;
    private long seq;
    private long sent;
    private long errors;
    private long rateWindowSent;
    private long rateWindowAt;
    private volatile float rate;
    private volatile String lastError = "";
    private String resolvedHost = "";
    private InetAddress resolvedAddress;

    public RoverUdpTransport(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        reloadPrefs();
    }

    public void start() {
        if (running) return;
        reloadPrefs();
        final int generation = ++lifecycleGeneration;
        running = true;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
        } catch (Exception e) {
            socket = null;
            recordError(e);
        }
        if (socket == null) {
            running = false;
            notifyState();
            return;
        }
        rateWindowSent = 0;
        rate = 0f;
        rateWindowAt = SystemClock.elapsedRealtime();
        final DatagramSocket sendSocket = socket;
        sendThread = new Thread(() -> sendLoop(generation, sendSocket), "rover-joy-udp");
        sendThread.setDaemon(true);
        sendThread.start();
        discoveryThread = new Thread(() -> discoveryLoop(generation), "rover-k230-discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
        notifyState();
    }

    public void reloadPrefs() {
        String previousConfiguredHost = configuredHost;
        configuredHost = Prefs.roverTargetHost(context);
        port = Prefs.roverUdpPort(context);
        autoDiscovery = Prefs.roverAutoDiscovery(context);
        broadcast = Prefs.roverBroadcast(context);
        synchronized (sendLock) {
            resolvedHost = "";
            resolvedAddress = null;
        }
        // Keep the last discovered K230 across a pause/resume cycle. The
        // discovery socket is deliberately short-lived with the Activity;
        // clearing this cache here makes the second car-control open show
        // "等待 K230" until another broadcast happens to arrive. Only a real
        // manual target change invalidates the cached discovery result.
        if (previousConfiguredHost == null && configuredHost.length() == 0 && autoDiscovery) {
            // A new Activity (or a process restart) has no in-memory discovery
            // state. Restore the last beacon address so the second car-control
            // open can send immediately instead of waiting for another hello.
            discoveredHost = Prefs.roverLastDiscoveredHost(context);
            discoveredAtMs = discoveredHost.length() == 0
                    ? 0L : SystemClock.elapsedRealtime();
        } else if (previousConfiguredHost != null &&
                !previousConfiguredHost.equals(configuredHost)) {
            discoveredHost = "";
            discoveredAtMs = 0L;
            Prefs.setRoverLastDiscoveredHost(context, "");
        }
        if (listener != null) listener.onTargetChanged(activeHost());
    }

    public void setInput(int leftX, int leftY, int rightX, int rightY,
                         boolean leftButton, boolean rightButton) {
        lx = clamp(leftX);
        ly = clamp(leftY);
        rx = clamp(rightX);
        ry = clamp(rightY);
        swL = leftButton;
        swR = rightButton;
    }

    /** Immediately neutralizes the next frame and sends a small neutral burst. */
    public void requestStop() {
        setInput(0, 0, 0, 0, false, false);
        sendNeutralBurstAsync(socket);
    }

    public void stop() {
        if (!running && socket == null && discoverySocket == null) return;
        setInput(0, 0, 0, 0, false, false);
        final String stopTarget = activeHost();
        ++lifecycleGeneration;
        running = false;
        DatagramSocket ds = discoverySocket;
        discoverySocket = null;
        if (ds != null) ds.close();
        DatagramSocket s = socket;
        socket = null;
        // The final neutral burst must not run on the Activity/UI thread. A
        // slow route or DNS lookup here used to surface NetworkOnMainThreadException
        // exactly while leaving the rover page.
        if (s != null) {
            Thread neutral = new Thread(() -> {
                sendNeutralBurst(s, stopTarget);
                try { s.close(); } catch (Exception ignored) {}
            }, "rover-neutral-stop");
            neutral.setDaemon(true);
            neutral.start();
        }
        interrupt(sendThread);
        interrupt(discoveryThread);
        sendThread = null;
        discoveryThread = null;
        notifyState();
    }

    public boolean isRunning() { return running; }
    public String activeHost() {
        // Keep the last discovered address across pause/resume and brief
        // beacon gaps. Falling back to broadcast after 3.5s made a reopened
        // control page appear connected while every joystick frame missed
        // the rover's unicast endpoint. A new beacon replaces this value, so
        // retaining it is safe and gives control/video a stable target while
        // the K230 is rebooting.
        if (autoDiscovery && validHost(discoveredHost)) {
            return discoveredHost;
        }
        return configuredHost == null ? "" : configuredHost.trim();
    }

    private void sendLoop(final int generation, final DatagramSocket sendSocket) {
        long next = SystemClock.elapsedRealtime();
        while (running && generation == lifecycleGeneration) {
            long now = SystemClock.elapsedRealtime();
            if (now < next) {
                try { Thread.sleep(Math.min(20L, next - now)); }
                catch (InterruptedException ignored) {}
                continue;
            }
            next += PERIOD_MS;
            if (next < now - PERIOD_MS * 4L) next = now + PERIOD_MS;
            sendFrame(sendSocket, lx, ly, rx, ry, swL, swR);
            if (now - rateWindowAt >= 1000L) {
                rate = rateWindowSent * 1000f / Math.max(1L, now - rateWindowAt);
                rateWindowSent = 0;
                rateWindowAt = now;
                notifyState();
            }
        }
    }

    private void sendFrame(DatagramSocket s, int leftX, int leftY, int rightX, int rightY,
                           boolean leftButton, boolean rightButton) {
        sendFrame(s, leftX, leftY, rightX, rightY, leftButton, rightButton, null);
    }

    private void sendFrame(DatagramSocket s, int leftX, int leftY, int rightX, int rightY,
                           boolean leftButton, boolean rightButton, String targetOverride) {
        if (s == null) return;
        String target = validHost(targetOverride) ? targetOverride : activeHost();
        try {
            WifiInfo wi = wifi == null ? null : wifi.getConnectionInfo();
            int rssi = wi == null ? 0 : wi.getRssi();
            if (rssi < -127 || rssi > 0) rssi = 0;
            JSONObject obj = new JSONObject();
            obj.put("type", "joy");
            obj.put("ver", 1);
            obj.put("seq", seq++);
            obj.put("ts_ms", SystemClock.elapsedRealtime());
            obj.put("lx", clamp(leftX));
            obj.put("ly", clamp(leftY));
            obj.put("rx", clamp(rightX));
            obj.put("ry", clamp(rightY));
            obj.put("sw_l", leftButton ? 1 : 0);
            obj.put("sw_r", rightButton ? 1 : 0);
            obj.put("rssi", rssi);
            byte[] data = obj.toString().getBytes("UTF-8");

            boolean delivered = false;
            if (validHost(target)) {
                sendTo(s, data, target, port);
                delivered = true;
            } else if (broadcast) {
                String subnet = subnetBroadcast();
                // One broadcast per frame: prefer the local subnet and use the
                // global address only when Android cannot report DHCP details.
                sendTo(s, data, subnet, port);
                delivered = true;
            }
            if (delivered) {
                sent++;
                rateWindowSent++;
            } else {
                lastError = "未配置 K230 目标";
            }
        } catch (Exception e) {
            errors++;
            recordError(e);
        }
    }

    private void sendNeutralBurstAsync(final DatagramSocket s) {
        if (s == null) return;
        Thread neutral = new Thread(() -> sendNeutralBurst(s), "rover-neutral-request");
        neutral.setDaemon(true);
        neutral.start();
    }

    private void sendNeutralBurst(DatagramSocket s) {
        sendNeutralBurst(s, null);
    }

    private void sendNeutralBurst(DatagramSocket s, String targetOverride) {
        for (int i = 0; i < 3; i++) {
            sendFrame(s, 0, 0, 0, 0, false, false, targetOverride);
        }
    }

    private void sendTo(DatagramSocket s, byte[] data, String host, int destinationPort)
            throws Exception {
        InetAddress address;
        synchronized (sendLock) {
            if (host.equals(resolvedHost) && resolvedAddress != null) {
                address = resolvedAddress;
            } else {
                address = InetAddress.getByName(host);
                resolvedHost = host;
                resolvedAddress = address;
            }
        }
        s.send(new DatagramPacket(data, data.length, address, destinationPort));
    }

    private void discoveryLoop(final int generation) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(DISCOVERY_PORT));
            s.setSoTimeout(1000);
            discoverySocket = s;
            byte[] buf = new byte[512];
            while (running && generation == lifecycleGeneration) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String raw = new String(p.getData(), p.getOffset(), p.getLength(), "UTF-8");
                    JSONObject obj = new JSONObject(raw);
                    String type = obj.optString("t", "");
                    if (!"talos/k230/hello".equals(type) && !"talos-k230".equals(type)) continue;
                    String host = obj.optString("ip", "");
                    if (!validHost(host)) host = p.getAddress().getHostAddress();
                    if (validHost(host) && !host.equals(discoveredHost)) {
                        discoveredHost = host;
                        discoveredAtMs = SystemClock.elapsedRealtime();
                        Prefs.setRoverLastDiscoveredHost(context, host);
                        synchronized (sendLock) {
                            resolvedHost = "";
                            resolvedAddress = null;
                        }
                        if (listener != null) listener.onTargetChanged(activeHost());
                    } else if (validHost(host)) {
                        // Refresh liveness even when the IP did not change.
                        discoveredAtMs = SystemClock.elapsedRealtime();
                    }
                } catch (SocketTimeoutException ignored) {
                    // Wake periodically to notice stop().
                } catch (Exception e) {
                    if (running) recordError(e);
                }
            }
        } catch (Exception e) {
            if (running) recordError(e);
        } finally {
            if (discoverySocket == s) discoverySocket = null;
            if (s != null) s.close();
        }
    }

    private String subnetBroadcast() {
        try {
            DhcpInfo d = wifi == null ? null : wifi.getDhcpInfo();
            if (d == null || d.ipAddress == 0 || d.netmask == 0) return "255.255.255.255";
            int b = (d.ipAddress & d.netmask) | ~d.netmask;
            return String.format(Locale.US, "%d.%d.%d.%d", b & 255, (b >> 8) & 255,
                    (b >> 16) & 255, (b >> 24) & 255);
        } catch (Exception ignored) {
            return "255.255.255.255";
        }
    }

    private void notifyState() {
        if (listener != null) {
            listener.onTransportState(activeHost(), sent, errors, rate, lastError);
        }
    }

    private void recordError(Exception e) {
        String msg = e.getClass().getSimpleName();
        if (e.getMessage() != null && e.getMessage().length() > 0) msg += ":" + e.getMessage();
        lastError = msg.length() > 80 ? msg.substring(0, 80) : msg;
        notifyState();
    }

    private static boolean validHost(String host) {
        return host != null && host.trim().length() > 0 && !"0.0.0.0".equals(host.trim());
    }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }

    private static void interrupt(Thread t) {
        if (t != null) t.interrupt();
    }
}
