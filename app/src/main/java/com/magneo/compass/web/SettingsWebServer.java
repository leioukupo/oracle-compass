package com.magneo.compass.web;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.DebugLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.ProviderConfig;
import com.magneo.compass.llm.LlmClient;
import com.magneo.compass.mcp.McpClient;
import com.magneo.compass.mcp.McpManager;
import com.magneo.compass.mcp.McpServerConfig;
import com.magneo.compass.netfs.FsManager;
import com.magneo.compass.netfs.NetFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 本机网页设置服务：同网络浏览器打开当前设备 IP 的 8080 端口可配置应用（不含推流）。 */
public class SettingsWebServer {
    public static final int PORT = 8080;
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static volatile ServerSocket server;
    private static volatile Thread thread;
    private static volatile Context app;
    private static final ExecutorService webWorkers = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "web-conn");
                    t.setDaemon(true);
                    return t;
                }
            }, new ThreadPoolExecutor.AbortPolicy());

    public static synchronized void start(Context c) {
        if (server != null) return;
        app = c.getApplicationContext();
        for (int attempt = 0; attempt < 3 && server == null; attempt++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);   // 快速重启时避免 TIME_WAIT 占用
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 32);
                server = ss;
                thread = new Thread(SettingsWebServer::loop, "web-settings");
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                server = null;
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    }

    public static android.content.Context getAppContext() { return app; }

    public static String url() {
        return "http://" + localIp() + ":" + PORT + "/";
    }

    public static String localIp() {
        String bestPrivate = null;
        String wifi = wifiIp();
        if (wifi != null) return wifi;
        try {
            Enumeration<NetworkInterface> ens = NetworkInterface.getNetworkInterfaces();
            while (ens.hasMoreElements()) {
                NetworkInterface ni = ens.nextElement();
                try { if (!ni.isUp()) continue; } catch (Exception ignored) {}
                String n = ni.getName();
                boolean lan = n != null && (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en"));
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (lan) return ip;
                    if (bestPrivate == null && isPrivate(ip)) bestPrivate = ip;
                }
            }
        } catch (Exception ignored) {}
        return bestPrivate != null ? bestPrivate : "127.0.0.1";
    }

    private static String wifiIp() {
        try {
            if (app == null) return null;
            WifiManager wm = (WifiManager) app.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            String s = (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                    + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            if ("0.0.0.0".equals(s) || s.startsWith("127.")) return null;
            return s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.");
    }

    private static void loop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                try {
                    webWorkers.execute(() -> handle(s));
                } catch (RejectedExecutionException e) {
                    try { s.close(); } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            Request req = readRequest(in);
            if (req == null) return;
            String method = req.method;
            String path = req.path;
            String body = "";
            boolean rawUpload = path.equals("/appmgr/upload")
                    || path.equals("/boot-assets/upload-logo")
                    || path.equals("/boot-assets/upload-original-logo");
            if (method.equals("POST") && req.contentLength > 0 && !rawUpload) {
                body = readBodyString(in, req.contentLength, 512 * 1024);
            }

            OutputStream out = s.getOutputStream();
            boolean authed = isAuthed(req);
            if (path.equals("/")) serveConsoleHtml(out);
            else if (path.equals("/status")) serveStatus(out, authed);
            else if (path.equals("/conversations")) {
                if (!requireAuth(out, authed)) return;
                serveConversations(out);
            }
            else if (path.equals("/clear_conv")) {
                if (!requireAuth(out, authed)) return;
                serveClearConv(out);
            }
            else if (path.equals("/debug_log")) {
                if (!requireAuth(out, authed)) return;
                serveDebugLog(out);
            }
            else if (path.equals("/clear_debug")) {
                if (!requireAuth(out, authed)) return;
                serveClearDebug(out);
            }
            else if (path.equals("/stream")) {
                if (!requireAuth(out, authed)) return;
                serveStream(s); return;
            }
            else if (path.equals("/h264")) {
                if (!requireAuth(out, authed)) return;
                serveH264(s); return;
            }
            else if (path.equals("/h264fast")) {
                if (!requireAuth(out, authed)) return;
                serveH264Fast(s); return;
            }
            else if (path.equals("/stream_state")) serveStreamState(out);
            else if (path.equals("/system_status")) serveSystemStatus(out);
            else if (path.equals("/gps/reset")) {
                if (!requireAuth(out, authed)) return;
                serveGpsReset(out);
            }
            else if (path.equals("/key")) {
                if (!requireAuth(out, authed)) return;
                serveKey(out, req.target); return;
            }
            else if (path.equals("/touch")) {
                if (!requireAuth(out, authed)) return;
                serveTouch(out, req.target); return;
            }
            else if (path.equals("/cam")) {
                if (!requireAuth(out, authed)) return;
                serveCamPage(out, req.target);
            }
            else if (path.equals("/camhttp")) {
                if (!requireAuth(out, authed)) return;
                com.magneo.compass.cam.CameraHttpStreamer.serve(s); return;
            }
            else if (path.equals("/cam/start")) {
                if (!requireAuth(out, authed)) return;
                com.magneo.compass.cam.CameraStreamService.start(app); serveText(out, "正在启动摄像头推流…");
            }
            else if (path.equals("/cam/stop")) {
                if (!requireAuth(out, authed)) return;
                com.magneo.compass.cam.CameraStreamService.stop(app); serveText(out, "已停止");
            }
            else if (path.equals("/cam/status")) {
                if (!requireAuth(out, authed)) return;
                serveCamStatus(out);
            }
            else if (path.equals("/cam/offer")) {
                if (!requireAuth(out, authed)) return;
                serveCamOffer(out, body);
            }
            else if (path.equals("/cam/answer")) {
                if (!requireAuth(out, authed)) return;
                serveCamAnswer(out);
            }
            else if (path.equals("/frpc/status")) serveFrpcStatus(out);
            else if (path.equals("/frpc/start")) {
                if (!requireAuth(out, authed)) return;
                serveText(out, com.magneo.compass.frp.FrpcManager.start(app));
            }
            else if (path.equals("/frpc/stop")) {
                if (!requireAuth(out, authed)) return;
                serveText(out, com.magneo.compass.frp.FrpcManager.stop());
            }
            else if (path.equals("/adb/status")) serveJson(out, AdbManager.status(app));
            else if (path.equals("/adb/save")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AdbManager.save(app, body));
            }
            else if (path.equals("/adb/start")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AdbManager.start(app, body));
            }
            else if (path.equals("/adb/stop")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AdbManager.stop(app));
            }
            else if (path.equals("/appmgr/state")) serveJson(out, AppManager.state(app, req.header("x-appmgr-token")));
            else if (path.equals("/appmgr/login")) serveJson(out, AppManager.login(app, body));
            else if (path.equals("/appmgr/setup")) serveJson(out, AppManager.setup(app, body));
            else if (path.equals("/appmgr/apps")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.apps(app));
            }
            else if (path.equals("/appmgr/upload")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else {
                    s.setSoTimeout(0);
                    serveJson(out, AppManager.upload(app, in, req.contentLength, req.header("x-file-name")));
                }
            }
            else if (path.equals("/appmgr/fetch")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.fetch(app, body));
            }
            else if (path.equals("/appmgr/install")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.install(app));
            }
            else if (path.equals("/appmgr/install-boot-module")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.installBootModule(app));
            }
            else if (path.equals("/device/reboot")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.reboot(app, body));
            }
            else if (path.equals("/appmgr/uninstall")) {
                if (!authed) serveJson(out, appMgrAuthError());
                else serveJson(out, AppManager.uninstall(app, body));
            }
            else if (path.equals("/boot-assets/status")) {
                if (!requireAuth(out, authed)) return;
                serveJson(out, BootAssetsManager.status(app));
            }
            else if (path.equals("/boot-assets/backup")) {
                if (!requireAuth(out, authed)) return;
                serveJson(out, BootAssetsManager.backup(app));
            }
            else if (path.equals("/boot-assets/boot-log")) {
                if (!requireAuth(out, authed)) return;
                serveJson(out, BootAssetsManager.bootLog());
            }
            else if (path.equals("/boot-assets/download")) {
                if (!requireAuth(out, authed)) return;
                serveBootAssetDownload(out, req.target);
                return;
            }
            else if (path.equals("/boot-assets/upload-logo")) {
                if (!requireAuth(out, authed)) return;
                s.setSoTimeout(0);
                serveJson(out, BootAssetsManager.uploadLogo(app, in, req.contentLength));
            }
            else if (path.equals("/boot-assets/upload-original-logo")) {
                if (!requireAuth(out, authed)) return;
                s.setSoTimeout(0);
                serveJson(out, BootAssetsManager.uploadOriginalLogo(app, in, req.contentLength,
                        req.header("x-file-sha256")));
            }
            else if (path.equals("/boot-assets/flash-logo")) {
                if (!requireAuth(out, authed)) return;
                serveJson(out, BootAssetsManager.flashLogo(app, body));
            }
            else if (path.equals("/boot-assets/restore-logo")) {
                if (!requireAuth(out, authed)) return;
                serveJson(out, BootAssetsManager.restoreLogo(app, body));
            }
            else if (path.equals("/save")) {
                if (!requireAuth(out, authed)) return;
                serveSave(out, body);
            }
            else if (path.equals("/backup/export")) {
                if (!requireAuth(out, authed)) return;
                serveBackupExport(out);
            }
            else if (path.equals("/backup/restore")) {
                if (!requireAuth(out, authed)) return;
                serveBackupRestore(out, body);
            }
            else if (path.equals("/test/llm")) {
                if (!requireAuth(out, authed)) return;
                serveTestLlm(out);
            }
            else if (path.equals("/test/asr_final")) {
                if (!requireAuth(out, authed)) return;
                serveTestAsrFinal(out);
            }
            else if (path.equals("/test/tts")) {
                if (!requireAuth(out, authed)) return;
                serveTestTts(out);
            }
            else if (path.equals("/test/tts_voices")) {
                if (!requireAuth(out, authed)) return;
                serveTtsVoices(out);
            }
            else if (path.equals("/mcp/status")) {
                if (!requireAuth(out, authed)) return;
                serveMcpStatus(out, false);
            }
            else if (path.equals("/mcp/refresh")) {
                if (!requireAuth(out, authed)) return;
                serveMcpStatus(out, true);
            }
            else if (path.equals("/mcp/test")) {
                if (!requireAuth(out, authed)) return;
                serveMcpStatus(out, true);
            }
            else if (path.equals("/mcp/call")) {
                if (!requireAuth(out, authed)) return;
                serveMcpCall(out, body);
            }
            else if (path.equals("/open/tts_test")) {
                if (!requireAuth(out, authed)) return;
                serveOpenTtsTest(out);
            }
            else if (path.equals("/fs/list")) {
                if (!requireAuth(out, authed)) return;
                serveFsList(out);
            }
            else if (path.equals("/fs/save")) {
                if (!requireAuth(out, authed)) return;
                serveFsSave(out, body);
            }
            else if (path.equals("/fs/remove")) {
                if (!requireAuth(out, authed)) return;
                serveFsRemove(out, body);
            }
            else if (path.equals("/fs/test")) {
                if (!requireAuth(out, authed)) return;
                serveFsTest(out, body);
            }
            else serve404(out);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String head = readHeaderBlock(in);
        if (head == null || head.trim().isEmpty()) return null;
        String[] lines = head.split("\\r?\\n");
        if (lines.length == 0) return null;
        String[] parts = lines[0].split(" ");
        if (parts.length < 2) return null;
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int p = lines[i].indexOf(':');
            if (p <= 0) continue;
            headers.put(lines[i].substring(0, p).trim().toLowerCase(Locale.US),
                    lines[i].substring(p + 1).trim());
        }
        long len = 0;
        try { len = Long.parseLong(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) {}
        return new Request(parts[0], parts[1], headers, len);
    }

    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 65536) {
            int c = in.read();
            if (c < 0) break;
            b.write(c);
            if (c == '\r') state = (state == 2) ? 3 : 1;
            else if (c == '\n') state = (state == 1) ? 2 : (state == 3 ? 4 : 0);
            else state = 0;
            if (state == 4) break;
        }
        if (b.size() == 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    private static String readBodyString(InputStream in, long len, int max) throws IOException {
        if (len <= 0) return "";
        if (len > max) throw new IOException("请求体过大");
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return new String(buf, 0, off, "UTF-8");
    }

    private static JSONObject appMgrAuthError() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", AppManager.hasPassword(app) ? "需要登录管理密码" : "请先设置管理密码");
        } catch (Exception ignored) {}
        return o;
    }

    private static boolean isAuthed(Request req) {
        if (req == null) return false;
        String token = req.header("x-appmgr-token");
        if (token == null || token.trim().isEmpty()) token = qParam(req.target, "access");
        return AppManager.authorized(app, token);
    }

    private static boolean requireAuth(OutputStream out, boolean authed) throws IOException {
        if (authed) return true;
        serveJson(out, appMgrAuthError());
        return false;
    }

    private static final class Request {
        final String method;
        final String target;
        final String path;
        final Map<String, String> headers;
        final long contentLength;

        Request(String method, String target, Map<String, String> headers, long contentLength) {
            this.method = method;
            this.target = target;
            this.path = target.split("\\?", 2)[0];
            this.headers = headers;
            this.contentLength = Math.max(0, contentLength);
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }

    private static void serveConsoleHtml(OutputStream out) throws IOException {
        StringBuilder h = new StringBuilder(60000);
        h.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>真理罗盘 · 远程控制台</title>")
                .append("<style>")
                .append(":root{--bg:#1b150f;--panel:#251d14;--panel2:#2d2418;--gold:#d4af37;--gold2:#9b7b2e;--red:#7e2924;--cyan:#52c9d8;--text:#f0e3c7;--dim:#ad9f86;--ok:#93c973;--bad:#e06d5f}")
                .append("*{box-sizing:border-box}body{margin:0;background-color:var(--bg);background-image:linear-gradient(180deg,#2a2116 0%,#21190f 34%,#1b150f 100%);background-attachment:fixed;color:var(--text);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:18px 18px 92px}")
                .append(".wrap{max-width:1180px;margin:0 auto}.hero{display:flex;gap:14px;align-items:flex-end;justify-content:space-between;margin-bottom:14px}.title h1{margin:0;color:var(--gold);font-size:24px;letter-spacing:0}.title p{margin:4px 0 0;color:var(--dim);font-size:13px}")
                .append(".pill{border:1px solid rgba(212,175,55,.45);border-radius:999px;padding:7px 12px;background:rgba(40,31,19,.72);color:var(--gold);font-size:12px;white-space:nowrap}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(128px,1fr));gap:10px}.chip{border:1px solid rgba(181,139,48,.32);border-radius:12px;background:rgba(43,33,20,.58);padding:10px;min-height:58px}.chip span{display:block;color:var(--dim);font-size:11px}.chip b{display:block;color:var(--gold);font-size:16px;margin-top:5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.chip.ok b{color:var(--ok)}.chip.bad b{color:var(--bad)}")
                .append(".card,.panel{border:1px solid rgba(181,139,48,.38);border-radius:14px;background:rgba(37,29,20,.72);box-shadow:0 12px 32px rgba(32,22,10,.22);padding:14px;margin:12px 0}.auth{max-width:560px;margin:20px auto}.tabs{position:sticky;top:0;z-index:5;display:flex;gap:8px;overflow-x:auto;background:rgba(27,21,15,.92);border-bottom:1px solid rgba(181,139,48,.25);padding:10px 0;margin-top:10px}.tab{background:rgba(41,32,20,.68);color:var(--dim);border:1px solid rgba(181,139,48,.34);border-radius:999px;padding:9px 14px;cursor:pointer;white-space:nowrap}.tab.active{color:#1b150f;background:var(--gold);border-color:var(--gold)}")
                .append(".panel{display:none}.panel.active{display:block}.sectionTitle{display:flex;align-items:center;justify-content:space-between;gap:10px;border-bottom:1px solid rgba(181,139,48,.22);margin:-2px 0 12px;padding-bottom:10px}.sectionTitle h2{margin:0;color:var(--gold);font-size:18px}.sectionTitle small{color:var(--dim)}")
                .append(".cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px}.box{border:1px solid rgba(181,139,48,.24);border-radius:12px;background:rgba(32,25,17,.52);padding:12px}.box h3{margin:0 0 10px;color:#dec98f;font-size:15px}.row{display:grid;grid-template-columns:130px 1fr;gap:10px;align-items:center;margin:8px 0}.row label{color:var(--gold);font-size:13px}.hint{color:var(--dim);font-size:11px;line-height:1.5}.state{color:var(--cyan);font-size:12px}.danger{color:var(--bad)}")
                .append("input,select,textarea{width:100%;background:rgba(29,22,15,.82);color:var(--text);border:1px solid rgba(181,139,48,.42);border-radius:9px;padding:8px;font:inherit;font-size:13px}input[type=range]{accent-color:var(--gold);padding:0;border:0;background:transparent}textarea{min-height:92px;resize:vertical;font-family:ui-monospace,Menlo,Consolas,monospace}.inline{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.inline input[type=checkbox]{width:auto}.checkrow{display:flex;gap:8px;align-items:center;color:var(--dim);font-size:13px;margin:8px 0}.checkrow input{width:auto}")
                .append("button{background:var(--gold);color:#1b150f;border:0;border-radius:9px;padding:8px 13px;margin:3px;cursor:pointer;font-weight:600}button.secondary{background:rgba(41,32,20,.68);color:var(--gold);border:1px solid rgba(181,139,48,.42)}button.danger{background:#5d2a22;color:#f5d9d2;border:1px solid #925044}button:disabled{opacity:.45;cursor:default}")
                .append("pre,.log{background:rgba(25,19,13,.72);border:1px solid rgba(181,139,48,.24);border-radius:10px;color:#9ed17d;padding:9px;max-height:220px;overflow:auto;white-space:pre-wrap;font-size:12px;line-height:1.45}.list{border:1px solid rgba(181,139,48,.24);border-radius:10px;overflow:hidden;background:rgba(27,21,15,.58)}.item{display:flex;gap:10px;align-items:center;padding:9px;border-bottom:1px solid rgba(181,139,48,.16)}.item:last-child{border-bottom:0}.item .main{flex:1;min-width:0}.item b{color:var(--gold)}.item small{display:block;color:var(--dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.mini{font-size:11px;color:var(--dim)}")
                .append(".sysdash{padding:16px}.sysbody{display:grid;grid-template-columns:1.05fr 1.5fr;gap:14px;align-items:stretch}.syssummary{border:1px solid rgba(181,139,48,.28);border-radius:12px;background:rgba(31,24,16,.58);padding:14px;min-height:132px}.systime{font-size:34px;line-height:1;color:var(--gold);font-weight:700;font-family:ui-monospace,Menlo,Consolas,monospace}.sysdate{font-size:12px;color:var(--text);margin-top:6px}.syscore{font-size:12px;color:var(--ok);margin-top:12px;line-height:1.55}.sysgps{font-size:12px;color:var(--dim);margin-top:4px;line-height:1.45}.tempring{display:grid;grid-template-columns:repeat(auto-fit,minmax(82px,1fr));gap:8px;margin-top:10px}.tempdot{border:1px solid rgba(181,139,48,.24);border-radius:10px;background:rgba(20,15,10,.62);padding:8px;text-align:center;min-width:0}.tempdot span{display:block;font-size:10px;color:var(--dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.tempdot b{display:block;font-size:16px;color:var(--gold);margin-top:3px}.sysmeters{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}.meter{border:1px solid rgba(181,139,48,.24);border-radius:12px;background:rgba(32,25,17,.48);padding:11px}.meter .top{display:flex;align-items:center;justify-content:space-between;color:var(--dim);font-size:12px}.meter b{color:var(--gold);font-size:16px}.bar{height:8px;border-radius:999px;background:rgba(16,12,8,.86);border:1px solid rgba(181,139,48,.22);overflow:hidden;margin-top:8px}.bar em{display:block;height:100%;width:0;background:linear-gradient(90deg,var(--gold2),var(--gold));border-radius:999px}.bar.cyan em{background:linear-gradient(90deg,#2c7880,var(--cyan))}.bar.bad em{background:linear-gradient(90deg,#6f2b25,var(--bad))}")
                .append("details{border:1px solid rgba(181,139,48,.24);border-radius:12px;background:rgba(32,25,17,.48);padding:9px;margin:10px 0}summary{cursor:pointer;color:var(--gold)}.preview{width:min(92mm,88vw);height:min(92mm,88vw);border-radius:50%;border:1px solid rgba(181,139,48,.42);background:#15110c;display:block;margin:10px auto;object-fit:cover}.screenwrap{position:relative;width:min(92mm,88vw);height:min(92mm,88vw);margin:10px auto}.screenwrap .preview{position:absolute;inset:0;width:100%;height:100%;margin:0}.touchpad{position:absolute;inset:0;border-radius:50%;display:none;z-index:3;cursor:crosshair;touch-action:none;user-select:none}.savebar{position:fixed;left:0;right:0;bottom:0;background:rgba(29,23,16,.96);border-top:1px solid rgba(181,139,48,.34);padding:10px 18px;z-index:10}.savebar .inner{max-width:1180px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;gap:10px}.hidden{display:none!important}a{color:var(--gold)}")
                .append("@media(max-width:720px){body{padding:12px 12px 92px}.hero{display:block}.row{grid-template-columns:1fr}.tabs{top:0}.cols,.sysbody{grid-template-columns:1fr}.savebar .inner{align-items:flex-start;flex-direction:column}}")
                .append("</style></head><body><div class='wrap'>")
                .append("<header class='hero'><div class='title'><h1>真理罗盘 · 远程控制台</h1><p>配置、链路测试、备份恢复和设备运维集中在这里。</p></div><div class='pill' id='authBadge'>未登录</div></header>")
                .append("<div class='grid' id='overview'>")
                .append("<div class='chip ok'><span>设备</span><b id='ovDevice'>在线</b></div>")
                .append("<div class='chip'><span>FRPC</span><b id='ovFrpc'>未知</b></div>")
                .append("<div class='chip'><span>ADB</span><b id='ovAdb'>未知</b></div>")
                .append("<div class='chip'><span>常驻监听</span><b id='ovVad'>未知</b></div>")
                .append("<div class='chip'><span>ASR</span><b id='ovAsr'>未知</b></div>")
                .append("<div class='chip'><span>Final ASR</span><b id='ovAsrFinal'>未知</b></div>")
                .append("<div class='chip'><span>LLM Key</span><b id='ovLlm'>未知</b></div>")
                .append("<div class='chip'><span>TTS</span><b id='ovTts'>未知</b></div>")
                .append("</div>")
                .append("<div class='card auth' id='authPanel'><div class='sectionTitle'><h2>管理登录</h2><small id='authHelp'>公网访问时需要先登录</small></div>")
                .append("<div class='row'><label>管理密码</label><input type='password' id='appPwd' autocomplete='current-password'></div>")
                .append("<div class='row'><label>旧密码</label><input type='password' id='appOldPwd' autocomplete='current-password' placeholder='修改密码时填写'></div>")
                .append("<div class='inline'><button type='button' onclick='appLogin()'>登录</button><button type='button' class='secondary' onclick='appSetup()'>设置/修改密码</button><span class='state' id='appAuth'></span></div>")
                .append("</div>")
                .append("<div id='console' class='hidden'>")
                .append("<div class='card sysdash'><div class='sectionTitle'><h2>设备状态</h2><small id='sysUpdated'>等待刷新</small></div><div class='sysbody'>")
                .append("<div class='syssummary'><div class='systime' id='sysTime'>--:--</div><div class='sysdate' id='sysDate'>--</div><div class='syscore' id='sysCore'>总 -- · App -- · Mali -- · 电 --</div><div class='sysgps' id='sysGps'></div><div class='tempring' id='sysTempRing'></div></div>")
                .append("<div class='sysmeters'><div class='meter'><div class='top'><span>CPU</span><b id='sysCpu'>--</b></div><div class='bar'><em id='sysCpuBar'></em></div></div>")
                .append("<div class='meter'><div class='top'><span>App CPU</span><b id='sysAppCpu'>--</b></div><div class='bar bad'><em id='sysAppCpuBar'></em></div></div>")
                .append("<div class='meter'><div class='top'><span>RAM</span><b id='sysRam'>--</b></div><div class='bar'><em id='sysRamBar'></em></div></div>")
                .append("<div class='meter'><div class='top'><span>Mali</span><b id='sysGpu'>--</b></div><div class='bar cyan'><em id='sysGpuBar'></em></div></div>")
                .append("<div class='meter'><div class='top'><span>电量</span><b id='sysBat'>--</b></div><div class='bar'><em id='sysBatBar'></em></div></div>")
                .append("<p class='hint' id='sysLoadNote'>CPU/RAM/温度来自设备实时采样；Mali clock off 表示内核节点未给出有效占用。</p></div></div><div class='box' style='margin-top:12px'><h3>硬件自检</h3><div id='hwDiag' class='list'></div><div class='inline' style='margin-top:10px'><button type='button' class='secondary' onclick='gpsReset()'>GPS 冷启动</button><span class='state' id='gpsResetMsg'></span></div></div></div>")
                .append("<nav class='tabs'>")
                .append("<button type='button' class='tab active' data-tab='model'>大模型</button>")
                .append("<button type='button' class='tab' data-tab='voice'>语音链路</button>")
                .append("<button type='button' class='tab' data-tab='mcp'>MCP 工具</button>")
                .append("<button type='button' class='tab' data-tab='vision'>视觉/摄像头</button>")
                .append("<button type='button' class='tab' data-tab='browser'>浏览器/网盘</button>")
                .append("<button type='button' class='tab' data-tab='remote'>FRP/ADB</button>")
                .append("<button type='button' class='tab' data-tab='apps'>应用管理</button>")
                .append("<button type='button' class='tab' data-tab='debug'>链路调试</button>")
                .append("<button type='button' class='tab' data-tab='records'>记录/备份</button>")
                .append("</nav>")
                .append("<form id='f' onsubmit='save();return false'>")
                .append("<section class='panel active' id='tab-model'><div class='sectionTitle'><h2>大模型</h2><small>Key 留空表示保持当前值</small></div><div class='cols'><div class='box'><h3>模型入口</h3>")
                .append(rowSelect("Provider", "provider", "provider", "<option value='deepseek'>deepseek</option><option value='openai兼容'>openai兼容</option>", "providerChanged()"))
                .append(rowInput("API Key", "apiKey", "password", "留空=保持当前 Key"))
                .append("<div class='row'><label></label><div class='inline'><span class='state' id='apiKeyState'>未知</span><label class='checkrow'><input type='checkbox' name='clearApiKey'>清空 Key</label></div></div>")
                .append(rowInput("公共 Base", "baseUrl", "text", "https://api.deepseek.com/v1"))
                .append(rowInput("文本 Base", "textBaseUrl", "text", "留空=公共 Base URL"))
                .append(rowInput("文本模型", "textModel", "text", "deepseek-chat"))
                .append(rowInput("视觉 Base", "visionBaseUrl", "text", "留空=公共 Base URL"))
                .append(rowInput("视觉模型", "visionModel", "text", "gpt-4.1-mini"))
                .append("</div><div class='box'><h3>生成参数</h3>")
                .append(rowSelect("思考强度", "reasoningEffort", null, "<option value='auto'>自动</option><option value='none'>禁止思考</option><option value='low'>低</option><option value='medium'>中</option><option value='high'>高</option><option value='max'>最大</option>", null))
                .append(rowInput("文本 MaxToken", "textMaxTokens", "text", "0=服务默认"))
                .append(rowInput("语音 MaxToken", "voiceMaxTokens", "text", "长故事可设 1500/2048"))
                .append(rowInput("视觉 MaxToken", "visionMaxTokens", "text", "0=服务默认"))
                .append(rowInput("文本温度", "textTemperature", "text", "0.0-2.0"))
                .append(rowInput("语音温度", "voiceTemperature", "text", "0.0-2.0"))
                .append(rowInput("视觉温度", "visionTemperature", "text", "0.0-2.0"))
                .append("<div class='inline'><button type='button' onclick=\"runTest('llm')\">测试 LLM</button><span class='state' id='testLlm'></span></div>")
                .append("<p class='hint'>DeepSeek 禁止思考会写入 thinking disabled；OpenAI 兼容格式使用 reasoning_effort。</p>")
                .append("</div></div></section>")
                .append("<section class='panel' id='tab-voice'><div class='sectionTitle'><h2>语音链路</h2><small>ASR / LLM / TTS 分段排障</small></div><div class='cols'><div class='box'><h3>ASR 与监听</h3>")
                .append(rowInput("语音 API Key", "voiceApiKey", "password", "留空=复用/保持当前语音 Key"))
                .append("<div class='row'><label></label><div class='inline'><span class='state' id='voiceKeyState'>未知</span><label class='checkrow'><input type='checkbox' name='clearVoiceApiKey'>清空语音 Key</label></div></div>")
                .append(rowInput("ASR 地址", "asrUrl", "text", "ws://host:port 或 http://host:port/"))
                .append(rowInput("最终 ASR", "asrFinalUrl", "text", "http://host:port/ 或 /api/v1/asr"))
                .append(rowInput("ASR 模型", "asrModel", "text", "whisper-1 / 服务默认"))
                .append(rowCheckbox("常驻监听", "vadEnabled", "启动后持续听外部语音"))
                .append(rowSelect("参与模式", "interactionMode", null, "<option value='quiet'>安静</option><option value='natural'>自然</option><option value='active'>积极</option>", null))
                .append(rowInput("VAD 灵敏度", "vadSensitivity", "text", "600"))
                .append(rowSelect("打断模式", "bargeMode", null, "<option value='steady'>稳健</option><option value='sensitive'>灵敏</option><option value='off'>关闭</option>", null))
                .append("<p class='hint'>自然/积极模式会在闲聊中判断是否接话；安静模式更像传统语音助手。</p>")
                .append("<div class='inline'><button type='button' onclick=\"runTest('asr')\">测试 Final ASR</button><span class='state' id='testAsr'></span></div>")
                .append("</div><div class='box'><h3>TTS</h3>")
                .append(rowInput("TTS 地址", "ttsUrl", "text", "http://host:port/"))
                .append(rowInput("TTS 模型", "ttsModel", "text", "cosyvoice-v3"))
                .append("<div class='row'><label>TTS 音色</label><div><input type='text' name='ttsVoice' list='ttsVoiceList'><datalist id='ttsVoiceList'></datalist></div></div>")
                .append(rowCheckbox("本地优先", "localTtsFirst", "关闭时不静默切本地 TTS"))
                .append("<div class='inline'><button type='button' onclick=\"runTest('voices')\">查询音色</button><button type='button' onclick=\"runTest('tts')\">测试 TTS</button><button type='button' class='secondary' onclick='openTtsSelfTest()'>打开设备自检页</button><span class='state' id='testTts'></span></div>")
                .append("<h3 style='margin-top:16px'>语音系统提示词</h3><textarea name='sysPromptVoice'></textarea>")
                .append("</div></div></section>")
                .append("<section class='panel' id='tab-mcp'><div class='sectionTitle'><h2>MCP 工具</h2><small>远程 Streamable HTTP 工具由模型自动调用</small></div><div class='cols'><div class='box'><h3>总控</h3>")
                .append(rowCheckbox("启用 MCP", Prefs.K_MCP_ENABLED, "语音对话和 Web 测试允许模型调用工具"))
                .append(rowInput("最大工具轮次", Prefs.K_MCP_MAX_TOOL_ROUNDS, "text", "3"))
                .append(rowCheckbox("慢工具提示", Prefs.K_MCP_SLOW_HINT_ENABLED, "工具慢时用 TTS 播短提示"))
                .append(rowInput("首次提示(ms)", Prefs.K_MCP_SLOW_HINT_MS, "text", "900"))
                .append(rowInput("提示节点(ms)", Prefs.K_MCP_SLOW_HINT_SCHEDULE_MS, "text", "900,2600,3800,4700,5300"))
                .append(rowInput("最多提示", Prefs.K_MCP_SLOW_HINT_MAX_COUNT, "text", "5"))
                .append("<h3 style='margin-top:16px'>提示语池</h3><textarea name='")
                .append(Prefs.K_MCP_SLOW_HINT_PHRASES)
                .append("' style='min-height:220px' placeholder='一行一句'></textarea><p class='hint'>提示语不会写入对话记忆，只用于等待工具结果时缓解空档。</p>")
                .append("</div><div class='box'><h3>远程服务器</h3>")
                .append(mcpServerBox(1))
                .append(mcpServerBox(2))
                .append(mcpServerBox(3))
                .append("<div class='inline'><button type='button' onclick=\"runMcp('refresh')\">刷新工具</button><button type='button' class='secondary' onclick=\"runMcp('test')\">测试连接</button><span class='state' id='mcpState'></span></div><pre id='mcpTools'></pre>")
                .append("<h3 style='margin-top:16px'>实际工具调用</h3><div class='row'><label>工具</label><input id='mcpToolName' placeholder='从上方工具列表复制完整名称'></div><div class='row'><label>参数 JSON</label><textarea id='mcpToolArgs' style='min-height:72px'>{}</textarea></div><div class='inline'><button type='button' onclick='runMcpCall()'>调用工具</button><span class='state' id='mcpCallState'></span></div><pre id='mcpCallResult'></pre>")
                .append("<p class='hint'>URL 可填 http(s)://host:port 或完整 /mcp；Bearer Token 留空表示保存时保持旧值。</p>")
                .append("</div></div></section>")
                .append("<section class='panel' id='tab-vision'><div class='sectionTitle'><h2>视觉 / 摄像头</h2><small>默认折叠高开销推流预览</small></div><div class='cols'><div class='box'><h3>灵眼</h3>")
                .append(rowInput("视觉间隔秒", "visionInterval", "text", "2"))
                .append(rowSelect("灵眼画面源", "visionFrameSource", null, "<option value='hal'>HAL直出</option><option value='rtsp'>RTSP同源</option>", null))
                .append(rowSelect("灵眼显示", "visionOverlayStyle", null, "<option value='mechanical'>机械灵眼</option><option value='plain'>纯净相机</option>", null))
                .append("<h3 style='margin-top:16px'>显示性能</h3>")
                .append(rowSelect("主屏渲染", "mainRenderer", null, "<option value='gl'>OpenGL</option><option value='canvas'>Canvas</option>", null))
                .append(rowSelect("帧率策略", "mainFpsMode", null, "<option value='adaptive'>自适应</option><option value='power'>省电</option><option value='smooth'>流畅</option>", null))
                .append(rowSelect("屏幕策略", "screenPolicy", null, "<option value='plugged'>插电常亮 · 拔电自动熄屏</option><option value='always'>始终常亮</option><option value='sleep'>始终自动熄屏</option>", null))
                .append("<p class='hint'>保存后回到主屏生效；Web 预览开启或低电量时会自动压低主屏刷新。</p>")
                .append("<h3 style='margin-top:16px'>坤 · 占卜</h3>")
                .append(rowRange("起卦力度", Prefs.K_ORACLE_SHAKE_FORCE, 0, 100, "0 最灵敏，100 最用力；数值越大越难触发"))
                .append("<h3 style='margin-top:16px'>视觉系统提示词</h3><textarea name='sysPromptVision'></textarea>")
                .append("</div><div class='box'><h3>摄像头推流</h3>")
                .append(rowSelect("摄像头", "camId", null, "<option value='0'>后置</option><option value='1'>前置</option>", null))
                .append("<div class='row'><label>分辨率</label><div class='inline'><select name='camWidth'><option>640</option><option>800</option><option>1280</option></select><select name='camHeight'><option>480</option><option>800</option><option>720</option></select></div></div>")
                .append(rowSelect("帧率", "camFps", null, "<option>24</option>", null))
                .append(rowSelect("码率(Kbps)", "camBitrate", null, "<option>2000</option><option>4000</option><option>5000</option><option>6000</option><option>8000</option><option>12000</option><option>20000</option>", null))
                .append(rowInput("RTSP 端口", "rtspPort", "text", "8554"))
                .append(rowInput("RTMP 地址", "rtmpUrl", "text", "rtmp://VPS:1935/cam/stream"))
                .append(rowCheckbox("开机推流", "camAutoStart", "应用启动时自动开始摄像头推流"))
                .append("<div class='inline'><button type='button' onclick='camToggle()' id='camBtn'>启动推流</button><span class='state' id='camMsg'></span></div><p class='hint'>状态：<span id='camState'>未知</span></p><div class='hint' id='camUrls'></div>")
                .append("</div></div><details><summary>屏幕推流与远程触摸</summary><div class='cols'><div class='box'>")
                .append(rowSelect("推流方式", "streamMode", null, "<option value='h264'>H.264 硬编</option><option value='h264fast'>H.264 高速</option><option value='mjpeg'>MJPEG 兼容</option>", null))
                .append(rowSelect("帧率(fps)", "streamFps", null, "<option>1</option><option>2</option><option>3</option><option>5</option>", null))
                .append(rowSelect("码率(Kbps)", "streamBitrate", null, "<option value='600'>600</option><option value='1000'>1000</option><option value='1500'>1500</option><option value='2500'>2500</option><option value='4000'>4000</option><option value='6000'>6000</option><option value='8000'>8000</option>", null))
                .append(rowSelect("画质(MJPEG)", "streamQuality", null, "<option value='30'>低</option><option value='55'>中</option><option value='75'>高</option>", null))
                .append(rowSelect("尺寸(MJPEG)", "streamScale", null, "<option value='2'>半尺寸</option><option value='1'>原始</option>", null))
                .append("<div class='inline'><button type='button' onclick='toggleStream()' id='sbtn'>开始屏幕预览</button><button type='button' class='secondary' onclick='keyEvent(4)'>返回</button><button type='button' class='secondary' onclick='keyEvent(3)'>桌面</button><button type='button' class='secondary' onclick='keyEvent(187)'>最近</button></div><p class='state' id='sstate'></p>")
                .append("</div><div class='box'><div class='screenwrap'><video id='h264v' class='preview' muted autoplay playsinline style='display:none'></video><img id='screen' class='preview' style='display:none'><div id='touchpad' class='touchpad'></div></div><p class='hint' id='tstat'>按当前推流方式预览；触摸层支持点击/拖动圆面。</p></div></div></details>")
                .append("</section>")
                .append("<section class='panel' id='tab-browser'><div class='sectionTitle'><h2>浏览器 / 网盘</h2><small>圆屏浏览和网络文件配置</small></div><div class='cols'><div class='box'><h3>浏览器</h3>")
                .append(rowInput("搜索引擎", "searchEngine", "text", "https://www.bing.com/search?q=%s"))
                .append(rowCheckbox("圆屏适配", "browserRoundFit", "网页默认避开圆屏四角"))
                .append(rowCheckbox("无图模式", "noImages", "减少加载流量"))
                .append(rowCheckbox("桌面 UA", "uaDesktop", "请求桌面版网页"))
                .append(rowCheckbox("忽略 SSL", "ignoreSsl", "仅用于证书异常站点"))
                .append("<h3 style='margin-top:16px'>粗定位</h3>")
                .append(rowSelect("定位来源", Prefs.K_LOC_SOURCE, null, "<option value='off'>关闭</option><option value='wifi_ip'>WiFi-IP</option><option value='gps_diag'>GPS诊断</option>", null))
                .append(rowInput("WiFi 定位", "locWifiUrl", "text", "留空=跳过 WiFi BSSID 定位"))
                .append(rowInput("IP 粗定位", "locIpUrl", "text", "留空=使用默认 IP 粗定位"))
                .append("<p class='hint'>WiFi/IP 只用于大概城市/街区；GPS 默认关闭省电。默认 IP 粗定位不需要 GPS，也不需要 SIM。</p>")
                .append("</div><div class='box'><h3>网盘连接</h3><div id='fsList' class='list'></div>")
                .append("<h3 style='margin-top:14px'>编辑连接</h3><input type='hidden' id='fsId'>")
                .append(fsRow("名称", "fsName", "自动命名可留空"))
                .append("<div class='row'><label>类型</label><select id='fsType'><option>FTP</option><option>WebDAV</option><option>SMB</option><option>NFS</option></select></div>")
                .append(fsRow("主机", "fsHost", "dav.example.com"))
                .append(fsRow("端口", "fsPort", "WebDAV 默认 443"))
                .append(fsRow("用户", "fsUser", ""))
                .append("<div class='row'><label>密码</label><input type='password' id='fsPass' placeholder='留空=保持旧密码'></div>")
                .append(fsRow("根路径", "fsRoot", "/dav 或留空"))
                .append(fsRow("SMB 域", "fsDomain", "可留空"))
                .append("<div class='inline'><button type='button' onclick='fsSave()'>保存连接</button><button type='button' class='secondary' onclick='fsTest()'>测试连接</button><button type='button' class='secondary' onclick='fsNew()'>新建</button><span class='state' id='fsMsg'></span></div>")
                .append("</div></div></section>")
                .append("<section class='panel' id='tab-remote'><div class='sectionTitle'><h2>FRP / ADB</h2><small>远程访问与安装通道</small></div><div class='cols'><div class='box'><h3>frpc.toml</h3><textarea name='frpcConfig' style='min-height:260px'></textarea><p class='hint'>保存后点启动 frpc。APK 安装优先走应用管理上传，不走 adb install 传文件。</p><div class='inline'><button type='button' onclick='frpcStart()'>启动 frpc</button><button type='button' class='secondary' onclick='frpcStop()'>停止 frpc</button><span class='state' id='frpcMsg'></span></div><p class='hint'>状态：<span id='frpcState'>未知</span><span id='frpcStateDetail'></span></p><pre id='frpcLog'></pre></div>")
                .append("<div class='box'><h3>ADB TCP</h3><div class='row'><label>ADB 端口</label><input type='text' id='adbPort' value='5555'></div><label class='checkrow'><input type='checkbox' id='adbAuto'>开机自启 ADB TCP</label>")
                .append(rowCheckbox("Root 授权提示", Prefs.K_ROOT_GRANT_NOTIFICATIONS, "关闭后隐藏 Magisk 的“应用已授予超级权限”提示（全局）"))
                .append("<p class='hint' id='rootGrantActual'>Magisk 数据库状态：读取中</p>")
                .append(rowCheckbox("启用系统锁屏", Prefs.K_SYSTEM_LOCKSCREEN_ENABLED, "默认关闭；开启后下一次亮屏恢复原厂滑动锁"))
                .append("<p class='hint' id='lockscreenActual'>LockSettingsService：读取中</p>")
                .append(rowCheckbox("低电量提示音", Prefs.K_LOW_BATTERY_SOUND, "默认关闭；只控制系统低电量声音，不影响电量环和充电状态"))
                .append("<p class='hint' id='lowBatterySoundActual'>系统声音状态：读取中</p>")
                .append("<div class='inline'><button type='button' onclick='adbSave()'>保存自启</button><button type='button' onclick='adbStart()'>启动/重启 ADB TCP</button><button type='button' class='secondary' onclick='adbStop()'>关闭 ADB TCP</button><button type='button' class='danger' onclick='deviceReboot()'>重启设备</button><span class='state' id='adbMsg'></span></div><p class='hint'>设备侧：<span id='adbState'>未知</span><span id='adbDetail'></span></p><pre id='adbLog'></pre></div></div></section>")
                .append("<section class='panel' id='tab-apps'><div class='sectionTitle'><h2>应用管理</h2><small>上传 APK 后设备本地安装</small></div><div class='cols'><div class='box'><h3>安装 APK</h3><div class='row'><label>APK 文件</label><input type='file' id='appApk' accept='.apk,application/vnd.android.package-archive'></div><div class='row'><label>APK 下载地址</label><input type='text' id='appFetchUrl' placeholder='https://.../app.apk 或 ftp://...'></div><div class='inline'><button type='button' onclick='appUpload()'>上传 APK</button><button type='button' class='secondary' onclick='appFetch()'>从 URL 拉取</button><button type='button' id='appInstallBtn' onclick='appInstall()' disabled>安装上传的 APK</button></div><p class='state' id='appUploadMsg'></p><p class='hint' id='appUploadInfo'></p><pre id='appTaskLog'></pre></div>")
                .append("<div class='box'><h3>已安装应用</h3><div class='row'><label>搜索应用</label><input type='text' id='appSearch' oninput='renderApps()'></div><div class='inline'><button type='button' class='secondary' onclick='loadApps()'>刷新应用</button><span class='hint' id='appCount'></span></div><div id='appList' class='list'></div></div></div></section>")
                .append("<section class='panel' id='tab-debug'><div class='sectionTitle'><h2>链路调试</h2><small>按时间记录 ASR / LLM / MCP / TTS</small></div><div class='cols'><div class='box'><h3>调试模式</h3>")
                .append(rowCheckbox("开启调试", Prefs.K_DEBUG_MODE, "记录语音文本、LLM 请求摘要、工具调用和返回结果"))
                .append(rowCheckbox("语音诊断浮层", Prefs.K_VOICE_DIAGNOSTIC_OVERLAYS, "在主屏和灵眼显示 ASR、LLM、TTS 状态与错误；日常建议关闭"))
                .append(rowInput("日志上限(KB)", Prefs.K_DEBUG_MAX_KB, "text", "4096"))
                .append("<p class='hint'>调试日志可能包含你说的话、模型请求内容和工具返回内容；不用排障时建议关闭。</p>")
                .append("<div class='inline'><button type='button' onclick='loadDebugLog()'>刷新日志</button><button type='button' class='danger' onclick='clearDebugLog()'>清空调试日志</button><span class='state' id='debugMsg'></span></div>")
                .append("</div><div class='box'><h3>时间线</h3><div id='debugLog' class='log' style='max-height:560px'></div></div></div></section>")
                .append("<section class='panel' id='tab-records'><div class='sectionTitle'><h2>记录 / 备份</h2><small>排障与恢复</small></div><div class='cols'><div class='box'><h3>对话记录</h3>")
                .append(rowInput("大小上限(KB)", "convMaxKb", "text", "4096"))
                .append(rowInput("清理间隔(分钟)", "convCleanMin", "text", "60"))
                .append("<div class='row'><label>过滤角色</label><select id='convFilter' onchange='loadConv()'><option value='all'>全部</option><option value='user'>用户</option><option value='assistant'>AI</option><option value='heard'>听见</option><option value='error'>错误</option></select></div><div class='inline'><button type='button' class='danger' onclick='clearConv()'>清空记录</button><span class='state' id='convMsg'></span></div><div id='conv' class='log'></div></div>")
                .append("<div class='box'><h3>配置备份</h3><div class='inline'><button type='button' onclick='backupExport()'>导出当前配置</button><button type='button' class='secondary' onclick='backupRestore()'>从下面内容恢复</button><span class='state' id='backupMsg'></span></div><textarea id='backupContent' placeholder='导出后会显示 JSON；也可以粘贴旧 prefs.json 后恢复。' style='min-height:260px'></textarea></div>")
                .append("<div class='box'><h3>启动资源</h3><p class='hint'>只允许备份、下载、上传和校验 MTK logo 分区；不会开放任意 Root 命令。刷写前必须有原厂备份、电量达标并输入确认短语。</p><div id='bootAssetState' class='log'>尚未读取</div><div class='inline'><button type='button' onclick='bootAssetBackup()'>创建原厂备份</button><button type='button' class='secondary' onclick='bootAssetDownload(\"logo\")'>下载 logo 备份</button><button type='button' class='secondary' onclick='bootAssetDownload(\"bootanimation\")'>下载原厂开机动画</button><button type='button' class='secondary' onclick='bootAssetDownload(\"shutanimation\")'>下载原厂关机动画</button><button type='button' class='secondary' onclick='bootAssetDownload(\"manifest\")'>下载清单</button><button type='button' class='secondary' onclick='bootLogLoad()'>启动日志</button></div><pre id='bootLog' style='display:none'></pre><div class='row'><label>新 logo.bin</label><input type='file' id='bootLogoFile' accept='.bin,application/octet-stream'></div><div class='inline'><button type='button' class='secondary' onclick='bootLogoUpload()'>上传并校验</button><button type='button' class='danger' onclick='bootLogoFlash()'>刷写新首屏</button><button type='button' class='secondary' onclick='bootLogoRestore()'>恢复原厂首屏</button><span class='state' id='bootAssetMsg'></span></div></div></div></section>")
                .append("</form></div></div>")
                .append("<div class='savebar hidden' id='savebar'><div class='inner'><div><b id='dirtyState'>未修改</b><div class='hint'>保存前自动导出备份；Key 留空不会覆盖旧值。</div></div><div><button type='button' onclick='save()'>保存设置</button><span class='state' id='msg'></span></div></div></div>")
                .append("<script>")
                .append("var token=sessionStorage.getItem('appmgrToken')||'',started=false,dirty=false,appApps=[],fsConns=[],convCache=null,streamOn=false;")
                .append("var streamEnded=false,mse=null,sb=null,abortCtl=null,watchdog=null,sess=0,streamPoll=null;")
                .append("var boxBuf=new Uint8Array(0),boxOff=0,initDone=false,appending=false,pending=[],gotData=false;")
                .append("function q(s){return document.querySelector(s)}function qa(s){return document.querySelectorAll(s)}")
                .append("function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\\\"/g,'&quot;').replace(/'/g,'&#39;')}")
                .append("function enc(o){var a=[];for(var k in o)a.push(encodeURIComponent(k)+'='+encodeURIComponent(o[k]==null?'':o[k]));return a.join('&')}")
                .append("function hdr(x){if(token)x.setRequestHeader('X-AppMgr-Token',token)}")
                .append("function api(m,u,b,cb,ct){var x=new XMLHttpRequest();x.open(m,u,true);hdr(x);if(ct)x.setRequestHeader('Content-Type',ct);x.onload=function(){var d=null;try{d=JSON.parse(x.responseText)}catch(e){d={ok:false,err:x.responseText||'请求失败'}}if(cb)cb(d,x)};x.onerror=function(){if(cb)cb({ok:false,err:'网络错误'},x)};x.send(b||null)}")
                .append("function textApi(m,u,b,cb,ct){var x=new XMLHttpRequest();x.open(m,u,true);hdr(x);if(ct)x.setRequestHeader('Content-Type',ct);x.onload=function(){if(cb)cb(x.responseText,x)};x.onerror=function(){if(cb)cb('网络错误',x)};x.send(b||null)}")
                .append("function chip(id,text,ok,bad){var e=q('#'+id);if(!e)return;e.textContent=text||'未知';var c=e.parentNode;c.className='chip'+(ok?' ok':'')+(bad?' bad':'')}")
                .append("var publicTimer=null;function boot(){wireTabs();wireDirty();wireAuthKeys();publicStatus();publicTimer=setInterval(function(){if(!document.hidden)publicStatus()},5000)}")
                .append("function authState(){api('GET','/appmgr/state',null,function(d){var p=q('#authPanel'),c=q('#console'),b=q('#authBadge'),h=q('#authHelp');if(!d||!d.ok){b.textContent='未登录';return}if(!d.hasPassword){h.textContent='首次使用请设置管理密码';b.textContent='未设置密码';p.classList.remove('hidden');c.classList.add('hidden');return}if(d.authed){b.textContent='已登录';p.classList.add('hidden');c.classList.remove('hidden');q('#savebar').classList.remove('hidden');if(!started){started=true;initConsole()}}else{b.textContent='未登录';p.classList.remove('hidden');c.classList.add('hidden');q('#savebar').classList.add('hidden')}})}")
                .append("function appLogin(){api('POST','/appmgr/login',enc({password:q('#appPwd').value}),function(d){if(d&&d.ok){token=d.token;sessionStorage.setItem('appmgrToken',token);q('#appPwd').value='';msg('appAuth','已登录');authState()}else msg('appAuth',d&&d.err?d.err:'登录失败')},'application/x-www-form-urlencoded')}")
                .append("function appSetup(){api('POST','/appmgr/setup',enc({password:q('#appPwd').value,oldPassword:q('#appOldPwd').value}),function(d){if(d&&d.ok){token=d.token;sessionStorage.setItem('appmgrToken',token);q('#appPwd').value='';q('#appOldPwd').value='';msg('appAuth','管理密码已保存');authState()}else msg('appAuth',d&&d.err?d.err:'设置失败')},'application/x-www-form-urlencoded')}")
                .append("function wireAuthKeys(){var p=q('#appPwd'),o=q('#appOldPwd');if(p)p.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();appLogin()}});if(o)o.addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();appSetup()}})}")
                .append("function msg(id,t){var e=q('#'+id);if(e)e.textContent=t||''}")
                .append("function publicStatus(){api('GET','/status',null,function(d){if(d){chip('ovVad',d.vadEnabled?'开启':'关闭',!!d.vadEnabled,!d.vadEnabled);chip('ovAsr',d.asrUrlSet?'已配置':'未配置',!!d.asrUrlSet,!d.asrUrlSet);chip('ovAsrFinal',d.asrFinalUrlSet?'已配置':'未配置',!!d.asrFinalUrlSet,false);chip('ovLlm',d.apiKeySet?d.apiKeyMask:'未设置',!!d.apiKeySet,!d.apiKeySet);chip('ovTts',d.ttsUrlSet?'已配置':'未配置',!!d.ttsUrlSet,!d.ttsUrlSet)}});if(!started){api('GET','/frpc/status',null,function(d){if(d)chip('ovFrpc',d.status==='running'?'运行中':(d.status==='error'?'异常':'停止'),d.status==='running',d.status==='error')});api('GET','/adb/status',null,function(d){if(!d)return;var h=d.health||'checking',t=h==='healthy'?'服务正常':(h==='degraded'?'连接积压':(h==='down'?'未监听':'检测中'));chip('ovAdb',t,h==='healthy',h==='degraded'||h==='down')})}}")
                .append("function pct(v){v=Number(v);return v>=0?(Math.round(v)+'%'):'--'}function metric(v,t){return t?t:pct(v)}function meter(n,v,t){var e=q('#sys'+n),b=q('#sys'+n+'Bar');if(e)e.textContent=metric(v,t);if(b)b.style.width=(Number(v)>=0?Math.max(0,Math.min(100,Number(v))):0)+'%'}")
                .append("function renderHwDiag(h){var box=q('#hwDiag');if(!box)return;h=h||{};var rows=[['传感器',h.sensors||'--'],['姿态',h.pose||'--'],['磁场',h.magnetic||'--'],['GPS请求',h.gpsRequest||'--'],['GPS驱动',h.gpsDriver||'--'],['GPS动作',h.gpsAction||'--'],['卫星',h.gps||'--'],['弱项',h.untrusted||'--'],['校准',h.magCalibration||'--']];var out='';for(var i=0;i<rows.length;i++)out+='<div class=\"item\"><div class=\"main\"><b>'+esc(rows[i][0])+'</b><small>'+esc(rows[i][1])+'</small></div></div>';box.innerHTML=out}")
                .append("function gpsReset(){if(!confirm('清理 GPS 辅助数据并重新搜星？建议在室外空旷处使用。'))return;msg('gpsResetMsg','正在触发...');api('POST','/gps/reset','',function(d){msg('gpsResetMsg',d&&d.ok?(d.msg||'已触发'):(d&&d.err?d.err:'失败'));setTimeout(systemStatus,800)},'application/x-www-form-urlencoded')}")
                .append("function systemStatus(){api('GET','/system_status',null,renderSystem)}function renderSystem(d){if(!d)return;q('#sysTime').textContent=d.time||'--:--';q('#sysDate').textContent=d.date||'';meter('Cpu',d.cpu);meter('AppCpu',d.appCpu);meter('Ram',d.memPct);meter('Gpu',d.gpu,d.gpuText);meter('Bat',d.battery,d.batteryText);renderHwDiag(d.hardware);var bat=d.batteryText||pct(d.battery),gt=d.gpuText||pct(d.gpu),core=(d.cpuOnline&&d.cpuPossible)?(' · 核 '+d.cpuOnline+'/'+d.cpuPossible):'',appCore=(Number(d.appCpuCore)>=0?(' · 单核 '+pct(d.appCpuCore)):''),renderer=(d.mainRenderer==='canvas'?'Canvas':'OpenGL'),fps=d.mainFpsMode==='power'?'省电':(d.mainFpsMode==='smooth'?'流畅':'自适应');q('#sysCore').textContent='总 '+pct(d.cpu)+' · App '+pct(d.appCpu)+appCore+core+' · RAM '+pct(d.memPct)+' · Mali '+gt+' · 电 '+bat+' · '+renderer+'/'+fps;q('#sysGps').textContent=d.gps||'';q('#sysUpdated').textContent='更新 '+(d.time||'');var note='loadavg '+(d.loadAvg1||'--')+' / '+(d.loadAvg5||'--')+' / '+(d.loadAvg15||'--')+' · 可运行 '+(d.runnable==null?'--':d.runnable)+' · D状态 '+(d.blockedThreads==null?'--':d.blockedThreads)+' · App线程 '+(d.appThreads==null?'--':d.appThreads);if(d.streamActive)note+=' · 屏幕预览会增加 CPU/温度';if(d.wifiScanAgeMs>=0)note+=' · Wi-Fi扫描 '+Math.round(d.wifiScanAgeMs/60000)+'分钟前';if(d.conversationBytes!=null)note+=' · 日志 '+Math.round(d.conversationBytes/1024)+'KB';q('#sysLoadNote').textContent=note;var temps=d.temps||[],ring=q('#sysTempRing');if(!ring)return;ring.innerHTML='';for(var i=0;i<temps.length;i++){var el=document.createElement('div');el.className='tempdot';el.innerHTML='<span>'+esc(temps[i].name||'温度')+'</span><b>'+Number(temps[i].c||0).toFixed(0)+'°</b>';ring.appendChild(el)}if(!temps.length){var e=document.createElement('div');e.className='tempdot';e.innerHTML='<span>温度</span><b>--</b>';ring.appendChild(e)}}")
                .append("var consoleTimer=null;function stopConsolePolling(){if(consoleTimer){clearInterval(consoleTimer);consoleTimer=null}}function startConsolePolling(){stopConsolePolling();if(document.hidden)return;consoleTimer=setInterval(function(){if(document.hidden)return;frpcRefresh();camRefresh();adbStatus();appState();systemStatus();if(activeTab('records'))loadConv();if(activeTab('debug'))loadDebugLog();},5000)}function activeTab(id){var e=q('#tab-'+id);return !!(e&&e.className.indexOf('active')>=0)}function refreshTabData(id){if(id==='records')loadConv();else if(id==='debug')loadDebugLog();else if(id==='apps')loadApps();else if(id==='files')fsList()}function initConsole(){loadStatus();lowBatterySoundStatus();systemStatus();frpcRefresh();camRefresh();adbStatus();appState();bootAssetStatus();fsList();startConsolePolling()}")
                .append("function wireTabs(){var bs=qa('.tab');for(var i=0;i<bs.length;i++)bs[i].onclick=function(){var id=this.getAttribute('data-tab');var b=qa('.tab'),p=qa('.panel');for(var j=0;j<b.length;j++)b[j].classList.remove('active');for(var k=0;k<p.length;k++)p[k].classList.remove('active');this.classList.add('active');q('#tab-'+id).classList.add('active');refreshTabData(id)}}document.addEventListener('visibilitychange',function(){if(document.hidden)stopConsolePolling();else{publicStatus();if(started){startConsolePolling();refreshTabData('records')}}});")
                .append("function wireDirty(){var f=q('#f');if(!f)return;var es=f.querySelectorAll('input,textarea,select');for(var i=0;i<es.length;i++){es[i].addEventListener('input',markDirty);es[i].addEventListener('change',markDirty)}}")
                .append("function markDirty(){dirty=true;q('#dirtyState').textContent='有未保存修改';q('#dirtyState').style.color='var(--gold)'}function clean(){dirty=false;q('#dirtyState').textContent='已保存';q('#dirtyState').style.color='var(--ok)'}")
                .append("function loadStatus(){api('GET','/status',null,function(d){if(!d||!d.authed){authState();return}for(var k in d){var e=q('[name=\"'+k+'\"]');if(!e)continue;var mcpTok=k.indexOf('mcpServer')===0&&k.indexOf('Token')>=0;if(e.type==='checkbox')e.checked=(d[k]===true||d[k]==='true');else if(k!=='apiKey'&&k!=='voiceApiKey'&&!mcpTok)e.value=d[k]}q('[name=apiKey]').value='';q('[name=voiceApiKey]').value='';msg('apiKeyState',d.apiKeySet?'当前 '+d.apiKeyMask:'当前未设置');msg('voiceKeyState',d.voiceApiKeySet?'当前 '+d.voiceApiKeyMask:'当前未设置');var rg=d.rootGrantStatus||{},ls=d.systemLockscreenStatus||{};msg('rootGrantActual','Magisk 数据库：'+(rg.detail||'状态未知'));msg('lockscreenActual','LockSettingsService：'+(ls.detail||'状态未知')+(ls.secureCredential?' · 检测到安全凭据':''));for(var i=1;i<=3;i++){var p='mcpServer'+i,s=q('[name='+p+'Token]');if(s)s.value='';msg(p+'TokenState',d[p+'TokenSet']?'当前 '+d[p+'TokenMask']:'当前未设置')}syncRanges();clean()})}")
                .append("function lowBatterySoundStatus(){api('GET','/status',null,function(d){if(!d||!d.authed)return;var s=d.lowBatterySoundStatus||{};msg('lowBatterySoundActual','系统声音：'+(s.detail||'状态未知'))})}")
                .append("function providerChanged(){var p=q('#provider').value;if(p==='deepseek'){q('[name=baseUrl]').value='https://api.deepseek.com/v1';q('[name=textModel]').value='deepseek-chat';if(!q('[name=visionModel]').value)q('[name=visionModel]').value='deepseek-chat';q('[name=textBaseUrl]').value='';q('[name=visionBaseUrl]').value=''}else{if(q('[name=baseUrl]').value==='https://api.deepseek.com/v1')q('[name=baseUrl]').value='';if(!q('[name=textModel]').value||q('[name=textModel]').value==='deepseek-chat')q('[name=textModel]').value='gpt-4.1-mini'}}")
                .append("function oracleShakeLabel(v){v=Math.max(0,Math.min(100,Number(v||70)));var n=v<18?'轻摇':(v<38?'稍轻':(v<62?'正常':(v<82?'较重':'用力')));return Math.round(v)+' / 100 · '+n}function syncRanges(){var e=q('[name=oracleShakeForce]'),t=q('#oracleShakeForceText');if(e&&t)t.textContent=oracleShakeLabel(e.value)}")
                .append("function save(){var fd=new FormData(q('#f')),b=new URLSearchParams(fd),c=q('#f').querySelectorAll('input[type=checkbox]');for(var i=0;i<c.length;i++)b.set(c[i].name,c[i].checked?'true':'false');textApi('POST','/save',b.toString(),function(t){msg('msg',t);if(t.indexOf('已保存')>=0){q('[name=apiKey]').value='';q('[name=voiceApiKey]').value='';for(var j=1;j<=3;j++){var te=q('[name=mcpServer'+j+'Token]');if(te)te.value=''}q('[name=clearApiKey]').checked=false;q('[name=clearVoiceApiKey]').checked=false;clean();loadStatus()}},'application/x-www-form-urlencoded')}")
                .append("function runTest(kind){var map={llm:['/test/llm','testLlm'],asr:['/test/asr_final','testAsr'],tts:['/test/tts','testTts'],voices:['/test/tts_voices','testTts']},m=map[kind];if(!m)return;msg(m[1],'测试中...');api('GET',m[0],null,function(d){if(kind==='voices'&&d&&d.ok){var dl=q('#ttsVoiceList');dl.innerHTML='';for(var i=0;i<(d.voices||[]).length;i++){var op=document.createElement('option');op.value=d.voices[i];dl.appendChild(op)}msg(m[1],'音色 '+(d.voices||[]).length+' 个 · '+d.ms+'ms');return}msg(m[1],d&&d.ok?('成功 '+(d.ms||0)+'ms '+(d.text||d.contentType||'')+(d.bytes?(' · '+fmtBytes(d.bytes)):'')+(d.retry?' · 已重试':'')+(d.note?' · '+d.note:'')):('失败 '+(d&&d.err?d.err:'未知错误')))})}")
                .append("function runMcp(kind){var u=kind==='refresh'?'/mcp/refresh':'/mcp/test';msg('mcpState',kind==='refresh'?'刷新中...':'测试中...');api('POST',u,'',function(d){if(!d){msg('mcpState','无返回');return}var ts=d.tools||[],ss=d.servers||[],lines=[];for(var i=0;i<ss.length;i++){lines.push((ss[i].ok===false?'! ':'✓ ')+(ss[i].name||ss[i].id)+' · '+(ss[i].url||'')+' · 工具 '+(ss[i].toolCount||0)+(ss[i].err?(' · '+ss[i].err):''))}for(var j=0;j<ts.length;j++){lines.push('  - '+ts[j].name+' · '+(ts[j].description||''))}if(d.errors&&d.errors.length)lines.push('错误: '+d.errors.join('；'));q('#mcpTools').textContent=lines.join('\\n')||'未发现工具';msg('mcpState',d.ok?('完成 · '+ts.length+' 个工具 · '+(d.ms||0)+'ms'):(d.err||'失败'))},'application/x-www-form-urlencoded')}")
                .append("function runMcpCall(){var n=(q('#mcpToolName').value||'').trim(),a=(q('#mcpToolArgs').value||'{}').trim();if(!n){msg('mcpCallState','请先刷新并填写工具名');return}try{JSON.parse(a)}catch(e){msg('mcpCallState','参数不是合法 JSON');return}msg('mcpCallState','调用中...');api('POST','/mcp/call',enc({toolName:n,toolArgs:a}),function(d){if(!d){msg('mcpCallState','无返回');return}q('#mcpCallResult').textContent=d.text||d.err||'';msg('mcpCallState',d.ok?('成功 · '+(d.ms||0)+'ms'):('失败 · '+(d.ms||0)+'ms'))},'application/x-www-form-urlencoded')}")
                .append("function openTtsSelfTest(){msg('testTts','正在打开设备页面...');api('POST','/open/tts_test','',function(d){msg('testTts',d&&d.ok?'已打开设备自检页':('打开失败 '+(d&&d.err?d.err:'未知错误')))},'application/x-www-form-urlencoded')}")
                .append("function frpcRefresh(){api('GET','/frpc/status',null,function(d){if(!d)return;var label=d.status==='running'?'运行中':(d.status==='error'?'异常':'已停止');msg('frpcState',label);chip('ovFrpc',label,d.status==='running',d.status==='error');msg('frpcStateDetail',d.detail?' · '+d.detail:'');var l=q('#frpcLog');if(l){l.textContent=d.log||'';l.scrollTop=l.scrollHeight}})}")
                .append("function frpcStart(){textApi('GET','/frpc/start',null,function(t){msg('frpcMsg',t);frpcRefresh()})}function frpcStop(){textApi('GET','/frpc/stop',null,function(t){msg('frpcMsg',t);frpcRefresh()})}")
                .append("function adbStatus(){api('GET','/adb/status',null,function(d){if(!d)return;if(document.activeElement!==q('#adbPort'))q('#adbPort').value=d.port||5555;q('#adbAuto').checked=!!d.autoStart;var ap=d.activePort||d.servicePort||d.persistPort||'--',h=d.health||'checking',t=h==='healthy'?'服务正常':(h==='degraded'?'连接积压':(h==='down'?'未监听':'检测中'));msg('adbState',t);chip('ovAdb',t,h==='healthy',h==='degraded'||h==='down');msg('adbDetail',' · 端口 '+ap+' · adbd='+(d.daemonState||'--')+' · 协议='+(d.protocolDetail||'未检测')+' · 开机同步='+(d.tunnelSynchronized?'完成':'等待')+' · 连续失败 '+(d.consecutiveFailures||0)+' · CLOSE_WAIT '+(d.closeWaitSockets||0)+' · '+(d.lastCheckDetail||'')+' · 主机状态以 adb devices 为准');var l=q('#adbLog');if(l)l.textContent=d.log||''})}")
                .append("function adbBody(){return enc({port:q('#adbPort').value,autoStart:q('#adbAuto').checked?'true':'false'})}function adbSave(){api('POST','/adb/save',adbBody(),function(d){msg('adbMsg',d&&d.ok?'已保存':(d&&d.err?d.err:'失败'));adbStatus()},'application/x-www-form-urlencoded')}function adbStart(){if(!confirm('启动 ADB TCP 会重启 adbd，继续？'))return;api('POST','/adb/start',adbBody(),function(d){msg('adbMsg',d&&d.ok?(d.msg||'已启动'):(d&&d.err?d.err:'失败'));adbStatus()},'application/x-www-form-urlencoded')}function adbStop(){if(!confirm('关闭 ADB TCP 会断开远程 ADB，继续？'))return;api('POST','/adb/stop','',function(d){msg('adbMsg',d&&d.ok?(d.msg||'已关闭'):(d&&d.err?d.err:'失败'));setTimeout(adbStatus,1500)},'application/x-www-form-urlencoded')}")
                .append("function deviceReboot(){var c=prompt('设备会立即重启。输入 REBOOT DEVICE 确认：');if(c!=='REBOOT DEVICE')return;api('POST','/device/reboot',enc({confirm:c}),function(d){msg('adbMsg',d&&d.ok?'重启命令已提交':(d&&d.err?d.err:'重启失败'))},'application/x-www-form-urlencoded')}")
                .append("function camRefresh(){api('GET','/cam/status',null,function(d){if(!d)return;msg('camState',d.status==='running'?('运行中 · '+(d.detail||'')):d.status);q('#camBtn').textContent=d.status==='running'?'停止推流':'启动推流';q('#camUrls').innerHTML=(d.rtsp?'<div>RTSP: '+esc(d.rtsp)+'</div>':'')+(d.rtmpUrl?'<div>RTMP: '+esc(d.rtmpUrl)+'</div>':'')+(d.webrtc?'<div>WebRTC: '+esc(d.webrtc)+'</div>':'')+(d.realFps?'<div>实际帧率: '+esc(d.realFps)+' fps</div>':'')})}")
                .append("function camToggle(){api('GET','/cam/status',null,function(d){if(d&&d.status==='running')textApi('GET','/cam/stop',null,function(t){msg('camMsg',t);setTimeout(camRefresh,500)});else textApi('GET','/cam/start',null,function(t){msg('camMsg',t);setTimeout(camRefresh,1500)})})}")
                .append("function currentMode(){var e=q('[name=streamMode]'),m=e?e.value:'h264';return m==='h264fast'||m==='mjpeg'?m:'h264'}")
                .append("function boxAt(pos){if(pos+8>boxBuf.length)return null;var size=((boxBuf[pos]<<24)|(boxBuf[pos+1]<<16)|(boxBuf[pos+2]<<8)|boxBuf[pos+3])>>>0;var type=String.fromCharCode(boxBuf[pos+4],boxBuf[pos+5],boxBuf[pos+6],boxBuf[pos+7]);return{size:size,type:type,start:pos}}")
                .append("function flushBoxes(my){while(true){if(!initDone){var a=boxAt(boxOff),b=boxAt(boxOff+(a?a.size:0));if(!a||!b||a.type!=='ftyp'||b.type!=='moov'||boxOff+a.size+b.size>boxBuf.length)break;pending.push(boxBuf.slice(boxOff,boxOff+a.size+b.size).buffer);boxOff+=a.size+b.size;initDone=true}else{var c=boxAt(boxOff),d=boxAt(boxOff+(c?c.size:0));if(!c||!d||c.type!=='moof'||d.type!=='mdat'||boxOff+c.size+d.size>boxBuf.length)break;pending.push(boxBuf.slice(boxOff,boxOff+c.size+d.size).buffer);boxOff+=c.size+d.size}if(boxOff===boxBuf.length){boxBuf=new Uint8Array(0);boxOff=0}pumpSb(my)}}")
                .append("function pumpSb(my){if(my!==sess||!mse||!sb||appending||!pending.length)return;try{appending=true;sb.appendBuffer(pending.shift())}catch(e){appending=false;pending=[];if(e.name!=='InvalidStateError')msg('sstate','MSE 追加失败：'+e)}}")
                .append("function startH264(){var video=q('#h264v'),img=q('#screen'),st=q('#sstate');if(!window.MediaSource){var sel=q('[name=streamMode]');if(sel)sel.value='mjpeg';if(video)video.style.display='none';if(img){img.src='/stream?access='+encodeURIComponent(token);img.style.display='block'}if(st)st.textContent='浏览器不支持 H.264 MSE，已切到 MJPEG';return}var my=++sess;boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];gotData=false;streamEnded=false;mse=new MediaSource();video.src=URL.createObjectURL(mse);mse.addEventListener('sourceopen',function(){if(my!==sess)return;var mySb=null;try{mySb=mse.addSourceBuffer('video/mp4; codecs=\\\"avc1.42E01E\\\"')}catch(e){try{mySb=mse.addSourceBuffer('video/mp4; codecs=\\\"avc1.4D401E\\\"')}catch(e2){msg('sstate','无法创建 H.264 解码器');return}}sb=mySb;mySb.mode='segments';mySb.addEventListener('updateend',function(){if(my!==sess){appending=false;pending=[];return}appending=false;pumpSb(my)});mySb.addEventListener('error',function(){if(my===sess)msg('sstate','MSE 错误：浏览器拒绝该媒体数据')});abortCtl=new AbortController();fetch((currentMode()==='h264fast'?'/h264fast':'/h264')+'?access='+encodeURIComponent(token),{signal:abortCtl.signal}).then(function(r){if(my!==sess)return;if(!r.ok||!r.body){msg('sstate','推流失败 '+r.status+'，请点停止后重试');return}var reader=r.body.getReader();function step(){reader.read().then(function(res){if(my!==sess)return;if(res.done){streamEnded=true;try{video.pause()}catch(e){}msg('sstate','推流已结束（保持最后一帧）');return}var nb=new Uint8Array(boxBuf.length+res.value.length);nb.set(boxBuf,0);nb.set(res.value,boxBuf.length);boxBuf=nb;flushBoxes(my);gotData=true;if(watchdog){clearTimeout(watchdog);watchdog=null}if(st&&st.textContent.indexOf('失败')<0&&st.textContent.indexOf('错误')<0)st.textContent=initDone?'H.264 推流中':'已连接，等待首帧…';video.play().catch(function(){});step()}).catch(function(e){if(e.name!=='AbortError'&&my===sess)msg('sstate','推流中断：'+e)})}step()}).catch(function(e){if(e.name!=='AbortError'&&my===sess)msg('sstate','推流失败：'+e)});watchdog=setTimeout(function(){if(my===sess&&!gotData&&streamOn)msg('sstate','12 秒未收到数据：设备端推流可能未启动，点停止后重试')},12000)})}")
                .append("function stopH264(){sess++;if(abortCtl){try{abortCtl.abort()}catch(e){}abortCtl=null}if(watchdog){clearTimeout(watchdog);watchdog=null}if(mse){try{mse.endOfStream()}catch(e){}mse=null;sb=null}boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];gotData=false;var v=q('#h264v');if(v){v.removeAttribute('src');try{v.load()}catch(e){}}}")
                .append("function streamState(){if(!streamOn)return;var vv=q('#h264v'),st=q('#sstate'),m=currentMode();if(vv&&(m==='h264'||m==='h264fast')&&gotData){if(vv.videoWidth>0){if(st.textContent.indexOf('已解码')<0)st.textContent='H.264 推流中 · 已解码 '+vv.videoWidth+'x'+vv.videoHeight}else if(st.textContent.indexOf('未解码')<0&&st.textContent.indexOf('推流中')>=0){st.textContent='H.264 推流中 · 浏览器尚未解码（readyState='+vv.readyState+'）'}if(!streamEnded&&vv.paused&&gotData)vv.play().catch(function(){})}api('GET','/stream_state',null,function(d){if(!d)return;if(d.mode==='mjpeg'&&m==='mjpeg')msg('sstate','MJPEG 推流中 · '+d.fps+'fps');else if(d.mode==='idle'&&streamOn)msg('sstate','设备端预览已停止，可重试')})}")
                .append("function toggleStream(){var img=q('#screen'),video=q('#h264v'),tp=q('#touchpad'),st=q('#sstate'),btn=q('#sbtn');streamOn=!streamOn;if(streamOn){var m=currentMode();if(tp)tp.style.display='block';if(btn)btn.textContent='停止屏幕预览';if(streamPoll)clearInterval(streamPoll);streamPoll=setInterval(streamState,1500);if(m==='mjpeg'){stopH264();if(video)video.style.display='none';if(img){img.src='/stream?access='+encodeURIComponent(token);img.style.display='block'}if(st)st.textContent='MJPEG 预览中'}else{if(img){img.src='';img.style.display='none'}if(video)video.style.display='block';if(st)st.textContent=m==='h264fast'?'正在启动 H.264 高速…':'正在启动 H.264…';startH264()}}else{if(streamPoll){clearInterval(streamPoll);streamPoll=null}streamEnded=true;stopH264();if(img){img.src='';img.style.display='none'}if(video)video.style.display='none';if(tp)tp.style.display='none';if(btn)btn.textContent='开始屏幕预览';if(st)st.textContent=''}}")
                .append("function keyEvent(code){textApi('GET','/key?code='+code,null,function(){msg('tstat','已发送按键 '+code);setTimeout(function(){msg('tstat','')},1200)})}")
                .append("var tp=q('#touchpad'),td={on:false,moved:false,x:0,y:0,lx:0,ly:0,last:0};function tPos(ev){var r=tp.getBoundingClientRect(),cx=(ev.clientX-r.left)/r.width,cy=(ev.clientY-r.top)/r.height,dx=cx-.5,dy=cy-.5;if(dx*dx+dy*dy>.25)return null;return{x:Math.max(0,Math.min(800,Math.round(cx*800))),y:Math.max(0,Math.min(800,Math.round(cy*800)))}}function tSend(qs){textApi('GET','/touch?'+qs,null,function(){})}if(tp){tp.addEventListener('pointerdown',function(ev){ev.preventDefault();var p=tPos(ev);if(!p)return;td.on=true;td.moved=false;td.x=p.x;td.y=p.y;td.lx=p.x;td.ly=p.y;td.last=0});tp.addEventListener('pointermove',function(ev){ev.preventDefault();if(!td.on)return;var p=tPos(ev);if(!p)return;if(!td.moved&&Math.abs(p.x-td.x)+Math.abs(p.y-td.y)<10)return;td.moved=true;var now=Date.now();if(now-td.last>70){tSend('act=move&x='+p.x+'&y='+p.y+'&px='+td.lx+'&py='+td.ly);td.lx=p.x;td.ly=p.y;td.last=now}});function tend(){if(!td.on)return;td.on=false;if(!td.moved)tSend('act=tap&x='+td.x+'&y='+td.y)}tp.addEventListener('pointerup',tend);tp.addEventListener('pointercancel',tend)}")
                .append("function fsBody(){return enc({id:q('#fsId').value,name:q('#fsName').value,type:q('#fsType').value,host:q('#fsHost').value,port:q('#fsPort').value,user:q('#fsUser').value,pass:q('#fsPass').value,root:q('#fsRoot').value,domain:q('#fsDomain').value})}")
                .append("function fsList(){api('GET','/fs/list',null,function(d){if(!d||!d.ok)return;fsConns=d.connections||[];var h='';for(var i=0;i<fsConns.length;i++){var c=fsConns[i];h+='<div class=\"item\"><div class=\"main\"><b>'+esc(c.name||c.host)+'</b><small>'+esc(c.type)+' · '+esc(c.host)+(c.port?(':'+c.port):'')+' · '+(c.passSet?'有密码':'无密码')+'</small></div><button type=\"button\" class=\"secondary\" onclick=\"fsEdit('+i+')\">编辑</button><button type=\"button\" class=\"secondary\" onclick=\"fsTest(\\''+esc(c.id)+'\\')\">测试</button><button type=\"button\" class=\"danger\" onclick=\"fsRemove(\\''+esc(c.id)+'\\')\">删除</button></div>'}q('#fsList').innerHTML=h||'<div class=\"item\"><div class=\"main\"><b>暂无连接</b><small>在下面添加 FTP / WebDAV / SMB / NFS</small></div></div>'})}")
                .append("function fsEdit(i){var c=fsConns[i];if(!c)return;q('#fsId').value=c.id||'';q('#fsName').value=c.name||'';q('#fsType').value=c.type||'FTP';q('#fsHost').value=c.host||'';q('#fsPort').value=c.port||'';q('#fsUser').value=c.user||'';q('#fsPass').value='';q('#fsRoot').value=c.root||'';q('#fsDomain').value=c.domain||'';msg('fsMsg',c.passSet?'已载入，密码留空则保持旧值':'已载入')}")
                .append("function fsNew(){q('#fsId').value='';q('#fsName').value='';q('#fsType').value='WebDAV';q('#fsHost').value='';q('#fsPort').value='';q('#fsUser').value='';q('#fsPass').value='';q('#fsRoot').value='';q('#fsDomain').value='';msg('fsMsg','新连接')}function fsSave(){api('POST','/fs/save',fsBody(),function(d){msg('fsMsg',d&&d.ok?'已保存':(d&&d.err?d.err:'保存失败'));if(d&&d.ok){q('#fsId').value=d.id||'';q('#fsPass').value='';fsList();publicStatus()}},'application/x-www-form-urlencoded')}function fsTest(id){var b=id?enc({id:id}):fsBody();msg('fsMsg','测试中...');api('POST','/fs/test',b,function(d){msg('fsMsg',d&&d.ok?('连接成功 · '+d.entries+' 项 · '+d.ms+'ms'):(d&&d.err?d.err:'测试失败'))},'application/x-www-form-urlencoded')}function fsRemove(id){if(!confirm('删除这个网盘连接？'))return;api('POST','/fs/remove',enc({id:id}),function(d){msg('fsMsg',d&&d.ok?'已删除':(d&&d.err?d.err:'删除失败'));fsList();publicStatus()},'application/x-www-form-urlencoded')}")
                .append("function fmtBytes(n){n=Number(n||0);if(n>=1048576)return (n/1048576).toFixed(1)+' MB';if(n>=1024)return (n/1024).toFixed(1)+' KB';return n+' B'}function appErr(d){return d&&d.err?d.err:'操作失败'}function appMsg(m){msg('appUploadMsg',m)}")
                .append("function renderUpload(u){var info=q('#appUploadInfo'),btn=q('#appInstallBtn');if(!u||!u.exists){info.textContent='未上传 APK';btn.disabled=true;return}var v=(u.versionName?(' v'+u.versionName):'')+(u.versionCode?(' ('+u.versionCode+')'):'');info.textContent='已上传 '+(u.packageName||'未知包名')+v+' · '+fmtBytes(u.size)+(u.self?' · 当前应用':'');btn.disabled=false}")
                .append("function renderTask(t){var l=q('#appTaskLog');if(!t){l.textContent='';return}l.textContent=t.log||'';l.scrollTop=l.scrollHeight}")
                .append("function appState(){api('GET','/appmgr/state',null,function(d){if(!d)return;if(d.authed){renderUpload(d.upload);renderTask(d.task)}else{renderUpload(null);q('#appTaskLog').textContent=''}})}")
                .append("function appUpload(){var fi=q('#appApk'),f=fi.files&&fi.files[0];if(!f){appMsg('请选择 APK 文件');return}var x=new XMLHttpRequest();x.open('POST','/appmgr/upload',true);hdr(x);x.setRequestHeader('Content-Type','application/vnd.android.package-archive');x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));x.upload.onprogress=function(e){if(e.lengthComputable)appMsg('上传 '+Math.round(e.loaded*100/e.total)+'% · '+fmtBytes(e.loaded)+' / '+fmtBytes(e.total))};x.onload=function(){var d=null;try{d=JSON.parse(x.responseText)}catch(e){d={ok:false,err:x.responseText||'上传失败'}}if(d&&d.ok){appMsg('上传完成');renderUpload(d.upload);appState()}else appMsg(appErr(d))};x.onerror=function(){appMsg('上传中断')};x.send(f)}")
                .append("function appFetch(){var u=q('#appFetchUrl').value.trim();if(!u){appMsg('请输入 APK 下载地址');return}api('POST','/appmgr/fetch',enc({url:u}),function(d){if(d&&d.ok){appMsg('拉取完成');renderUpload(d.upload);appState()}else appMsg(appErr(d))},'application/x-www-form-urlencoded')}function appInstall(){if(!confirm('安装上传的 APK？安装当前应用时网页会短暂断开。'))return;api('POST','/appmgr/install','',function(d){if(d&&d.ok){appMsg('安装任务已开始');renderTask(d.task);setTimeout(appState,1000)}else appMsg(appErr(d))},'application/x-www-form-urlencoded')}")
                .append("function loadApps(){api('GET','/appmgr/apps',null,function(d){if(d&&d.ok){appApps=d.apps||[];renderApps();appMsg('应用列表已刷新')}else appMsg(appErr(d))})}function renderApps(){var box=q('#appList'),cnt=q('#appCount'),s=q('#appSearch').value.toLowerCase(),h='',n=0;for(var i=0;i<appApps.length;i++){var a=appApps[i],hay=((a.label||'')+' '+(a.packageName||'')).toLowerCase();if(s&&hay.indexOf(s)<0)continue;n++;h+='<div class=\"item\"><div class=\"main\"><b>'+esc(a.label||a.packageName)+'</b><small>'+esc(a.packageName)+'</small><small>'+esc(a.versionName||'')+' · '+(a.system?'系统':'第三方')+' · '+(a.enabled?'启用':'停用')+(a.self?' · 当前管理':'')+'</small></div><button type=\"button\" '+(a.self?'disabled ':'')+'onclick=\"appUninstall(\\''+esc(a.packageName)+'\\','+(a.system?'true':'false')+',\\''+esc(a.label||a.packageName)+'\\')\">卸载</button></div>'}box.innerHTML=h||'<div class=\"item\"><div class=\"main\"><b>没有匹配应用</b></div></div>';cnt.textContent='显示 '+n+' / '+appApps.length+' 个'}function appUninstall(pkg,sys,label){if(sys){var p=prompt('系统应用卸载风险高，请输入包名确认：'+pkg);if(p!==pkg)return}else if(!confirm('卸载 '+label+' ?'))return;api('POST','/appmgr/uninstall',enc({packageName:pkg}),function(d){if(d&&d.ok){appMsg('卸载任务已开始');renderTask(d.task);setTimeout(loadApps,1200)}else appMsg(appErr(d))},'application/x-www-form-urlencoded')}")
                .append("function convHtml(d){convCache=d;var arr=d.entries||[],f=q('#convFilter').value,h='';for(var i=Math.max(0,arr.length-250);i<arr.length;i++){var e=arr[i];if(f!=='all'&&e.role!==f)continue;var who=e.role==='user'?'用户':(e.role==='assistant'?'AI':(e.role==='heard'?'听见':(e.role==='error'?'错误':'系统')));h+='<div><span class=\"mini\">'+esc(e.ts)+'</span> <b>'+who+':</b> '+esc(e.text)+'</div>'}if(!h)h='<div class=\"hint\">暂无匹配记录</div>';h+='<div class=\"mini\">共 '+arr.length+' 条 · 文件 '+d.sizeKb+' KB / 上限 '+d.maxKb+' KB</div>';return h}function loadConv(){api('GET','/conversations',null,function(d){if(!d||!d.entries)return;var v=q('#conv');v.innerHTML=convHtml(d);v.scrollTop=v.scrollHeight})}function clearConv(){if(!confirm('清空对话记录？'))return;textApi('POST','/clear_conv','',function(){msg('convMsg','已清空');loadConv()})}")
                .append("function debugHtml(d){var arr=d.entries||[],h='';for(var i=Math.max(0,arr.length-400);i<arr.length;i++){var e=arr[i];h+='<div style=\"border-bottom:1px solid rgba(181,139,48,.14);padding:5px 0\"><span class=\"mini\">'+esc(e.ts)+'</span> <b>'+esc(e.stage||'debug')+':</b><pre style=\"margin:4px 0 0;border:0;background:transparent;padding:0;color:#9ed17d;max-height:none\">'+esc(e.text||'')+'</pre></div>'}if(!h)h='<div class=\"hint\">暂无调试日志。打开调试模式并保存后，再复现一次天气查询。</div>';h+='<div class=\"mini\">共 '+arr.length+' 条 · 文件 '+(d.sizeKb||0)+' KB / 上限 '+(d.maxKb||0)+' KB · '+(d.enabled?'调试已开启':'调试已关闭')+'</div>';return h}function loadDebugLog(){api('GET','/debug_log',null,function(d){if(!d||!d.ok)return;var v=q('#debugLog');if(!v)return;v.innerHTML=debugHtml(d);v.scrollTop=v.scrollHeight;msg('debugMsg',d.enabled?'调试已开启':'调试已关闭')})}function clearDebugLog(){if(!confirm('清空链路调试日志？'))return;textApi('POST','/clear_debug','',function(){msg('debugMsg','已清空');loadDebugLog()})}")
                .append("function backupExport(){api('GET','/backup/export',null,function(d){if(d&&d.ok){q('#backupContent').value=d.content||'';msg('backupMsg','已导出 '+(d.bytes||0)+' bytes · '+(d.path||''))}else msg('backupMsg',d&&d.err?d.err:'导出失败')})}function backupRestore(){var c=q('#backupContent').value;if(!c.trim()){msg('backupMsg','请先粘贴备份 JSON');return}if(!confirm('从此 JSON 恢复配置？当前配置会被覆盖。'))return;api('POST','/backup/restore',enc({content:c}),function(d){msg('backupMsg',d&&d.ok?'已恢复，正在重载':(d&&d.err?d.err:'恢复失败'));if(d&&d.ok)setTimeout(loadStatus,800)},'application/x-www-form-urlencoded')}")
                .append("var bootAssets=null;function bootFileText(x){return x&&x.exists?(fmtBytes(x.bytes)+' · '+(x.sha256||'').slice(0,16)+'...'):'未创建'}function renderBootAssets(d){bootAssets=d||{};var b=d&&d.battery||{},lines=['分区：'+(d&&d.partition?d.partition:'未识别')+' · '+fmtBytes(d&&d.partitionBytes||0),'电量：'+(b.level==null?'--':b.level+'%')+(b.charging?' · 充电中':''),'logo 原厂：'+bootFileText(d&&d.logoRaw),'原厂开机动画：'+bootFileText(d&&d.bootanimation),'原厂关机动画：'+bootFileText(d&&d.shutanimation),'当前关机动画：'+bootFileText(d&&d.activeShutanimation),'待刷 logo：'+bootFileText(d&&d.uploadedLogo),'状态：'+(d&&d.probe||d&&d.err||'未知')];q('#bootAssetState').textContent=lines.join('\\n')}function bootAssetStatus(){api('GET','/boot-assets/status',null,function(d){renderBootAssets(d)})}function bootAssetBackup(){if(!confirm('从真实分区和 Magisk mirror 创建原厂启动资源备份？'))return;msg('bootAssetMsg','正在读取并校验原厂资源...');api('POST','/boot-assets/backup','',function(d){msg('bootAssetMsg',d&&d.ok?(d.msg||'备份完成'):(d&&d.err?d.err:'备份失败'));bootAssetStatus()},'application/x-www-form-urlencoded')}function bootAssetDownload(kind){var x=new XMLHttpRequest();x.open('GET','/boot-assets/download?kind='+encodeURIComponent(kind),true);hdr(x);x.responseType='blob';x.onload=function(){if(x.status!==200){msg('bootAssetMsg','下载失败 '+x.status);return}var names={logo:'logo-original.bin.gz',bootanimation:'bootanimation-original.zip',shutanimation:'shutanimation-original.zip',manifest:'manifest.json',sums:'SHA256SUMS'},a=document.createElement('a');a.href=URL.createObjectURL(x.response);a.download=names[kind]||'boot-asset.bin';a.click();setTimeout(function(){URL.revokeObjectURL(a.href)},1000)};x.onerror=function(){msg('bootAssetMsg','下载中断')};x.send()}")
                .append("function bootLogLoad(){api('GET','/boot-assets/boot-log',null,function(d){var e=q('#bootLog');e.style.display='block';e.textContent=d&&d.ok?(d.log||'日志为空'):(d&&d.err?d.err:'读取失败')})}")
                .append("function bootLogoUpload(){var f=q('#bootLogoFile').files&&q('#bootLogoFile').files[0];if(!f){msg('bootAssetMsg','请选择完整 logo.bin');return}var x=new XMLHttpRequest();x.open('POST','/boot-assets/upload-logo',true);hdr(x);x.setRequestHeader('Content-Type','application/octet-stream');x.upload.onprogress=function(e){if(e.lengthComputable)msg('bootAssetMsg','上传 '+Math.round(e.loaded*100/e.total)+'%')};x.onload=function(){var d;try{d=JSON.parse(x.responseText)}catch(e){d={ok:false,err:x.responseText}}msg('bootAssetMsg',d&&d.ok?('上传完成 · '+d.sha256):(d&&d.err?d.err:'上传失败'));bootAssetStatus()};x.onerror=function(){msg('bootAssetMsg','上传中断')};x.send(f)}function bootLogoFlash(){var f=bootAssets&&bootAssets.uploadedLogo;if(!f||!f.exists){msg('bootAssetMsg','请先上传新 logo.bin');return}var c=prompt('高风险操作。输入 FLASH LOGO 确认刷写真实 logo 分区：');if(c!=='FLASH LOGO')return;msg('bootAssetMsg','正在刷写并整分区回读校验...');api('POST','/boot-assets/flash-logo',enc({confirm:c,expectedSha:f.sha256}),function(d){msg('bootAssetMsg',d&&d.ok?(d.msg+' · '+d.sha256):(d&&d.err?d.err:'刷写失败'));bootAssetStatus()},'application/x-www-form-urlencoded')}function bootLogoRestore(){var f=bootAssets&&bootAssets.logoRaw;if(!f||!f.exists){msg('bootAssetMsg','没有原厂备份');return}var c=prompt('输入 RESTORE STOCK LOGO 确认恢复原厂首屏：');if(c!=='RESTORE STOCK LOGO')return;msg('bootAssetMsg','正在恢复并回读校验...');api('POST','/boot-assets/restore-logo',enc({confirm:c,expectedSha:f.sha256}),function(d){msg('bootAssetMsg',d&&d.ok?(d.msg+' · '+d.sha256):(d&&d.err?d.err:'恢复失败'));bootAssetStatus()},'application/x-www-form-urlencoded')}")
                .append("boot();")
                .append("</script></body></html>");
        byte[] b = h.toString().getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
        out.write(b);
    }

    private static String rowInput(String label, String name, String type, String placeholder) {
        return "<div class='row'><label>" + label + "</label><input type='" + type + "' name='"
                + name + "' placeholder='" + placeholder + "'></div>";
    }

    private static String rowSelect(String label, String name, String id, String options, String onchange) {
        String idAttr = id == null || id.isEmpty() ? "" : " id='" + id + "'";
        String change = onchange == null || onchange.isEmpty() ? "" : " onchange='" + onchange + "'";
        return "<div class='row'><label>" + label + "</label><select name='" + name + "'" + idAttr
                + change + ">" + options + "</select></div>";
    }

    private static String rowCheckbox(String label, String name, String hint) {
        return "<label class='checkrow'><input type='checkbox' name='" + name + "'><b style='color:var(--gold)'>"
                + label + "</b><span>" + hint + "</span></label>";
    }

    private static String rowRange(String label, String name, int min, int max, String hint) {
        return "<div class='row'><label>" + label + "</label><div><input type='range' name='"
                + name + "' min='" + min + "' max='" + max
                + "' step='1' oninput='syncRanges()'><div class='hint'><span id='"
                + name + "Text'></span> · " + hint + "</div></div></div>";
    }

    private static String mcpServerBox(int i) {
        String p = "mcpServer" + i;
        return "<details open><summary>服务器 " + i + " · 命名空间 mcp" + i + "__</summary>"
                + rowCheckbox("启用服务器 " + i, p + "Enabled", "关闭后不会把该服务器工具交给模型")
                + rowInput("名称", p + "Name", "text", "天气 / 搜索 / 记忆")
                + rowInput("URL", p + "Url", "text", "https://host/mcp")
                + rowInput("Bearer Token", p + "Token", "password", "留空=保持旧 Token")
                + "<div class='row'><label></label><span class='state' id='" + p
                + "TokenState'>Token 未知</span></div>"
                + rowInput("超时(ms)", p + "TimeoutMs", "text", "12000")
                + "</details>";
    }

    private static String fsRow(String label, String id, String placeholder) {
        return "<div class='row'><label>" + label + "</label><input type='text' id='" + id
                + "' placeholder='" + placeholder + "'></div>";
    }

    private static void serveHtml(OutputStream out) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>真理罗盘 · 网页设置</title>"
                + "<style>body{background:#0d0b08;color:#e8dcc0;font-family:sans-serif;margin:0;padding:16px}"
                + "h1{color:#d4af37;text-align:center;font-size:20px}"
                + "fieldset{border:1px solid #6b5a2e;border-radius:12px;margin:10px 0;padding:10px}"
                + "legend{color:#d4af37}.row{margin:6px 0}label{display:inline-block;width:110px;color:#d4af37;font-size:13px}"
                + "input[type=text],input[type=password],input[type=file]{width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px}"
                + "button{background:#d4af37;color:#0d0b08;border:none;border-radius:8px;padding:8px 14px;margin:4px}"
                + "button:disabled{opacity:.45}"
                + ".navbtn{width:74px;height:42px;border-radius:21px;background:#171512;color:#d4af37;border:1px solid #6b5a2e;font-size:13px;margin:0 5px;cursor:pointer}"
                + ".navbtn:active{background:#d4af37;color:#0d0b08}"
                + ".ok{color:#8fbf6a}</style></head><body>"
                + "<h1>☯ 真理罗盘 · 网页设置</h1>"
                + "<form id='f' onsubmit='save();return false'>"
                + "<fieldset><legend>大模型</legend>"
                + "<div class='row'><label>Provider</label><select name='provider' id='provider' onchange='providerChanged()' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='deepseek'>deepseek</option><option value='openai兼容'>openai兼容</option></select></div>"
                + "<div class='row'><label>API Key</label><input type='text' name='apiKey'></div>"
                + "<div class='row'><label>公共 Base</label><input type='text' name='baseUrl' id='baseUrl'></div>"
                + "<div class='row'><label>文本 Base</label><input type='text' name='textBaseUrl' placeholder='留空=公共 Base URL'></div>"
                + "<div class='row'><label>文本模型</label><input type='text' name='textModel'></div>"
                + "<div class='row'><label>视觉 Base</label><input type='text' name='visionBaseUrl' placeholder='留空=公共 Base URL'></div>"
                + "<div class='row'><label>视觉模型</label><input type='text' name='visionModel'></div>"
                + "<div class='row'><label>思考强度</label><select name='reasoningEffort'>"
                + "<option value='auto'>自动</option>"
                + "<option value='none'>禁止思考</option>"
                + "<option value='low'>低</option>"
                + "<option value='medium'>中</option>"
                + "<option value='high'>高</option>"
                + "<option value='max'>最大</option></select></div>"
                + "<div class='row'><label>文本 MaxToken</label><input type='text' name='textMaxTokens' placeholder='0=服务默认'></div>"
                + "<div class='row'><label>语音 MaxToken</label><input type='text' name='voiceMaxTokens' placeholder='讲故事可设 1500/2048'></div>"
                + "<div class='row'><label>视觉 MaxToken</label><input type='text' name='visionMaxTokens' placeholder='0=服务默认'></div>"
                + "<div class='row'><label>文本温度</label><input type='text' name='textTemperature' placeholder='0.0-2.0'></div>"
                + "<div class='row'><label>语音温度</label><input type='text' name='voiceTemperature' placeholder='0.0-2.0'></div>"
                + "<div class='row'><label>视觉温度</label><input type='text' name='visionTemperature' placeholder='0.0-2.0'></div>"
                + "<div style='color:#8a8272;font-size:11px'>自动=沿用服务默认；DeepSeek 会额外带上 thinking 开关，OpenAI 兼容格式则直接写 reasoning_effort。MaxToken 是回复长度上限，0 表示不主动传这个参数。</div></fieldset>"
                + "<fieldset><legend>语音</legend>"
                + "<div class='row'><label>语音 API Key</label><input type='text' name='voiceApiKey' placeholder='留空=复用大模型 API Key'></div>"
                + "<div class='row'><label>ASR 地址</label><input type='text' name='asrUrl'></div>"
                + "<div class='row'><label>最终 ASR</label><input type='text' name='asrFinalUrl' placeholder='http://<asr-final-host>:<port>/ 或 /api/v1/asr'></div>"
                + "<div class='row'><label>ASR 模型</label><input type='text' name='asrModel'></div>"
                + "<div class='row'><label>TTS 地址</label><input type='text' name='ttsUrl'></div>"
                + "<div class='row'><label>TTS 模型</label><input type='text' name='ttsModel'></div>"
                + "<div class='row'><label>TTS 音色</label><input type='text' name='ttsVoice'></div>"
                + "<div class='row'><label>本地优先</label><input type='checkbox' name='localTtsFirst' style='width:auto;vertical-align:middle'>"
                + "<span style='font-size:11px;color:#8a8272'>关闭时固定优先云端 TTS，云端失败不会偷偷换本地声音</span></div>"
                + "<div class='row'><label>打断模式</label><select name='bargeMode' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='steady'>稳健</option><option value='sensitive'>灵敏</option><option value='off'>关闭</option></select></div>"
                + "<div class='row'><label>参与模式</label><select name='interactionMode' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='quiet'>安静</option><option value='natural'>自然</option><option value='active'>积极</option></select></div>"
                + "<div style='color:#8a8272;font-size:11px;margin:4px 0 8px 114px'>流式 ASR 填 ws://地址:端口 或 http://主机根地址；最终 ASR 这里填 SenseVoice 根地址或 /api/v1/asr，OpenAI 兼容则直接填 /v1/audio/transcriptions。</div>"
                + "<div class='row' style='margin-top:10px'><label style='width:100%'>语音系统提示词</label></div>"
                + "<textarea name='sysPromptVoice' style='width:calc(100% - 14px);height:64px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'></textarea>"
                + "</fieldset>"
                + "<fieldset><legend>视觉 / 监听 / 浏览器</legend>"
                + "<div class='row'><label>视觉间隔秒</label><input type='text' name='visionInterval'></div>"
                + "<div class='row'><label>常驻监听</label><input type='checkbox' name='vadEnabled' style='width:auto;vertical-align:middle'>"
                + "<span style='font-size:11px;color:#8a8272'>持续识别外部语音，由 AI 判断是否需要回复</span></div>"
                + "<div class='row'><label>VAD 灵敏度</label><input type='text' name='vadSensitivity'></div>"
                + "<div class='row'><label>起卦力度</label><div style='width:calc(100% - 130px);display:inline-block;vertical-align:middle'><input type='range' name='oracleShakeForce' min='0' max='100' step='1' oninput='oracleShakeText()' style='width:100%'><div id='oracleShakeForceText' style='font-size:11px;color:#8a8272'>70 / 100 · 较重</div></div></div>"
                + "<div class='row'><label>搜索引擎</label><input type='text' name='searchEngine'></div>"
                + "<div class='row' style='margin-top:10px'><label style='width:100%'>视觉系统提示词</label></div>"
                + "<textarea name='sysPromptVision' style='width:calc(100% - 14px);height:80px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'></textarea>"
                + "<div class='row'><label>灵眼画面源</label><select name='visionFrameSource' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='hal'>HAL直出</option><option value='rtsp'>RTSP同源</option></select></div>"
                + "<div class='row'><label>灵眼显示</label><select name='visionOverlayStyle' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='mechanical'>机械灵眼</option><option value='plain'>纯净相机</option></select></div>"
                + "<div class='row'><label>主屏渲染</label><select name='mainRenderer' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='gl'>OpenGL</option><option value='canvas'>Canvas</option></select></div>"
                + "<div class='row'><label>帧率策略</label><select name='mainFpsMode' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='adaptive'>自适应</option><option value='power'>省电</option><option value='smooth'>流畅</option></select></div>"
                + "</fieldset>"
                + "<fieldset><legend>屏幕推流</legend>"
                + "<div class='row'><label>推流方式</label><select name='streamMode'>"
                + "<option value='h264'>H.264 硬编（采集慢）</option>"
                + "<option value='h264fast'>H.264 高速（虚拟显示）</option>"
                + "<option value='mjpeg'>MJPEG 兼容</option></select></div>"
                + "<div class='row'><label>帧率(fps)</label><select name='streamFps'>"
                + "<option>1</option><option>2</option><option>3</option><option>5</option></select></div>"
                + "<div class='row'><label>码率(Kbps)</label><select name='streamBitrate'>"
                + "<option value='600'>600</option><option value='1000'>1000</option>"
                + "<option value='1500'>1500</option><option value='2500'>2500</option>"
                + "<option value='4000'>4000</option><option value='6000'>6000</option>"
                + "<option value='8000'>8000(最大)</option></select></div>"
                + "<div class='row'><label>画质(MJPEG)</label><select name='streamQuality'>"
                + "<option value='30'>低</option><option value='55'>中</option><option value='75'>高</option></select></div>"
                + "<div class='row'><label>尺寸(MJPEG)</label><select name='streamScale'>"
                + "<option value='2'>半尺寸(400×400)</option><option value='1'>原始(800×800)</option></select></div>"
                + "<div style='text-align:center'><button type='button' onclick='toggleStream()' id='sbtn'>开始推流</button>"
                + "<span id='sstate' style='font-size:12px;color:#8fbf6a'></span></div>"
                + "<div style='display:flex;justify-content:center;gap:20px;flex-wrap:wrap;align-items:center'>"
                + "<div style='text-align:center'>"
                + "<div style='position:relative;width:92mm;height:92mm'>"
                + "<video id='h264v' muted autoplay playsinline style='width:100%;height:100%;"
                + "border-radius:50%;border:1px solid #6b5a2e;display:none;object-fit:cover;filter:brightness(1.55) contrast(1.15)'></video>"
                + "<img id='screen' style='width:100%;height:100%;border-radius:50%;"
                + "border:1px solid #6b5a2e;display:none;object-fit:cover;filter:brightness(1.55) contrast(1.15)'>"
                + "<div id='touchpad' style='position:absolute;inset:0;border-radius:50%;cursor:crosshair;touch-action:none;user-select:none'></div></div>"
                + "<div style='margin-top:8px;color:#8a8272;font-size:11px'>点击/长按/拖动圆面 → 远程操作设备屏幕</div>"
                + "<div style='margin-top:4px'>"
                + "<button type='button' class='navbtn' onclick='keyEvent(4)'>◀ 返回</button>"
                + "<button type='button' class='navbtn' onclick='keyEvent(3)'>● 桌面</button>"
                + "<button type='button' class='navbtn' onclick='keyEvent(187)'>▢ 最近</button>"
                + "<div id='tstat' style='color:#8fbf6a;font-size:11px;margin-top:4px;min-height:14px'></div></div></div>"
                + "<div style='width:92mm;height:92mm;border-radius:50%;border:1px solid #6b5a2e;background:#171512;position:relative;box-sizing:border-box'>"
                + "<div style='position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center'>"
                + "<div id='statTime' style='font-size:34px;color:#d4af37;font-weight:bold;font-family:monospace'></div>"
                + "<div id='statDate' style='font-size:12px;color:#e8dcc0;margin-top:2px'></div>"
                + "<div id='statCore' style='font-size:10px;color:#8fbf6a;margin-top:6px'></div>"
                + "<div id='statGps' style='font-size:10px;color:#d4af37;margin-top:3px'></div></div>"
                + "<div id='ring' style='position:absolute;inset:0'></div></div></div>"
                + "<div style='color:#8a8272;font-size:11px'>H.264 走 MT6580 硬件编码器（720×720），省 CPU、省带宽；MJPEG 为兼容模式。改参数先点保存。</div>"
                + "</fieldset>"
                + "<fieldset><legend>定位 API</legend>"
                + "<div class='row'><label>定位来源</label><select name='locSource' style='width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'>"
                + "<option value='off'>关闭</option><option value='wifi_ip'>WiFi-IP</option><option value='gps_diag'>GPS诊断</option></select></div>"
                + "<div class='row'><label>WiFi 定位地址</label><input type='text' name='locWifiUrl'></div>"
                + "<div class='row'><label>IP 粗定位地址</label><input type='text' name='locIpUrl' placeholder='留空=使用默认 IP 粗定位'></div>"
                + "<div style='color:#8a8272;font-size:11px'>WiFi/IP 只做城市/街区级粗定位；默认 IP 粗定位不需要 GPS/SIM，只有 GPS诊断 或 坤·星图 会临时搜星。</div>"
                + "</fieldset>"
                + "<fieldset><legend>摄像头推流</legend>"
                + "<div class='row'><label>摄像头</label><select name='camId'><option value='0'>后置（默认）</option><option value='1'>前置</option></select></div>"
                + "<div class='row'><label>分辨率</label><select name='camWidth'><option>640</option><option>800</option><option>1280</option></select> × "
                + "<select name='camHeight'><option>480</option><option>800</option><option>720</option></select></div>"
                + "<div style='color:#8a8272;font-size:11px'>相机支持尺寸：640×480（19fps 流畅，默认）、800×800（10fps 圆屏原生）、1280×720（7fps 高清）。选择不匹配时自动取最接近的支持尺寸。</div>"
                + "<div class='row'><label>帧率</label><select name='camFps'><option>24</option></select>"
                + "<span style='font-size:11px;color:#8a8272'>设备摄像头实际 16fps（硬件上限）</span></div>"
                + "<div class='row'><label>码率(Kbps)</label><select name='camBitrate'><option>2000</option><option>4000</option><option>5000</option><option>6000</option><option>8000</option><option>12000</option><option>20000</option></select></div>"
                + "<div style='color:#8a8272;font-size:11px'>码率影响 720p 帧率：2-5Mbps≈7-8fps（穿透推荐），20Mbps 只有 5fps。要帧率降分辨率，要画质提码率。</div>"
                + "<div class='row'><label>RTSP 端口</label><input type='text' name='rtspPort'></div>"
                + "<div class='row'><label>RTMP 地址</label><input type='text' name='rtmpUrl' placeholder='rtmp://VPS:1935/cam/stream（留空=不推）'></div>"
                + "<div class='row'><label>开机自动推流</label><input type='checkbox' name='camAutoStart' style='width:auto'>"
                + "<span style='font-size:11px;color:#8a8272'>应用启动时自动开始摄像头推流（默认开）</span></div>"
                + "<div style='text-align:center'><button type='button' onclick='camToggle()' id='camBtn'>启动推流</button>"
                + "<span id='camMsg'></span></div>"
                + "<div style='color:#8a8272;font-size:11px'>状态：<span id='camState'>未知</span>"
                + "<div id='camUrls' style='margin-top:4px'></div></div>"
                + "<div style='color:#8a8272;font-size:11px'>RTSP 用 VLC 等播放 <b>rtsp://127.0.0.1:端口/cam</b>（本机可直接访问；远程访问请走 frpc）；网页播放 <a href='#' onclick=\"window.open('/cam?access='+encodeURIComponent(token),'_blank');return false;\" style='color:#d4af37'>点这里打开摄像头直播页</a>。已实测：720p 可达，摄像头回调硬件上限 16fps（60/30fps 目标自动降级），码率 VBR 最高按设置值。状态区会显示实际帧率。</div>"
                + "</fieldset>"
                + "<fieldset><legend>内网穿透 frpc</legend>"
                + "<div class='row'><label style='width:100%'>frpc.toml 配置（保存后生效）</label></div>"
                + "<textarea name='frpcConfig' rows='12' style='width:calc(100% - 14px);height:220px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px;font-family:monospace;font-size:12px'></textarea>"
                + "<div style='color:#8a8272;font-size:11px'>示例：serverAddr = '你的服务器IP' / serverPort = 7000 / auth.token = '密钥'，代理用 [[proxies]]：name='web' type='tcp' localIP='127.0.0.1' localPort=8080 remotePort=你的远程端口（远程端口按你的 VPS 规划填写，改完先点保存）</div>"
                + "<div style='color:#8a8272;font-size:11px'>当前服务端不支持 QUIC/KCP 传输端口，且 tcpMux=false 实测会断；ADB/大文件保持默认 TCP，安装 APK 优先用下面的应用管理。</div>"
                + "<div class='row'>状态：<span id='frpcState' style='color:#d4af37'>未知</span>"
                + "<span id='frpcStateDetail' style='font-size:11px;color:#8a8272;margin-left:8px'></span>"
                + "<span style='font-size:11px;color:#8a8272;margin-left:8px'>应用启动时自动运行（配置非空）</span></div>"
                + "<div style='text-align:center'><button type='button' onclick='frpcStart()'>启动 frpc</button>"
                + "<button type='button' onclick='frpcStop()'>停止 frpc</button>"
                + "<span id='frpcMsg'></span></div>"
                + "<div style='color:#8a8272;font-size:11px'>运行日志（最近部分，自动刷新）：</div>"
                + "<pre id='frpcLog' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;max-height:200px;overflow-y:auto;font-size:11px;white-space:pre-wrap;color:#8fbf6a'></pre>"
                + "</fieldset>"
                + "<fieldset><legend>对话记录</legend>"
                + "<div class='row'><label>大小上限(KB)</label><input type='text' name='convMaxKb' placeholder='4096'> </div>"
                + "<div class='row'><label>清理间隔(分钟)</label><input type='text' name='convCleanMin'>"
                + "<div style='color:#8a8272;font-size:11px;margin-left:114px'>0=关闭定时清理（超出上限时写入仍会自动裁剪）</div></div>"
                + "<div style='text-align:center'><button type='button' onclick='clearConv()'>清空记录</button><span id='convMsg'></span></div>"
                + "<div id='conv' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;"
                + "max-height:320px;overflow-y:auto;font-size:12px;line-height:1.5'></div></fieldset>"
                + "<div style='text-align:center'><button type='submit'>保存设置</button><span id='msg' class='ok'></span></div>"
                + "</form>"
                + "<fieldset><legend>ADB TCP</legend>"
                + "<div class='row'><label>ADB 端口</label><input type='text' id='adbPort' value='5555'></div>"
                + "<div class='row'><label>开机自启</label><input type='checkbox' id='adbAuto' style='width:auto;vertical-align:middle'>"
                + "<span style='font-size:11px;color:#8a8272'>由本应用 root 启动 adbd TCP，替代外部 adbwireless</span></div>"
                + "<div class='row'>设备侧：<span id='adbState' style='color:#d4af37'>未知</span>"
                + "<span id='adbDetail' style='font-size:11px;color:#8a8272;margin-left:8px'></span></div>"
                + "<div style='text-align:center'><button type='button' onclick='adbSave()'>保存自启</button>"
                + "<button type='button' onclick='adbStart()'>启动/重启 ADB TCP</button>"
                + "<button type='button' onclick='adbStop()'>关闭 ADB TCP</button>"
                + "<span id='adbMsg' style='font-size:12px;color:#8fbf6a'></span></div>"
                + "<pre id='adbLog' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;max-height:120px;overflow-y:auto;font-size:11px;white-space:pre-wrap;color:#8fbf6a'></pre>"
                + "</fieldset>"
                + "<fieldset><legend>应用管理</legend>"
                + "<div class='row'><label>管理密码</label><input type='password' id='appPwd' autocomplete='current-password'></div>"
                + "<div class='row'><label>旧密码</label><input type='password' id='appOldPwd' autocomplete='current-password' placeholder='修改密码时填写'></div>"
                + "<div style='text-align:center'><button type='button' onclick='appLogin()'>登录</button>"
                + "<button type='button' onclick='appSetup()'>设置/修改密码</button>"
                + "<span id='appAuth' style='font-size:12px;color:#8fbf6a'></span></div>"
                + "<div class='row'><label>APK 文件</label><input type='file' id='appApk' accept='.apk,application/vnd.android.package-archive'></div>"
                + "<div class='row'><label>APK 下载地址</label><input type='text' id='appFetchUrl' placeholder='https://.../app.apk 或 ftp://...'></div>"
                + "<div style='text-align:center'><button type='button' onclick='appUpload()'>上传 APK</button>"
                + "<button type='button' onclick='appFetch()'>从 URL 拉取</button>"
                + "<button type='button' id='appInstallBtn' onclick='appInstall()' disabled>安装上传的 APK</button>"
                + "<span id='appUploadMsg' style='font-size:12px;color:#8fbf6a'></span></div>"
                + "<div id='appUploadInfo' style='font-size:11px;color:#8a8272;margin:4px 0'></div>"
                + "<div style='color:#8a8272;font-size:11px;margin:2px 0 6px 0'>上传/拉取都走普通网络，不走 <code>adb install</code> 的文件传输。</div>"
                + "<div class='row'><label>搜索应用</label><input type='text' id='appSearch' oninput='renderApps()'></div>"
                + "<div style='text-align:center'><button type='button' onclick='loadApps()'>刷新应用</button>"
                + "<span id='appCount' style='font-size:12px;color:#8a8272'></span></div>"
                + "<div id='appList' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;max-height:360px;overflow-y:auto;font-size:12px'></div>"
                + "<div style='color:#8a8272;font-size:11px;margin-top:6px'>任务日志（安装/卸载期间自动刷新）：</div>"
                + "<pre id='appTaskLog' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;max-height:160px;overflow-y:auto;font-size:11px;white-space:pre-wrap;color:#8fbf6a'></pre>"
                + "</fieldset>"
                + "<script>"
                + "function get(url,cb){var x=new XMLHttpRequest();x.open('GET',url,true);"
                + "x.onload=function(){try{cb(JSON.parse(x.responseText));}catch(e){cb(null);}};"
                + "x.onerror=function(){cb(null);};x.send();}"
                + "get('/status',function(d){if(!d)return;for(var k in d){var e=document.querySelector('[name='+k+']');if(!e)continue;"
                + "if(e.type==='checkbox'){e.checked=(d[k]===true||d[k]==='true');}else{e.value=d[k];}}"
                + "document.getElementById('provider').value=normProvider(d.provider);"
                + "oracleShakeText();"
                + "document.getElementById('msg').textContent='已加载设备当前配置';});"
                + "function normProvider(p){p=String(p||'').toLowerCase();return p.indexOf('deepseek')>=0?'deepseek':'openai兼容';}"
                + "function providerChanged(){var p=document.getElementById('provider').value;"
                + "if(p==='deepseek'){document.getElementById('baseUrl').value='https://api.deepseek.com/v1';"
                + "document.querySelector('[name=textModel]').value='deepseek-chat';document.querySelector('[name=visionModel]').value='deepseek-chat';"
                + "document.querySelector('[name=textBaseUrl]').value='';document.querySelector('[name=visionBaseUrl]').value='';}"
                + "else{var b=document.getElementById('baseUrl'),t=document.querySelector('[name=textModel]'),v=document.querySelector('[name=visionModel]');"
                + "if(b.value==='https://api.deepseek.com/v1')b.value='';if(!t.value||t.value==='deepseek-chat')t.value='gpt-4.1-mini';"
                + "if(!v.value||v.value==='deepseek-chat')v.value='gpt-4.1-mini';}}"
                + "function oracleShakeText(){var e=document.querySelector('[name=oracleShakeForce]'),t=document.getElementById('oracleShakeForceText');if(e&&t){var v=Math.max(0,Math.min(100,Number(e.value||70))),n=v<18?'轻摇':(v<38?'稍轻':(v<62?'正常':(v<82?'较重':'用力'));t.textContent=Math.round(v)+' / 100 · '+n+' · 数值越大越难触发';}}"
                + "function save(){var b=new URLSearchParams(new FormData(document.getElementById('f')));"
                + "var cbs=document.querySelectorAll('#f input[type=checkbox]');for(var i=0;i<cbs.length;i++){b.set(cbs[i].name,cbs[i].checked?'true':'false');}"
                + "var x=new XMLHttpRequest();x.open('POST','/save',true);"
                + "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');"
                + "x.onload=function(){document.getElementById('msg').textContent=x.responseText;};x.send(b.toString());}"
                + "function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}"
                + "var appApps=[],appTaskRunning=false;"
                + "function appToken(){return sessionStorage.getItem('appmgrToken')||'';}"
                + "function appErr(d){return d&&d.err?d.err:'操作失败';}"
                + "function appMsg(m){var e=document.getElementById('appUploadMsg');if(e)e.textContent=m||'';}"
                + "function appReq(m,u,body,cb,ctype){var x=new XMLHttpRequest();x.open(m,u,true);"
                + "var t=appToken();if(t)x.setRequestHeader('X-AppMgr-Token',t);if(ctype)x.setRequestHeader('Content-Type',ctype);"
                + "x.onload=function(){var d=null;try{d=JSON.parse(x.responseText);}catch(e){d={ok:false,err:x.responseText||'请求失败'};}if(cb)cb(d,x);};"
                + "x.onerror=function(){if(cb)cb({ok:false,err:'网络错误'},x);};x.send(body||null);}"
                + "function adbMsg(m){var e=document.getElementById('adbMsg');if(e)e.textContent=m||'';}"
                + "function adbStatus(){get('/adb/status',function(d){if(!d)return;var p=document.getElementById('adbPort'),a=document.getElementById('adbAuto');"
                + "if(p&&document.activeElement!==p)p.value=d.port||5555;if(a&&document.activeElement!==a)a.checked=!!d.autoStart;var st=document.getElementById('adbState');"
                + "var h=d.health||'checking';st.textContent=h==='healthy'?'服务正常':(h==='degraded'?'连接积压':(h==='down'?'未监听':'检测中'));var det=document.getElementById('adbDetail'),ap=d.activePort||d.servicePort||d.persistPort||'--';"
                + "det.textContent='端口 '+ap+' · adbd='+(d.daemonState||'--')+' · 协议='+(d.protocolDetail||'未检测')+' · 开机同步='+(d.tunnelSynchronized?'完成':'等待')+' · 自启='+(d.autoStart?'开':'关')+' · 连续失败 '+(d.consecutiveFailures||0)+' · CLOSE_WAIT '+(d.closeWaitSockets||0)+' · '+(d.lastCheckDetail||'')+' · 主机状态以 adb devices 为准';"
                + "var l=document.getElementById('adbLog');if(l){l.textContent=d.log||'';}});}"
                + "function adbBody(){return 'port='+encodeURIComponent(document.getElementById('adbPort').value)+'&autoStart='+(document.getElementById('adbAuto').checked?'true':'false');}"
                + "function adbSave(){appReq('POST','/adb/save',adbBody(),function(d){if(d&&d.ok){adbMsg('已保存');adbStatus();}else adbMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function adbStart(){if(!confirm('启动 ADB TCP 会重启 adbd，当前 ADB 连接会短暂断开。继续？'))return;"
                + "appReq('POST','/adb/start',adbBody(),function(d){if(d&&d.ok){adbMsg(d.msg||'已启动');adbStatus();}else adbMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function adbStop(){if(!confirm('关闭 ADB TCP 会断开远程 ADB。网页/frp 仍可继续使用。继续？'))return;"
                + "appReq('POST','/adb/stop','',function(d){if(d&&d.ok){adbMsg(d.msg||'已关闭');setTimeout(adbStatus,1500);}else adbMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function appLogin(){var p=document.getElementById('appPwd').value;"
                + "appReq('POST','/appmgr/login','password='+encodeURIComponent(p),function(d){"
                + "if(d&&d.ok){sessionStorage.setItem('appmgrToken',d.token);document.getElementById('appPwd').value='';appMsg('已登录');appState();loadApps();}"
                + "else appMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function appSetup(){var p=document.getElementById('appPwd').value,old=document.getElementById('appOldPwd').value;"
                + "var b='password='+encodeURIComponent(p)+'&oldPassword='+encodeURIComponent(old);"
                + "appReq('POST','/appmgr/setup',b,function(d){if(d&&d.ok){sessionStorage.setItem('appmgrToken',d.token);"
                + "document.getElementById('appPwd').value='';document.getElementById('appOldPwd').value='';appMsg('管理密码已保存');appState();loadApps();}"
                + "else appMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function fmtBytes(n){n=Number(n||0);if(n>=1048576)return (n/1048576).toFixed(1)+' MB';if(n>=1024)return (n/1024).toFixed(1)+' KB';return n+' B';}"
                + "function renderUpload(u){var info=document.getElementById('appUploadInfo'),btn=document.getElementById('appInstallBtn');"
                + "if(!u||!u.exists){info.textContent='未上传 APK';btn.disabled=true;return;}"
                + "var v=(u.versionName?(' v'+u.versionName):'')+(u.versionCode?(' ('+u.versionCode+')'):'');"
                + "info.textContent='已上传 '+(u.packageName||'未知包名')+v+' · '+fmtBytes(u.size)+(u.self?' · 当前应用':'');btn.disabled=false;}"
                + "function renderTask(t){var l=document.getElementById('appTaskLog');if(!t){l.textContent='';return;}"
                + "l.textContent=t.log||'';l.scrollTop=l.scrollHeight;var was=appTaskRunning;appTaskRunning=!!t.running;"
                + "if(was&&!appTaskRunning){loadApps();appState();}}"
                + "function appState(){appReq('GET','/appmgr/state',null,function(d){if(!d)return;"
                + "var a=document.getElementById('appAuth');if(!d.hasPassword)a.textContent='未设置管理密码';"
                + "else a.textContent=d.authed?'已登录':'未登录';if(d.authed){renderUpload(d.upload);renderTask(d.task);}"
                + "else{renderUpload(null);document.getElementById('appTaskLog').textContent='';}});}"
                + "function appUpload(){if(!appToken()){appMsg('请先登录');return;}var fi=document.getElementById('appApk');var f=fi.files&&fi.files[0];"
                + "if(!f){appMsg('请选择 APK 文件');return;}var x=new XMLHttpRequest();x.open('POST','/appmgr/upload',true);"
                + "x.setRequestHeader('X-AppMgr-Token',appToken());x.setRequestHeader('Content-Type','application/vnd.android.package-archive');"
                + "x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable)appMsg('上传 '+Math.round(e.loaded*100/e.total)+'% · '+fmtBytes(e.loaded)+' / '+fmtBytes(e.total));};"
                + "x.onload=function(){var d=null;try{d=JSON.parse(x.responseText);}catch(e){d={ok:false,err:x.responseText||'上传失败'};}"
                + "if(d&&d.ok){appMsg('上传完成');renderUpload(d.upload);appState();}else appMsg(appErr(d));};"
                + "x.onerror=function(){appMsg('上传中断');};x.send(f);}"
                + "function appFetch(){if(!appToken()){appMsg('请先登录');return;}var u=document.getElementById('appFetchUrl');var url=u&&u.value?u.value.trim():'';"
                + "if(!url){appMsg('请输入 APK 下载地址');return;}appReq('POST','/appmgr/fetch','url='+encodeURIComponent(url),function(d){"
                + "if(d&&d.ok){appMsg('拉取完成');renderUpload(d.upload);appState();}else appMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function appInstall(){if(!appToken()){appMsg('请先登录');return;}if(!confirm('安装上传的 APK？安装当前应用时网页会短暂断开。'))return;"
                + "appReq('POST','/appmgr/install','',function(d){if(d&&d.ok){appMsg('安装任务已开始');renderTask(d.task);setTimeout(appState,1000);}else appMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function loadApps(){if(!appToken()){appMsg('请先登录');return;}appReq('GET','/appmgr/apps',null,function(d){"
                + "if(d&&d.ok){appApps=d.apps||[];renderApps();appMsg('应用列表已刷新');}else appMsg(appErr(d));});}"
                + "function renderApps(){var box=document.getElementById('appList'),cnt=document.getElementById('appCount');if(!box)return;"
                + "var q=(document.getElementById('appSearch').value||'').toLowerCase(),h='',n=0;"
                + "for(var i=0;i<appApps.length;i++){var a=appApps[i],hay=((a.label||'')+' '+(a.packageName||'')).toLowerCase();if(q&&hay.indexOf(q)<0)continue;n++;"
                + "h+=\"<div style='display:flex;align-items:center;gap:8px;border-bottom:1px solid #3a2f19;padding:6px 0'>\""
                + "+\"<div style='flex:1;min-width:0'><div style='color:#d4af37;white-space:nowrap;overflow:hidden;text-overflow:ellipsis'>\"+esc(a.label||a.packageName)+\"</div>\""
                + "+\"<div style='color:#8a8272;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis'>\"+esc(a.packageName)+\"</div>\""
                + "+\"<div style='color:#8fbf6a;font-size:11px'>\"+esc(a.versionName||'')+\" · \"+(a.system?'系统':'第三方')+\" · \"+(a.enabled?'启用':'停用')+(a.self?' · 当前管理':'')+\"</div></div>\""
                + "+\"<button type='button' data-pkg='\"+esc(a.packageName)+\"' data-system='\"+(a.system?'1':'0')+\"' data-label='\"+esc(a.label||a.packageName)+\"' \"+(a.self?'disabled':'')+\">卸载</button></div>\";}"
                + "box.innerHTML=h||'<div style=\"color:#8a8272\">没有匹配的应用</div>';cnt.textContent='显示 '+n+' / '+appApps.length+' 个';"
                + "var bs=box.querySelectorAll('button[data-pkg]');for(var j=0;j<bs.length;j++){bs[j].onclick=function(){appUninstall(this.getAttribute('data-pkg'),this.getAttribute('data-system')==='1',this.getAttribute('data-label'));};}}"
                + "function appUninstall(pkg,sys,label){if(sys){var p=prompt('系统应用卸载风险高，请输入包名确认：'+pkg);if(p!==pkg)return;}"
                + "else if(!confirm('卸载 '+label+' ?'))return;appReq('POST','/appmgr/uninstall','packageName='+encodeURIComponent(pkg),function(d){"
                + "if(d&&d.ok){appMsg('卸载任务已开始');renderTask(d.task);setTimeout(appState,1000);}else appMsg(appErr(d));},'application/x-www-form-urlencoded');}"
                + "function convHtml(d){var h='';var arr=d.entries||[];"
                + "for(var i=Math.max(0,arr.length-200);i<arr.length;i++){var e=arr[i];"
                + "var who=e.role==='user'?'你':(e.role==='assistant'?'AI':(e.role==='heard'?'听见':'系统'));"
                + "var color=e.role==='error'?'#e74c3c':(e.role==='user'?'#d4af37':(e.role==='heard'?'#8a8272':'#8fbf6a'));"
                + "h+=\"<div style='margin:4px 0'><span style='color:#6b6b6b;font-size:10px'>\"+esc(e.ts)+'</span> <b style=\"color:'+color+'\">'+who+':</b> '+esc(e.text)+'</div>';}"
                + "if(arr.length===0)h='<div style=\"color:#8a8272\">暂无对话记录，用语音和罗盘对话后会显示在这里</div>';"
                + "h+=\"<div style='color:#6b6b6b;font-size:10px;margin-top:6px'>共 \"+arr.length+\" 条 · 文件 \"+d.sizeKb+\" KB / 上限 \"+d.maxKb+\" KB</div>\";return h;}"
                + "function loadConv(){get('/conversations',function(d){if(!d)return;var v=document.getElementById('conv');"
                + "v.innerHTML=convHtml(d);v.scrollTop=v.scrollHeight;});}"
                + "function clearConv(){var x=new XMLHttpRequest();x.open('POST','/clear_conv',true);"
                + "x.onload=function(){document.getElementById('convMsg').textContent='已清空';loadConv();};x.send();}"
                + "var streamOn=false,streamEnded=false;var mse=null,sb=null,abortCtl=null,watchdog=null,sess=0;"
                + "var boxBuf=new Uint8Array(0),boxOff=0,initDone=false,appending=false,pending=[],gotData=false;"
                + "function currentMode(){var e=document.querySelector('[name=streamMode]');return e?e.value:'h264';}"
                + "function boxAt(pos){if(pos+8>boxBuf.length)return null;"
                + "var size=((boxBuf[pos]<<24)|(boxBuf[pos+1]<<16)|(boxBuf[pos+2]<<8)|boxBuf[pos+3])>>>0;"
                + "var type=String.fromCharCode(boxBuf[pos+4],boxBuf[pos+5],boxBuf[pos+6],boxBuf[pos+7]);"
                + "return {size:size,type:type,start:pos};}"
                + "function flushBoxes(my){while(true){"
                + "if(!initDone){var a=boxAt(boxOff),b=boxAt(boxOff+(a?a.size:0));"
                + "if(!a||!b||a.type!=='ftyp'||b.type!=='moov'||boxOff+a.size+b.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+a.size+b.size).buffer);boxOff+=a.size+b.size;initDone=true;}"
                + "else{var c=boxAt(boxOff),d=boxAt(boxOff+(c?c.size:0));"
                + "if(!c||!d||c.type!=='moof'||d.type!=='mdat'||boxOff+c.size+d.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+c.size+d.size).buffer);boxOff+=c.size+d.size;}"
                + "if(boxOff===boxBuf.length){boxBuf=new Uint8Array(0);boxOff=0;}"
                + "pumpSb(my);}}"
                + "function pumpSb(my){if(my!==sess||!mse||!sb||appending||!pending.length)return;"
                + "try{appending=true;sb.appendBuffer(pending.shift());}"
                + "catch(e){appending=false;pending=[];"
                + "if(e.name!=='InvalidStateError'){var st=document.getElementById('sstate');st.textContent='MSE 追加失败：'+e;}}}"
                + "function startH264(){var video=document.getElementById('h264v');var st=document.getElementById('sstate');"
                + "if(!window.MediaSource){st.textContent='浏览器不支持 MSE';return;}"
                + "var my=++sess;boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];gotData=false;streamEnded=false;"
                + "mse=new MediaSource();video.src=URL.createObjectURL(mse);"
                + "mse.addEventListener('sourceopen',function(){"
                + "if(my!==sess){return;}"
                + "var mySb=null;"
                + "try{mySb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.42E01E\"');}"
                + "catch(e){try{mySb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.4D401E\"');}"
                + "catch(e2){st.textContent='无法创建解码器';return;}}"
                + "sb=mySb;mySb.mode='segments';"
                + "mySb.addEventListener('updateend',function(){if(my!==sess){appending=false;pending=[];return;}appending=false;pumpSb(my);});"
                + "mySb.addEventListener('error',function(){if(my===sess)st.textContent='MSE 错误：浏览器拒绝该媒体数据（H.264 封装不兼容）';});"
                + "abortCtl=new AbortController();"
                + "fetch(currentMode()==='h264fast'?'/h264fast':'/h264',{signal:abortCtl.signal}).then(function(r){"
                + "if(my!==sess){return;}"
                + "if(!r.ok||!r.body){st.textContent='推流失败 '+r.status+'，请点停止后重试';return;}"
                + "var reader=r.body.getReader();"
                + "function step(){reader.read().then(function(res){"
                + "if(my!==sess){return;}"
                + "if(res.done){st.textContent='推流已结束（保持最后一帧）';try{video.pause();}catch(e){}streamEnded=true;return;}"
                + "var nb=new Uint8Array(boxBuf.length+res.value.length);nb.set(boxBuf,0);nb.set(res.value,boxBuf.length);boxBuf=nb;"
                + "flushBoxes(my);gotData=true;"
                + "if(watchdog){clearTimeout(watchdog);watchdog=null;}"
                + "var cur=st.textContent;"
                + "if(cur.indexOf('失败')<0&&cur.indexOf('错误')<0&&cur.indexOf('未解码')<0&&cur.indexOf('已解码')<0)"
                + "st.textContent=initDone?'推流中 · 画面持续更新':'已连接，等待首帧…';"
                + "video.play().catch(function(){});"
                + "step();}).catch(function(e){if(e.name!=='AbortError'&&my===sess){st.textContent='推流中断：'+e;}});}"
                + "step();}).catch(function(e){if(e.name!=='AbortError'&&my===sess){st.textContent='推流失败：'+e;}});"
                + "watchdog=setTimeout(function(){if(my===sess&&!gotData&&streamOn){st.textContent='12 秒未收到数据：设备端推流可能未启动，点停止后重试';}},12000);"
                + "});}"
                + "function stopH264(){sess++;if(abortCtl){try{abortCtl.abort();}catch(e){}abortCtl=null;}"
                + "if(watchdog){clearTimeout(watchdog);watchdog=null;}"
                + "if(mse){try{mse.endOfStream();}catch(e){}mse=null;sb=null;}"
                + "boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];"
                + "var v=document.getElementById('h264v');v.removeAttribute('src');v.load();}"
                + "function streamState(){if(!streamOn)return;"
                + "var vv=document.getElementById('h264v');var st2=document.getElementById('sstate');"
                + "if(vv&&currentMode()==='h264'&&gotData){"
                + "if(vv.videoWidth>0){if(st2.textContent.indexOf('已解码')<0)st2.textContent='推流中 · 画面已解码 '+vv.videoWidth+'x'+vv.videoHeight+'（持续更新）';}"
                + "else if(st2.textContent.indexOf('未解码')<0&&st2.textContent.indexOf('推流中')>=0){st2.textContent='推流中 · 浏览器尚未解码出视频轨道（readyState='+vv.readyState+'）';}"
                + "if(!streamEnded&&vv.paused&&gotData)vv.play().catch(function(){});}"
                + "get('/stream_state',function(d){if(!d)return;"
                + "var st3=document.getElementById('sstate');"
                + "if(d.mode==='mjpeg'&&st3.textContent.indexOf('推流中')<0)st3.textContent='推流中 · MJPEG '+d.fps+'fps';"
                + "if(d.mode==='idle'&&currentMode()==='mjpeg')st3.textContent='已停止（可重新开始）';});}"
                + "function toggleStream(){streamOn=!streamOn;var btn=document.getElementById('sbtn');"
                + "var st=document.getElementById('sstate');var img=document.getElementById('screen');"
                + "var video=document.getElementById('h264v');"
                + "if(streamOn){var m=currentMode();st.textContent='正在启动推流…';"
                + "if(m==='h264'||m==='h264fast'){img.style.display='none';video.style.display='inline-block';startH264();}"
                + "else{video.style.display='none';img.src='/stream';img.style.display='inline-block';}"
                + "btn.textContent='停止推流';}"
                + "else{streamEnded=true;stopH264();img.src='';img.style.display='none';video.style.display='none';"
                + "btn.textContent='开始推流';st.textContent='';}}"
                + "function renderSystem(d){if(!d)return;"
                + "document.getElementById('statTime').textContent=d.time||'--:--';"
                + "document.getElementById('statDate').textContent=d.date||'';"
                + "var core=(d.cpuOnline&&d.cpuPossible)?(' · 核 '+d.cpuOnline+'/'+d.cpuPossible):'',app=(d.appCpu>=0?(' · App '+d.appCpu+'%'):'');"
                + "document.getElementById('statCore').textContent='CPU '+(d.cpu>=0?d.cpu+'%':'--')+app+core+' · 内存 '+(d.memPct>=0?d.memPct+'%':'--')+' · GPU '+(d.gpu>=0?d.gpu+'%':'--')+' · 电 '+(d.battery>=0?d.battery+'%':'--');"
                + "var sg=document.getElementById('statGps');if(sg)sg.textContent=d.gps?d.gps:'';"
                + "var temps=d.temps||[];var ring=document.getElementById('ring');ring.innerHTML='';"
                + "var n=Math.max(1,temps.length),cw=ring.clientWidth||346,cx=cw/2,cy=cw/2,r=cw*0.42;"
                + "for(var i=0;i<temps.length;i++){var ang=(-90+i*(360/n))*Math.PI/180;"
                + "var x=cx+r*Math.cos(ang),y=cy+r*Math.sin(ang);"
                + "var el=document.createElement('div');el.style.cssText='position:absolute;left:'+x+'px;top:'+y+'px;transform:translate(-50%,-50%);text-align:center;pointer-events:none';"
                + "el.innerHTML='<div style=\"font-size:9px;color:#8a8272\">'+temps[i].name+'</div><div style=\"font-size:13px;color:#d4af37\">'+temps[i].c.toFixed(0)+'°</div>';"
                + "ring.appendChild(el);}"
                + "if(temps.length===0){var e=document.createElement('div');e.style.cssText='position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#8a8272;font-size:11px';e.textContent='无温度数据';ring.appendChild(e);}}"
                + "function keyEvent(code){var x=new XMLHttpRequest();x.open('GET','/key?code='+code,true);x.send();"
                + "var nm={4:'返回',3:'桌面',187:'最近'},ts=document.getElementById('tstat');if(ts){ts.textContent='已发送：'+nm[code];setTimeout(function(){ts.textContent='';},1200);}}"
                + "var tp=document.getElementById('touchpad');var td={on:false,moved:false,long:false,timer:null,x:0,y:0,lx:0,ly:0,last:0};"
                + "function tPos(ev){if(!tp)return null;var r=tp.getBoundingClientRect();var cx=(ev.clientX-r.left)/r.width,cy=(ev.clientY-r.top)/r.height;"
                + "var dx=cx-0.5,dy=cy-0.5;if(dx*dx+dy*dy>0.25)return null;"
                + "return {x:Math.max(0,Math.min(800,Math.round(cx*800))),y:Math.max(0,Math.min(800,Math.round(cy*800)))};}"
                + "function tSend(q){var x=new XMLHttpRequest();x.open('GET','/touch?'+q,true);x.send();}"
                + "function tMsg(m){var s=document.getElementById('tstat');if(s){s.textContent=m;if(m)setTimeout(function(){if(s.textContent===m)s.textContent='';},1200);}}"
                + "if(tp){tp.addEventListener('pointerdown',function(ev){ev.preventDefault();if(td.on)return;var p=tPos(ev);if(!p)return;"
                + "try{tp.setPointerCapture(ev.pointerId);}catch(e){}"
                + "td.on=true;td.moved=false;td.long=false;td.x=p.x;td.y=p.y;td.lx=p.x;td.ly=p.y;td.last=0;"
                + "td.timer=setTimeout(function(){if(td.on&&!td.moved){td.long=true;tSend('act=long&x='+td.x+'&y='+td.y);tMsg('长按 '+td.x+','+td.y);}},650);});"
                + "tp.addEventListener('pointermove',function(ev){ev.preventDefault();if(!td.on)return;var p=tPos(ev);if(!p)return;"
                + "if(!td.moved&&Math.abs(p.x-td.x)+Math.abs(p.y-td.y)<10)return;"
                + "if(!td.moved){td.moved=true;if(td.timer){clearTimeout(td.timer);td.timer=null;}}"
                + "var now=Date.now();if(now-td.last>=60){tSend('act=move&x='+p.x+'&y='+p.y+'&px='+td.lx+'&py='+td.ly);td.lx=p.x;td.ly=p.y;td.last=now;tMsg('拖动 '+p.x+','+p.y);}});"
                + "function tEnd(){if(!td.on)return;td.on=false;if(td.timer){clearTimeout(td.timer);td.timer=null;}"
                + "if(!td.moved&&!td.long){tSend('act=tap&x='+td.x+'&y='+td.y);tMsg('点击 '+td.x+','+td.y);}}"
                + "tp.addEventListener('pointerup',tEnd);tp.addEventListener('pointercancel',tEnd);"
                + "tp.addEventListener('contextmenu',function(ev){ev.preventDefault();});}"
                + "function camRefresh(){get('/cam/status',function(d){if(!d)return;"
                + "var st=document.getElementById('camState');st.textContent=d.status==='running'?('运行中 · '+d.detail):d.status;"
                + "var u=document.getElementById('camUrls');u.innerHTML=(d.rtsp?'<div>RTSP: '+esc(d.rtsp)+'</div>':'')"
                + "+(d.rtmpUrl?'<div>RTMP: '+esc(d.rtmpUrl)+'</div>':'')"
                + "+(d.webrtc?'<div>WebRTC: '+esc(d.webrtc)+'</div>':'')"
                + "+(d.realFps?'<div style=\"color:#8fbf6a\">实际帧率: '+esc(d.realFps)+' fps</div>':'');"
                + "document.getElementById('camBtn').textContent=d.status==='running'?'停止推流':'启动推流';});}"
                + "function camToggle(){get('/cam/status',function(d){"
                + "if(d&&d.status==='running'){camStop();}else{camStart();}});}"
                + "function camStart(){var x=new XMLHttpRequest();x.open('GET','/cam/start',true);"
                + "x.onload=function(){document.getElementById('camMsg').textContent=x.responseText;setTimeout(camRefresh,1500);};x.send();}"
                + "function camStop(){var x=new XMLHttpRequest();x.open('GET','/cam/stop',true);"
                + "x.onload=function(){document.getElementById('camMsg').textContent=x.responseText;setTimeout(camRefresh,500);};x.send();}"
                + "function frpcRefresh(){get('/frpc/status',function(d){if(!d)return;"
                + "document.getElementById('frpcState').textContent=d.status==='running'?'运行中':(d.status==='error'?'异常':'已停止');"
                + "document.getElementById('frpcStateDetail').textContent=d.detail?(' · '+d.detail):'';"
                + "var l=document.getElementById('frpcLog');l.textContent=d.log;l.scrollTop=l.scrollHeight;});}"
                + "function frpcStart(){var x=new XMLHttpRequest();x.open('GET','/frpc/start',true);"
                + "x.onload=function(){document.getElementById('frpcMsg').textContent=x.responseText;frpcRefresh();};x.send();}"
                + "function frpcStop(){var x=new XMLHttpRequest();x.open('GET','/frpc/stop',true);"
                + "x.onload=function(){document.getElementById('frpcMsg').textContent=x.responseText;frpcRefresh();};x.send();}"
                + "setInterval(function(){get('/system_status',renderSystem);},2000);"
                + "loadConv();setInterval(loadConv,3000);setInterval(streamState,3000);frpcRefresh();setInterval(frpcRefresh,3000);camRefresh();setInterval(camRefresh,3000);adbStatus();setInterval(adbStatus,3000);appState();setInterval(appState,3000);"
                + "</script></body></html>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveCamStatus(OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("status", com.magneo.compass.cam.CameraStreamService.status());
            o.put("detail", com.magneo.compass.cam.CameraStreamService.statusDetail());
            int port = Prefs.getI(app, Prefs.K_RTSP_PORT, 8554);
            o.put("rtsp", "rtsp://" + LOOPBACK_HOST + ":" + port + "/cam");
            o.put("rtmpUrl", Prefs.get(app, Prefs.K_RTMP_URL, ""));
            o.put("camAutoStart", Prefs.getB(app, Prefs.K_CAM_AUTO_START, false));
            o.put("webrtc", com.magneo.compass.cam.WebRtcStreamer.get().state());
            o.put("webrtcError", com.magneo.compass.cam.WebRtcStreamer.get().error());
            o.put("realFps", com.magneo.compass.cam.CameraStreamService.realFps());
            o.put("visionPreviewDirect", com.magneo.compass.cam.CameraStreamService.isVisionPreviewDirect());
            o.put("cameraEncoding", com.magneo.compass.cam.CameraStreamService.isEncodingActive());
            o.put("cameraExternalConsumers", com.magneo.compass.cam.CameraStreamService.externalConsumerCount());
            o.put("fpsInfo", com.magneo.compass.cam.CameraStreamService.fpsInfo());
            o.put("camDiag", com.magneo.compass.cam.CameraStreamService.camDiag());
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("{\"status\":\"error\",\"err\":\"" + e + "\"}").getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveCamOffer(OutputStream out, String body) throws IOException {
        String ok = "false";
        try {
            JSONObject o = new JSONObject(body);
            String sdp = o.optString("sdp", "");
            if (!sdp.isEmpty()) {
                ok = String.valueOf(com.magneo.compass.cam.WebRtcStreamer.get().handleOffer(sdp));
            }
        } catch (Exception e) { ok = "false"; }
        serveText(out, ok);
    }

    private static void serveCamAnswer(OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("sdp", com.magneo.compass.cam.WebRtcStreamer.get().answer());
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = "{\"sdp\":\"\"}".getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveCamPage(OutputStream out, String target) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>真理罗盘 · 摄像头</title>"
                + "<style>body{background:#0d0b08;color:#e8dcc0;font-family:sans-serif;text-align:center;margin:0;padding:20px}"
                + "h1{color:#d4af37;font-size:18px}"
                + "video{width:92mm;height:92mm;border-radius:50%;border:1px solid #6b5a2e;object-fit:cover;background:#000;display:block;margin:0 auto}"
                + "#st{color:#8fbf6a;margin-top:10px;font-size:13px;min-height:18px}"
                + "button{background:#d4af37;color:#0d0b08;border:none;border-radius:8px;padding:8px 14px;margin:6px}"
                + "a{color:#d4af37}</style></head><body>"
                + "<h1>☯ 摄像头直播</h1>"
                + "<video id='v' autoplay playsinline muted></video>"
                + "<div id='st'>连接中…</div>"
                + "<div><button type='button' onclick='startMse()'>MSE 播放</button>"
                + "<button type='button' onclick='startWr()'>WebRTC（实验）</button></div>"
                + "<div style='margin-top:6px;font-size:11px;color:#8a8272'>MSE = H.264 实时流（默认，兼容好）；WebRTC 在这台 MT6580 上硬编兼容性有限，失败时用 MSE 或 RTSP</div>"
                + "<script>"
                + "var access=(location.search.match(/[?&]access=([^&]*)/)||[])[1]||'';try{access=decodeURIComponent(access);}catch(e){}"
                + "function authUrl(u){return u+(u.indexOf('?')>=0?'&':'?')+'access='+encodeURIComponent(access);}"
                + "var v=document.getElementById('v'),st=document.getElementById('st');"
                + "var mse=null,sb=null,abortCtl=null,boxBuf=new Uint8Array(0),boxOff=0,initDone=false,appending=false,pending=[];"
                + "function boxAt(pos){if(pos+8>boxBuf.length)return null;"
                + "var size=((boxBuf[pos]<<24)|(boxBuf[pos+1]<<16)|(boxBuf[pos+2]<<8)|boxBuf[pos+3])>>>0;"
                + "var type=String.fromCharCode(boxBuf[pos+4],boxBuf[pos+5],boxBuf[pos+6],boxBuf[pos+7]);"
                + "return {size:size,type:type,start:pos};}"
                + "function pumpSb(){if(!mse||!sb||sb.updating)return;"
                + "if(pending.length>12){pending=pending.slice(-2);st.textContent='追帧中…';}"
                + "if(!pending.length)return;"
                + "try{sb.appendBuffer(pending.shift());}catch(e){st.textContent='MSE 追加失败: '+e;}}"
                + "function flush(){while(true){"
                + "if(!initDone){var a=boxAt(boxOff),b=boxAt(boxOff+(a?a.size:0));"
                + "if(!a||!b||a.type!=='ftyp'||b.type!=='moov'||boxOff+a.size+b.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+a.size+b.size).buffer);boxOff+=a.size+b.size;initDone=true;}"
                + "else{var c=boxAt(boxOff),d=boxAt(boxOff+(c?c.size:0));"
                + "if(!c||!d||c.type!=='moof'||d.type!=='mdat'||boxOff+c.size+d.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+c.size+d.size).buffer);boxOff+=c.size+d.size;}"
                + "if(boxOff===boxBuf.length){boxBuf=new Uint8Array(0);boxOff=0;}"
                + "pumpSb();}}"
                + "function append(c){var nb=new Uint8Array(boxBuf.length+c.length);nb.set(boxBuf,0);nb.set(c,boxBuf.length);boxBuf=nb;flush();}"
                + "function startMse(){stopWr();st.textContent='MSE 连接中…';"
                + "if(!window.MediaSource){st.textContent='浏览器不支持 MSE';return;}"
                + "mse=new MediaSource();v.src=URL.createObjectURL(mse);boxBuf=new Uint8Array(0);boxOff=0;initDone=false;pending=[];"
                + "mse.addEventListener('sourceopen',function(){"
                + "try{sb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.42E01E\"');}"
                + "catch(e){try{sb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.4D401E\"');}"
                + "catch(e2){st.textContent='无法创建解码器';return;}}"
                + "sb.addEventListener('updateend',pumpSb);"
                + "abortCtl=new AbortController();"
                + "fetch(authUrl('/camhttp'),{signal:abortCtl.signal}).then(function(r){"
                + "if(!r.ok||!r.body){st.textContent='连接失败 '+r.status;return;}"
                + "var reader=r.body.getReader();"
                + "function step(){reader.read().then(function(res){"
                + "if(res.done){st.textContent='流结束';try{mse.endOfStream();}catch(e){}return;}"
                + "append(res.value);v.play().catch(function(){});"
                + "st.textContent='推流中 · H.264';step();}).catch(function(e){if(e.name!=='AbortError')st.textContent='流中断: '+e;});}"
                + "step();}).catch(function(e){if(e.name!=='AbortError')st.textContent='连接失败: '+e;});});}"
                + "function stopWr(){if(abortCtl){try{abortCtl.abort();}catch(e){}abortCtl=null;}"
                + "if(mse){try{mse.endOfStream();}catch(e){}mse=null;sb=null;}"
                + "boxBuf=new Uint8Array(0);boxOff=0;initDone=false;pending=[];}"
                + "function startMse2(){startMse();}"
                + "async function sleep(ms){return new Promise(r=>setTimeout(r,ms));}"
                + "function startWr(){stopWr();st.textContent='WebRTC 连接中…';"
                + "var pc=new RTCPeerConnection();"
                + "pc.ontrack=function(e){v.srcObject=e.streams[0];st.textContent='WebRTC 已连接';};"
                + "pc.onconnectionstatechange=function(){if(pc.connectionState==='failed')st.textContent='WebRTC 失败，请用 MSE 播放';};"
                + "(async function(){try{pc.addTransceiver('video',{direction:'recvonly'});"
                + "var offer=await pc.createOffer();await pc.setLocalDescription(offer);"
                + "while(pc.iceGatheringState!=='complete'){await sleep(200);}"
                + "var r=await fetch(authUrl('/cam/offer'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sdp:pc.localDescription.sdp})});"
                + "var ok=await r.text();if(ok!=='true'){st.textContent='设备拒绝连接';return;}"
                + "st.textContent='等待设备应答…';var ans=null;"
                + "for(var i=0;i<80;i++){var r2=await fetch(authUrl('/cam/answer'));var d=await r2.json();"
                + "if(d&&d.sdp){ans=d;break;}await sleep(500);}"
                + "if(!ans){st.textContent='设备无应答（WebRTC 不可用）';return;}"
                + "await pc.setRemoteDescription(ans);"
                + "}catch(e){st.textContent='WebRTC 错误: '+e;}})();}"
                + "startMse();"
                + "</script></body></html>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveFrpcStatus(OutputStream out) throws IOException {
        try {
            com.magneo.compass.frp.FrpcManager.Snapshot s = com.magneo.compass.frp.FrpcManager.snapshot();
            JSONObject o = new JSONObject();
            o.put("status", s.status);
            o.put("detail", s.detail);
            o.put("log", com.magneo.compass.frp.FrpcManager.logTail(4000));
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("{\"status\":\"error\",\"log\":\"\"}").getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveText(OutputStream out, String text) throws IOException {
        byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveJson(OutputStream out, JSONObject o) throws IOException {
        byte[] b = (o == null ? "{}" : o.toString()).getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveStatus(OutputStream out, boolean authed) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("authed", authed);
            o.put("hasPassword", AppManager.hasPassword(app));
            String savedApiKey = Prefs.get(app, Prefs.K_API_KEY, "");
            String savedVoiceKey = Prefs.get(app, Prefs.K_VOICE_API_KEY, "");
            o.put("apiKeySet", !savedApiKey.trim().isEmpty());
            o.put("apiKeyMask", maskSecret(savedApiKey));
            o.put("voiceApiKeySet", !savedVoiceKey.trim().isEmpty());
            o.put("voiceApiKeyMask", maskSecret(savedVoiceKey));
            o.put("vadEnabled", Prefs.vadEnabled(app));
            o.put("localTtsFirst", Prefs.getB(app, Prefs.K_LOCAL_TTS_FIRST, false));
            o.put("asrUrlSet", !Prefs.get(app, Prefs.K_ASR_URL, "").trim().isEmpty());
            o.put("asrFinalUrlSet", !Prefs.get(app, Prefs.K_ASR_FINAL_URL, "").trim().isEmpty());
            o.put("ttsUrlSet", !Prefs.get(app, Prefs.K_TTS_URL, "").trim().isEmpty());
            o.put("fsCount", FsManager.list(app).size());
            if (!authed) {
                o.put("locked", true);
                byte[] b = o.toString().getBytes("UTF-8");
                writeHead(out, "application/json; charset=utf-8", b.length);
                out.write(b);
                return;
            }
            String provider = ProviderConfig.normalizeName(Prefs.get(app, Prefs.K_PROVIDER, ""));
            ProviderConfig preset = ProviderConfig.byName(provider);
            String baseUrl = Prefs.get(app, Prefs.K_BASE_URL, "");
            if (baseUrl.trim().isEmpty() && ProviderConfig.PROVIDER_DEEPSEEK.equals(provider)) {
                baseUrl = ProviderConfig.DEEPSEEK_BASE_URL;
            }
            String textModel = Prefs.get(app, Prefs.K_TEXT_MODEL, "");
            String visionModel = Prefs.get(app, Prefs.K_VISION_MODEL, "");
            if (textModel.trim().isEmpty()) textModel = preset.textModel;
            if (visionModel.trim().isEmpty()) visionModel = preset.visionModel;
            o.put("provider", provider);
            o.put("apiKey", "");
            o.put("voiceApiKey", "");
            o.put("reasoningEffort", Prefs.get(app, Prefs.K_REASONING_EFFORT, Prefs.DEFAULT_REASONING_EFFORT));
            o.put("textMaxTokens", String.valueOf(Prefs.getI(app, Prefs.K_TEXT_MAX_TOKENS,
                    Prefs.DEFAULT_TEXT_MAX_TOKENS)));
            o.put("voiceMaxTokens", String.valueOf(Prefs.getI(app, Prefs.K_VOICE_MAX_TOKENS,
                    Prefs.DEFAULT_VOICE_MAX_TOKENS)));
            o.put("visionMaxTokens", String.valueOf(Prefs.getI(app, Prefs.K_VISION_MAX_TOKENS,
                    Prefs.DEFAULT_VISION_MAX_TOKENS)));
            o.put("textTemperature", trimFloat(Prefs.getF(app, Prefs.K_TEXT_TEMPERATURE,
                    Prefs.DEFAULT_TEXT_TEMPERATURE)));
            o.put("voiceTemperature", trimFloat(Prefs.getF(app, Prefs.K_VOICE_TEMPERATURE,
                    Prefs.DEFAULT_VOICE_TEMPERATURE)));
            o.put("visionTemperature", trimFloat(Prefs.getF(app, Prefs.K_VISION_TEMPERATURE,
                    Prefs.DEFAULT_VISION_TEMPERATURE)));
            o.put("baseUrl", baseUrl);
            o.put("textBaseUrl", Prefs.get(app, Prefs.K_TEXT_BASE_URL, ""));
            o.put("visionBaseUrl", Prefs.get(app, Prefs.K_VISION_BASE_URL, ""));
            o.put("textModel", textModel);
            o.put("visionModel", visionModel);
            o.put("asrUrl", Prefs.get(app, Prefs.K_ASR_URL, ""));
            o.put("asrFinalUrl", Prefs.get(app, Prefs.K_ASR_FINAL_URL, ""));
            o.put("asrModel", Prefs.get(app, Prefs.K_ASR_MODEL, ""));
            o.put("ttsUrl", Prefs.get(app, Prefs.K_TTS_URL, ""));
            o.put("ttsModel", Prefs.get(app, Prefs.K_TTS_MODEL, ""));
            o.put("ttsVoice", Prefs.get(app, Prefs.K_TTS_VOICE, ""));
            o.put("localTtsFirst", Prefs.getB(app, Prefs.K_LOCAL_TTS_FIRST, false));
            o.put("bargeMode", Prefs.get(app, Prefs.K_BARGE_MODE, Prefs.DEFAULT_BARGE_MODE));
            o.put("interactionMode", Prefs.interactionMode(app));
            o.put("visionInterval", String.valueOf(Prefs.getI(app, Prefs.K_VISION_INTERVAL, 2)));
            o.put("visionFrameSource", Prefs.visionFrameSource(app));
            o.put("visionOverlayStyle", Prefs.visionOverlayStyle(app));
            o.put("vadEnabled", Prefs.vadEnabled(app));
            o.put("vadSensitivity", String.valueOf(Prefs.getI(app, Prefs.K_VAD_SENSITIVITY, 600)));
            int oracleShakeForce = oracleShakeForce();
            o.put("oracleShakeForce", String.valueOf(oracleShakeForce));
            o.put("oracleShakeLevel", String.valueOf(Math.max(1, Math.min(5,
                    Math.round(oracleShakeForce / 25f) + 1))));
            o.put("searchEngine", Prefs.get(app, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"));
            o.put("ignoreSsl", Prefs.getB(app, Prefs.K_IGNORE_SSL, false));
            o.put("uaDesktop", Prefs.getB(app, Prefs.K_UA_DESKTOP, false));
            o.put("noImages", Prefs.getB(app, Prefs.K_NO_IMAGES, false));
            o.put("browserRoundFit", Prefs.getB(app, Prefs.K_BROWSER_ROUND_FIT, true));
            o.put("convMaxKb", String.valueOf(ConversationLog.maxKb(app)));
            o.put("convCleanMin", String.valueOf(Prefs.getI(app, Prefs.K_CONV_CLEAN_MIN, 60)));
            o.put("debugMode", Prefs.getB(app, Prefs.K_DEBUG_MODE, false));
            o.put(Prefs.K_VOICE_DIAGNOSTIC_OVERLAYS, Prefs.voiceDiagnosticOverlays(app));
            o.put("debugMaxKb", String.valueOf(DebugLog.maxKb(app)));
            o.put("sysPromptVoice", Prefs.get(app, Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE));
            o.put("sysPromptVision", Prefs.get(app, Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION));
            o.put("streamMode", normalizeStreamMode(Prefs.get(app, Prefs.K_STREAM_MODE, "h264")));
            o.put("streamFps", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_FPS, 1)));
            o.put("streamQuality", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_QUALITY, 55)));
            o.put("streamScale", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_SCALE, 2)));
            o.put("streamBitrate", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_BITRATE, 1500)));
            o.put("mainRenderer", Prefs.mainRenderer(app));
            o.put("mainFpsMode", Prefs.mainFpsMode(app));
            o.put(Prefs.K_SCREEN_POLICY, Prefs.screenPolicy(app));
            o.put(Prefs.K_ROOT_GRANT_NOTIFICATIONS, Prefs.rootGrantNotifications(app));
            o.put(Prefs.K_SYSTEM_LOCKSCREEN_ENABLED, Prefs.systemLockscreenEnabled(app));
            o.put(Prefs.K_LOW_BATTERY_SOUND, Prefs.lowBatterySoundEnabled(app));
            o.put("rootGrantStatus",
                    com.magneo.compass.RootGrantNotificationManager.status(app).toJson());
            o.put("systemLockscreenStatus",
                    com.magneo.compass.SystemLockscreenManager.status(app).toJson());
            o.put("lowBatterySoundStatus",
                    com.magneo.compass.SystemLowBatterySoundManager.status(app).toJson());
            o.put("locSource", Prefs.locSource(app));
            o.put("locSourceLabel", Prefs.locSourceLabel(app));
            o.put("locWifiUrl", Prefs.locWifiUrl(app));
            o.put("locIpUrl", Prefs.locIpUrl(app));
            o.put("showLoc", Prefs.locationDisplayEnabled(app));
            putMcpConfigStatus(o);
            o.put("frpcConfig", Prefs.get(app, Prefs.K_FRPC_CONFIG, ""));
            o.put("camId", String.valueOf(Prefs.getI(app, Prefs.K_CAM_ID, 0)));
            o.put("camWidth", String.valueOf(Prefs.getI(app, Prefs.K_CAM_WIDTH, 1280)));
            o.put("camHeight", String.valueOf(Prefs.getI(app, Prefs.K_CAM_HEIGHT, 720)));
            o.put("camFps", String.valueOf(Prefs.getI(app, Prefs.K_CAM_FPS, 24)));
            o.put("camBitrate", String.valueOf(Prefs.getI(app, Prefs.K_CAM_BITRATE, 5000)));
            o.put("rtspPort", String.valueOf(Prefs.getI(app, Prefs.K_RTSP_PORT, 8554)));
            o.put("rtmpUrl", Prefs.get(app, Prefs.K_RTMP_URL, ""));
            o.put("camAutoStart", Prefs.getB(app, Prefs.K_CAM_AUTO_START, false));
            o.put("mode", H264SurfaceStreamer.isActive() ? "h264fast"
                    : (H264Streamer.isActive() ? "h264" : (ScreenStreamer.isActive() ? "mjpeg" : "idle")));
            o.put("ip", LOOPBACK_HOST);
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveSave(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> fields = form(body);
            saveMcpServers(fields);
            if (isTrue(fields.get("clearApiKey"))) Prefs.put(app, Prefs.K_API_KEY, "");
            if (isTrue(fields.get("clearVoiceApiKey"))) Prefs.put(app, Prefs.K_VOICE_API_KEY, "");
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k == null) continue;
                if (k.equals("clearApiKey") || k.equals("clearVoiceApiKey")) continue;
                if (k.startsWith("mcpServer")) continue;
                if (k.equals(Prefs.K_API_KEY) || k.equals(Prefs.K_VOICE_API_KEY)) {
                    if (v == null || v.trim().isEmpty()) continue;
                    Prefs.put(app, k, v.trim());
                    continue;
                }
                if (k.equals("visionInterval") || k.equals("vadSensitivity")) {
                    try { Prefs.putI(app, k, Integer.parseInt(v)); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_ORACLE_SHAKE_FORCE)) {
                    try { Prefs.putI(app, k, clamp(Integer.parseInt(v), 0, 100)); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_ORACLE_SHAKE_LEVEL)) {
                    try {
                        int old = clamp(Integer.parseInt(v), 1, 5);
                        Prefs.putI(app, Prefs.K_ORACLE_SHAKE_FORCE, clamp(old * 18 - 2, 0, 100));
                    } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_VISION_FRAME_SOURCE)) {
                    Prefs.put(app, k, Prefs.normalizeVisionFrameSource(v));
                } else if (k.equals(Prefs.K_VISION_OVERLAY_STYLE)) {
                    Prefs.put(app, k, Prefs.normalizeVisionOverlayStyle(v));
                } else if (k.equals(Prefs.K_MAIN_RENDERER)) {
                    Prefs.put(app, k, Prefs.normalizeMainRenderer(v));
                } else if (k.equals(Prefs.K_MAIN_FPS_MODE)) {
                    Prefs.put(app, k, Prefs.normalizeMainFpsMode(v));
                } else if (k.equals(Prefs.K_SCREEN_POLICY)) {
                    Prefs.put(app, k, Prefs.screenPolicyValue(v));
                    com.magneo.compass.MainActivity.applyScreenPolicyToActive();
                } else if (k.equals(Prefs.K_LOC_SOURCE)) {
                    Prefs.put(app, k, Prefs.normalizeLocSource(v));
                    syncRuntimeGpsSource();
                } else if (k.equals(Prefs.K_SHOW_LOC)) {
                    boolean on = "true".equalsIgnoreCase(v) || "1".equals(v);
                    Prefs.putB(app, k, on);
                    Prefs.put(app, Prefs.K_LOC_SOURCE, on ? Prefs.LOC_SOURCE_WIFI_IP : Prefs.LOC_SOURCE_OFF);
                    syncRuntimeGpsSource();
                } else if (isMaxTokenKey(k)) {
                    try { Prefs.putI(app, k, clamp(Integer.parseInt(v), 0, 8192)); } catch (Exception ignored) {}
                } else if (isTemperatureKey(k)) {
                    try { Prefs.putF(app, k, clampF(Float.parseFloat(v), 0f, 2f)); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_BARGE_MODE)) {
                    Prefs.put(app, k, normalizeBargeMode(v));
                } else if (k.equals(Prefs.K_INTERACTION_MODE)) {
                    Prefs.put(app, k, Prefs.normalizeInteractionMode(v));
                } else if (k.equals(Prefs.K_STREAM_MODE)) {
                    Prefs.put(app, k, normalizeStreamMode(v));
                } else if (k.equals("convMaxKb")) {
                    try { Prefs.putI(app, k, Math.max(100, Math.min(4096, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("convCleanMin")) {
                    try { Prefs.putI(app, k, Math.max(0, Math.min(1440, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_DEBUG_MAX_KB)) {
                    try { Prefs.putI(app, k, Math.max(256, Math.min(4096, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_MCP_MAX_TOOL_ROUNDS)) {
                    try { Prefs.putI(app, k, Math.max(0, Math.min(6, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_MCP_SLOW_HINT_MS)) {
                    try { Prefs.putI(app, k, Math.max(0, Math.min(10000, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_MCP_SLOW_HINT_MAX_COUNT)) {
                    try { Prefs.putI(app, k, Math.max(0, Math.min(10, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_MCP_SLOW_HINT_SCHEDULE_MS)
                        || k.equals(Prefs.K_MCP_SLOW_HINT_PHRASES)) {
                    Prefs.put(app, k, v == null ? "" : v.trim());
                } else if (k.equals("streamFps")) {
                    try { Prefs.putI(app, k, Math.max(1, Math.min(10, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamQuality")) {
                    try { Prefs.putI(app, k, Math.max(20, Math.min(90, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamScale")) {
                    try { Prefs.putI(app, k, Math.max(1, Math.min(2, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamBitrate")) {
                    try { Prefs.putI(app, k, Math.max(300, Math.min(8000, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals(Prefs.K_SYSTEM_LOCKSCREEN_ENABLED)) {
                    boolean on = "true".equalsIgnoreCase(v) || "1".equals(v);
                    com.magneo.compass.SystemLockscreenManager.Snapshot state =
                            com.magneo.compass.SystemLockscreenManager.setEnabledBlocking(
                                    app, on, true);
                    if (!state.ok) throw new IOException(state.detail);
                } else if (k.equals(Prefs.K_LOW_BATTERY_SOUND)) {
                    boolean on = "true".equalsIgnoreCase(v) || "1".equals(v);
                    com.magneo.compass.SystemLowBatterySoundManager.Snapshot state =
                            com.magneo.compass.SystemLowBatterySoundManager.setEnabledBlocking(
                                    app, on, true);
                    if (!state.ok) throw new IOException(state.detail);
                } else if (isBoolKey(k)) {
                    boolean on = "true".equalsIgnoreCase(v) || "1".equals(v);
                    Prefs.putB(app, k, on);
                    if (k.equals(Prefs.K_VOICE_DIAGNOSTIC_OVERLAYS)) {
                        com.magneo.compass.MainActivity.applyVoiceDiagnosticPrefToActive();
                        com.magneo.compass.vision.VisionActivity.applyVoiceDiagnosticPrefToActive();
                    }
                    if (k.equals(Prefs.K_ROOT_GRANT_NOTIFICATIONS)) {
                        com.magneo.compass.RootGrantNotificationManager.Snapshot state =
                                com.magneo.compass.RootGrantNotificationManager.applyBlocking(on);
                        if (!state.ok) throw new IOException(state.detail);
                    }
                    if (k.equals(Prefs.K_VAD_ENABLED)) {
                        android.content.Intent i = new android.content.Intent(app,
                                com.magneo.compass.voice.VadService.class);
                        if (on) app.startService(i);
                        else {
                            app.stopService(i);
                            try {
                                com.magneo.compass.voice.VoiceController.get(app, null)
                                        .stopContinuousListening();
                            } catch (Throwable ignored) {}
                        }
                    }
                } else if (k.equals(Prefs.K_PROVIDER)) {
                    Prefs.put(app, k, ProviderConfig.normalizeName(v));
                } else {
                    Prefs.put(app, k, v);
                }
            }
            Prefs.exportBackup(app);
            byte[] b = "设置已保存".getBytes("UTF-8");
            writeHead(out, "text/plain; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("保存失败: " + e.getMessage()).getBytes("UTF-8");
            writeHead(out, "text/plain; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveBackupExport(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            Prefs.exportBackup(app);
            File f = backupFile();
            if (!f.exists() || f.length() <= 0) {
                o.put("ok", false).put("err", "未找到备份文件");
            } else {
                o.put("ok", true);
                o.put("path", f.getAbsolutePath());
                o.put("bytes", f.length());
                o.put("content", readAll(f, 1024 * 1024));
            }
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveBackupRestore(OutputStream out, String body) throws IOException {
        JSONObject o = new JSONObject();
        try {
            Map<String, String> f = form(body);
            String content = f.get("content");
            if (content == null || content.trim().isEmpty()) content = body;
            Object root = new org.json.JSONTokener(content).nextValue();
            if (!(root instanceof JSONObject)) {
                serveJson(out, err("备份内容不是 JSON 对象"));
                return;
            }
            File bf = backupFile();
            File dir = bf.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileOutputStream fo = new FileOutputStream(bf, false)) {
                fo.write(((JSONObject) root).toString(2).getBytes("UTF-8"));
            }
            boolean ok = Prefs.restoreBackupIfPresent(app);
            o.put("ok", ok);
            o.put("msg", ok ? "已从备份恢复" : "恢复失败");
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveTestLlm(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder text = new StringBuilder();
        final AtomicReference<String> err = new AtomicReference<>("");
        final long start = System.currentTimeMillis();
        final long[] first = {-1L};
        try {
            LlmClient llm = new LlmClient(app);
            if (llm.apiKey.trim().isEmpty()) {
                serveJson(out, err("未配置大模型 API Key"));
                return;
            }
            ArrayList<LlmClient.Msg> msgs = new ArrayList<>();
            msgs.add(new LlmClient.Msg("system", "你是链路测试助手。只用中文一句话回复。"));
            msgs.add(new LlmClient.Msg("user", "真理罗盘大模型链路测试，请回复收到。"));
            okhttp3.Call call = llm.chat(msgs, false, llm.voiceOptions(), new LlmClient.StreamCallback() {
                @Override public void onDelta(String s) {
                    if (first[0] < 0) first[0] = System.currentTimeMillis() - start;
                    if (s != null) text.append(s);
                }

                @Override public void onDone(String full) {
                    if (text.length() == 0 && full != null) text.append(full);
                    latch.countDown();
                }

                @Override public void onError(String msg) {
                    err.set(msg == null ? "请求失败" : msg);
                    latch.countDown();
                }
            });
            boolean done = latch.await(25000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                if (call != null) call.cancel();
                o.put("ok", false).put("err", "LLM 测试超时");
            } else if (!err.get().isEmpty()) {
                o.put("ok", false).put("err", err.get());
            } else {
                o.put("ok", true);
                o.put("firstDeltaMs", first[0]);
                o.put("text", clip(text.toString(), 300));
            }
            o.put("ms", System.currentTimeMillis() - start);
            o.put("model", llm.textModel);
            o.put("baseUrl", llm.textBaseUrl);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveTestAsrFinal(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<>("");
        final AtomicReference<String> err = new AtomicReference<>("");
        final long start = System.currentTimeMillis();
        try {
            LlmClient llm = new LlmClient(app);
            okhttp3.Call call = llm.transcribe(silenceWav(800), new LlmClient.TextCallback() {
                @Override public void onResult(String t) {
                    text.set(t == null ? "" : t);
                    latch.countDown();
                }

                @Override public void onError(String msg) {
                    err.set(msg == null ? "请求失败" : msg);
                    latch.countDown();
                }
            });
            boolean done = latch.await(20000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                if (call != null) call.cancel();
                o.put("ok", false).put("err", "Final ASR 测试超时");
            } else if (!err.get().isEmpty()) {
                o.put("ok", false).put("err", err.get());
            } else {
                o.put("ok", true);
                o.put("text", text.get());
                o.put("sample", "静音 800ms WAV");
                o.put("note", text.get().trim().isEmpty()
                        ? "接口可返回，静音样本无文本；准确率请用真实录音测试"
                        : "接口可返回，但静音样本出现文本，准确率请用真实录音测试");
            }
            o.put("ms", System.currentTimeMillis() - start);
            o.put("url", llm.asrFinalUrl);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveTestTts(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        final long start = System.currentTimeMillis();
        try {
            LlmClient llm = new LlmClient(app);
            TtsProbeResult r = runTtsProbe(llm, "真理罗盘 TTS 链路测试。", 30000);
            boolean retried = false;
            if (!r.ok && isResetLike(r.err)) {
                retried = true;
                r = runTtsProbe(llm, "真理罗盘 TTS 链路测试。", 30000);
            }
            if (!r.ok) {
                o.put("ok", false).put("err", r.err);
            } else {
                o.put("ok", r.bytes > 0);
                o.put("bytes", r.bytes);
                o.put("contentType", r.contentType);
            }
            o.put("ms", System.currentTimeMillis() - start);
            o.put("model", llm.ttsModel);
            o.put("voice", llm.ttsVoice);
            o.put("retry", retried);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static TtsProbeResult runTtsProbe(LlmClient llm, String text, long timeoutMs)
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final TtsProbeResult r = new TtsProbeResult();
        okhttp3.Call call = llm.synthesize(text, new LlmClient.BytesCallback() {
            @Override public void onResult(byte[] audio) {
                r.ok = audio != null && audio.length > 0;
                r.bytes = audio == null ? 0 : audio.length;
                if (!r.ok) r.err = "TTS 返回空音频";
                latch.countDown();
            }

            @Override public void onResult(byte[] audio, String ct) {
                r.contentType = ct == null ? "" : ct;
                onResult(audio);
            }

            @Override public void onError(String msg) {
                r.ok = false;
                r.err = msg == null ? "请求失败" : msg;
                latch.countDown();
            }
        });
        boolean done = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!done) {
            if (call != null) call.cancel();
            r.ok = false;
            r.err = "TTS 测试超时";
        }
        return r;
    }

    private static boolean isResetLike(String msg) {
        String s = msg == null ? "" : msg.toLowerCase(Locale.US);
        return s.contains("econnreset") || s.contains("connection reset")
                || s.contains("unexpected end of stream") || s.contains("closed");
    }

    private static class TtsProbeResult {
        boolean ok;
        long bytes;
        String contentType = "";
        String err = "";
    }

    private static void serveTtsVoices(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> err = new AtomicReference<>("");
        final ArrayList<String> voices = new ArrayList<>();
        final long start = System.currentTimeMillis();
        try {
            LlmClient llm = new LlmClient(app);
            okhttp3.Call call = llm.listTtsVoices(new LlmClient.VoicesCallback() {
                @Override public void onResult(List<String> result) {
                    if (result != null) voices.addAll(result);
                    latch.countDown();
                }

                @Override public void onError(String msg) {
                    err.set(msg == null ? "请求失败" : msg);
                    latch.countDown();
                }
            });
            boolean done = latch.await(20000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                if (call != null) call.cancel();
                o.put("ok", false).put("err", "查询音色超时");
            } else if (!err.get().isEmpty()) {
                o.put("ok", false).put("err", err.get());
            } else {
                o.put("ok", true);
                JSONArray arr = new JSONArray();
                for (String v : voices) arr.put(v);
                o.put("voices", arr);
            }
            o.put("ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveMcpStatus(OutputStream out, boolean forceRefresh) throws IOException {
        serveJson(out, McpManager.status(app, forceRefresh));
    }

    /** tools/list does not exercise a tool's downstream credentials; this does. */
    private static void serveMcpCall(OutputStream out, String body) throws IOException {
        JSONObject o = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            Map<String, String> f = form(body);
            String name = trim(f.get("toolName"));
            String args = trim(f.get("toolArgs"));
            if (name.isEmpty()) throw new IllegalArgumentException("请选择 MCP 工具");
            if (args.isEmpty()) args = "{}";
            Object parsed = new org.json.JSONTokener(args).nextValue();
            if (!(parsed instanceof JSONObject)) {
                throw new IllegalArgumentException("参数必须是 JSON 对象");
            }
            McpClient.ToolResult result = McpManager.call(app, name, args);
            boolean ok = result != null && !result.isError;
            o.put("ok", ok);
            o.put("tool", name);
            o.put("text", clip(result == null ? "工具没有返回结果" : result.text, 6000));
            if (!ok) o.put("err", "工具返回错误");
        } catch (Exception e) {
            putErr(o, clip(e.getMessage(), 800));
        }
        try { o.put("ms", System.currentTimeMillis() - start); } catch (Exception ignored) {}
        serveJson(out, o);
    }

    private static void serveOpenTtsTest(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            android.content.Intent i = new android.content.Intent(app,
                    com.magneo.compass.voice.TtsTestActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(i);
            o.put("ok", true);
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void putMcpConfigStatus(JSONObject o) {
        try {
            o.put(Prefs.K_MCP_ENABLED, Prefs.mcpEnabled(app));
            o.put(Prefs.K_MCP_MAX_TOOL_ROUNDS, String.valueOf(Prefs.mcpMaxToolRounds(app)));
            o.put(Prefs.K_MCP_SLOW_HINT_ENABLED, Prefs.mcpSlowHintEnabled(app));
            o.put(Prefs.K_MCP_SLOW_HINT_MS, String.valueOf(Prefs.mcpSlowHintMs(app)));
            o.put(Prefs.K_MCP_SLOW_HINT_SCHEDULE_MS, Prefs.get(app,
                    Prefs.K_MCP_SLOW_HINT_SCHEDULE_MS,
                    Prefs.DEFAULT_MCP_SLOW_HINT_SCHEDULE_MS));
            o.put(Prefs.K_MCP_SLOW_HINT_MAX_COUNT, String.valueOf(Prefs.mcpSlowHintMaxCount(app)));
            o.put(Prefs.K_MCP_SLOW_HINT_PHRASES, Prefs.get(app,
                    Prefs.K_MCP_SLOW_HINT_PHRASES,
                    Prefs.DEFAULT_MCP_SLOW_HINT_PHRASES));
            List<McpServerConfig> servers = McpServerConfig.load(app);
            for (int i = 1; i <= 3; i++) {
                String id = "mcp" + i;
                McpServerConfig c = findMcpServer(servers, id);
                String p = "mcpServer" + i;
                o.put(p + "Enabled", c != null && c.enabled);
                o.put(p + "Name", c == null ? "" : c.name);
                o.put(p + "Url", c == null ? "" : c.url);
                o.put(p + "Token", "");
                o.put(p + "TokenSet", c != null && !c.bearerToken.isEmpty());
                o.put(p + "TokenMask", c == null ? "未设置" : maskSecret(c.bearerToken));
                o.put(p + "TimeoutMs", String.valueOf(c == null
                        ? Prefs.DEFAULT_MCP_TOOL_TIMEOUT_MS : c.timeoutMs));
            }
        } catch (Exception ignored) {}
    }

    private static void saveMcpServers(Map<String, String> fields) {
        if (fields == null || !fields.containsKey("mcpServer1Url")) return;
        List<McpServerConfig> old = McpServerConfig.load(app);
        JSONArray arr = new JSONArray();
        for (int i = 1; i <= 3; i++) {
            String p = "mcpServer" + i;
            String id = "mcp" + i;
            String name = trim(fields.get(p + "Name"));
            String url = trim(fields.get(p + "Url"));
            String token = fields.get(p + "Token");
            int timeout = parseInt(fields.get(p + "TimeoutMs"), Prefs.DEFAULT_MCP_TOOL_TIMEOUT_MS);
            McpServerConfig previous = findMcpServer(old, id);
            if ((token == null || token.trim().isEmpty()) && previous != null) {
                token = previous.bearerToken;
            }
            boolean enabled = isTrue(fields.get(p + "Enabled"));
            if (url.isEmpty()) continue;
            try {
                arr.put(new JSONObject()
                        .put("id", id)
                        .put("name", name.isEmpty() ? ("MCP " + i) : name)
                        .put("url", McpServerConfig.normalizeUrl(url))
                        .put("enabled", enabled)
                        .put("bearerToken", token == null ? "" : token.trim())
                        .put("timeoutMs", Math.max(1000, Math.min(60000, timeout))));
            } catch (Exception ignored) {}
        }
        Prefs.put(app, Prefs.K_MCP_SERVERS, arr.toString());
    }

    private static McpServerConfig findMcpServer(List<McpServerConfig> list, String id) {
        if (list == null) return null;
        for (McpServerConfig c : list) {
            if (c != null && c.id.equals(id)) return c;
        }
        return null;
    }

    private static void serveFsList(OutputStream out) throws IOException {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (FsManager.Conn c : FsManager.list(app)) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("type", c.type);
                o.put("host", c.host);
                o.put("port", c.port);
                o.put("user", c.user);
                o.put("root", c.root);
                o.put("domain", c.domain);
                o.put("passSet", c.pass != null && !c.pass.isEmpty());
                o.put("passMask", maskSecret(c.pass));
                arr.put(o);
            }
            root.put("ok", true);
            root.put("connections", arr);
        } catch (Exception e) {
            putErr(root, e.getMessage());
        }
        serveJson(out, root);
    }

    private static void serveFsSave(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> f = form(body);
            String id = trim(f.get("id"));
            FsManager.Conn existing = id.isEmpty() ? null : FsManager.byId(app, id);
            FsManager.Conn c = buildFsConn(f, existing);
            if (c.host.trim().isEmpty()) {
                serveJson(out, err("请填写网盘主机"));
                return;
            }
            FsManager.save(app, c);
            JSONObject o = new JSONObject();
            o.put("ok", true).put("id", c.id).put("msg", "网盘连接已保存");
            serveJson(out, o);
        } catch (Exception e) {
            serveJson(out, err(e.getMessage()));
        }
    }

    private static void serveFsRemove(OutputStream out, String body) throws IOException {
        try {
            String id = trim(form(body).get("id"));
            if (id.isEmpty()) {
                serveJson(out, err("缺少连接 ID"));
                return;
            }
            FsManager.remove(app, id);
            JSONObject o = new JSONObject();
            o.put("ok", true).put("msg", "已删除连接");
            serveJson(out, o);
        } catch (Exception e) {
            serveJson(out, err(e.getMessage()));
        }
    }

    private static void serveFsTest(OutputStream out, String body) throws IOException {
        NetFs fs = null;
        JSONObject o = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            Map<String, String> f = form(body);
            String id = trim(f.get("id"));
            FsManager.Conn existing = id.isEmpty() ? null : FsManager.byId(app, id);
            FsManager.Conn c = buildFsConn(f, existing);
            fs = FsManager.connect(app, c);
            List<NetFs.Entry> entries = fs.list("");
            o.put("ok", true);
            o.put("entries", entries == null ? 0 : entries.size());
            o.put("msg", "连接成功");
        } catch (Exception e) {
            putErr(o, e.getMessage());
        } finally {
            if (fs != null) {
                try { fs.close(); } catch (Exception ignored) {}
            }
        }
        try { o.put("ms", System.currentTimeMillis() - start); } catch (Exception ignored) {}
        serveJson(out, o);
    }

    private static boolean isBoolKey(String k) {
        return k.equals("localTtsFirst") || k.equals("visionEnabled") || k.equals("vadEnabled")
                || k.equals("ignoreSsl") || k.equals("uaDesktop") || k.equals("noImages")
                || k.equals("browserRoundFit") || k.equals("camAutoStart")
                || k.equals(Prefs.K_MCP_ENABLED)
                || k.equals(Prefs.K_MCP_SLOW_HINT_ENABLED)
                || k.equals(Prefs.K_DEBUG_MODE)
                || k.equals(Prefs.K_VOICE_DIAGNOSTIC_OVERLAYS)
                || k.equals(Prefs.K_ROOT_GRANT_NOTIFICATIONS)
                || k.equals(Prefs.K_SYSTEM_LOCKSCREEN_ENABLED);
    }

    private static void syncRuntimeGpsSource() {
        try {
            com.magneo.compass.SensorHub h = com.magneo.compass.SensorHub.instance;
            if (h != null) h.setGpsEnabled(Prefs.locSourceGpsDiag(app));
        } catch (Exception ignored) {}
        try {
            com.magneo.compass.MainActivity.applyLocationPrefsToActive();
        } catch (Exception ignored) {}
    }

    private static Map<String, String> form(String body) {
        HashMap<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) return out;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            try {
                String k = URLDecoder.decode(kv[0], "UTF-8");
                String v = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                out.put(k, v);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean isTrue(String v) {
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        putErr(o, msg);
        return o;
    }

    private static void putErr(JSONObject o, String msg) {
        try {
            o.put("ok", false);
            o.put("err", msg == null || msg.isEmpty() ? "操作失败" : msg);
        } catch (Exception ignored) {}
    }

    private static String maskSecret(String s) {
        String v = trim(s);
        if (v.isEmpty()) return "未设置";
        if (v.length() <= 8) return "已设置";
        return v.substring(0, Math.min(4, v.length())) + "..." + v.substring(v.length() - 4);
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        if (v.length() <= max) return v;
        return v.substring(0, max) + "...";
    }

    private static File backupFile() {
        return new File(new File(android.os.Environment.getExternalStorageDirectory(),
                "oracle-compass-backup"), "prefs.json");
    }

    private static String readAll(File f, int max) throws IOException {
        if (f == null || !f.exists()) return "";
        if (f.length() > max) throw new IOException("文件过大");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("文件过大");
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static FsManager.Conn buildFsConn(Map<String, String> f, FsManager.Conn existing) {
        FsManager.Conn c = existing == null ? new FsManager.Conn() : existing;
        String id = trim(f.get("id"));
        if (!id.isEmpty()) c.id = id;
        if (f.containsKey("type")) c.type = normalizeFsType(f.get("type"));
        if (f.containsKey("host")) c.host = trim(f.get("host"));
        if (f.containsKey("port")) c.port = parseInt(f.get("port"), c.port);
        if (f.containsKey("user")) c.user = trim(f.get("user"));
        if (f.containsKey("pass")) {
            String pass = f.get("pass");
            if (pass != null && !pass.isEmpty()) c.pass = pass;
        }
        if (f.containsKey("root")) c.root = trim(f.get("root"));
        if (f.containsKey("domain")) c.domain = trim(f.get("domain"));
        if (f.containsKey("name")) c.name = trim(f.get("name"));
        if (c.name.isEmpty()) c.name = c.host + " (" + c.type + ")";
        return c;
    }

    private static String normalizeFsType(String type) {
        String t = trim(type);
        if ("WebDAV".equals(t) || "SMB".equals(t) || "NFS".equals(t)) return t;
        return "FTP";
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(trim(s)); } catch (Exception e) { return def; }
    }

    private static byte[] silenceWav(int ms) {
        int samples = Math.max(1, 16000 * Math.max(1, ms) / 1000);
        int dataLen = samples * 2;
        byte[] out = new byte[44 + dataLen];
        putAscii(out, 0, "RIFF");
        putLe32(out, 4, 36 + dataLen);
        putAscii(out, 8, "WAVEfmt ");
        putLe32(out, 16, 16);
        putLe16(out, 20, 1);
        putLe16(out, 22, 1);
        putLe32(out, 24, 16000);
        putLe32(out, 28, 16000 * 2);
        putLe16(out, 32, 2);
        putLe16(out, 34, 16);
        putAscii(out, 36, "data");
        putLe32(out, 40, dataLen);
        return out;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length() && off + i < b.length; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static boolean isMaxTokenKey(String k) {
        return k.equals(Prefs.K_TEXT_MAX_TOKENS) || k.equals(Prefs.K_VOICE_MAX_TOKENS)
                || k.equals(Prefs.K_VISION_MAX_TOKENS);
    }

    private static boolean isTemperatureKey(String k) {
        return k.equals(Prefs.K_TEXT_TEMPERATURE) || k.equals(Prefs.K_VOICE_TEMPERATURE)
                || k.equals(Prefs.K_VISION_TEMPERATURE);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static String trimFloat(float v) {
        if (Math.abs(v - Math.round(v)) < 0.0001f) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String normalizeBargeMode(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.US);
        if (Prefs.BARGE_MODE_OFF.equals(s) || Prefs.BARGE_MODE_SENSITIVE.equals(s)) return s;
        return Prefs.BARGE_MODE_STEADY;
    }

    private static int oracleShakeForce() {
        if (Prefs.contains(app, Prefs.K_ORACLE_SHAKE_FORCE)) {
            return clamp(Prefs.getI(app, Prefs.K_ORACLE_SHAKE_FORCE,
                    Prefs.DEFAULT_ORACLE_SHAKE_FORCE), 0, 100);
        }
        int old = clamp(Prefs.getI(app, Prefs.K_ORACLE_SHAKE_LEVEL,
                Prefs.DEFAULT_ORACLE_SHAKE_LEVEL), 1, 5);
        return clamp(old * 18 - 2, 0, 100);
    }

    private static String normalizeStreamMode(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.US);
        if ("h264fast".equals(s) || "mjpeg".equals(s)) return s;
        return "h264";
    }

    private static void serveConversations(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("entries", ConversationLog.readTail(app, 250, 160 * 1024));
            o.put("sizeKb", ConversationLog.size(app) / 1024L);
            o.put("maxKb", ConversationLog.maxKb(app));
            o.put("cleanMin", Prefs.getI(app, Prefs.K_CONV_CLEAN_MIN, 60));
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveClearConv(OutputStream out) throws IOException {
        ConversationLog.clear(app);
        byte[] b = "已清空".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveDebugLog(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("enabled", Prefs.getB(app, Prefs.K_DEBUG_MODE, false));
            o.put("entries", DebugLog.readTail(app, 320, 384 * 1024));
            o.put("sizeKb", DebugLog.size(app) / 1024L);
            o.put("maxKb", DebugLog.maxKb(app));
        } catch (Exception e) {
            putErr(o, e.getMessage());
        }
        serveJson(out, o);
    }

    private static void serveClearDebug(OutputStream out) throws IOException {
        DebugLog.clear(app);
        byte[] b = "已清空".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveSystemStatus(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("time", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
            o.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd EEE", java.util.Locale.getDefault()).format(new java.util.Date()));
            int cpuOnline = readOnlineCpuCount();
            int cpuPossible = readPossibleCpuCount();
            o.put("cpu", readCpuPct());
            o.put("appCpu", readAppCpuPct(cpuOnline));
            o.put("appCpuCore", lastAppCoreCpuPct);
            o.put("cpuOnline", cpuOnline);
            o.put("cpuPossible", cpuPossible);
            long[] mem = readMem();
            o.put("memTotalMb", mem[0] / 1024);
            o.put("memUsedMb", mem[1] / 1024);
            o.put("memPct", mem[0] > 0 ? (int) Math.round(mem[1] * 100.0 / mem[0]) : -1);
            GpuInfo gpu = readGpuInfo();
            o.put("gpu", gpu.pct);
            o.put("gpuText", gpu.text);
            o.put("streamActive", ScreenStreamer.isActive() || H264Streamer.isActive() || H264SurfaceStreamer.isActive());
            o.put("streamMode", H264SurfaceStreamer.isActive() ? "h264fast"
                    : (H264Streamer.isActive() ? "h264" : (ScreenStreamer.isActive() ? "mjpeg" : "idle")));
            o.put("mainRenderer", Prefs.mainRenderer(app));
            o.put("mainFpsMode", Prefs.mainFpsMode(app));
            o.put("temps", readTemps());
            LoadInfo load = readLoadInfo();
            o.put("loadAvg1", load.one);
            o.put("loadAvg5", load.five);
            o.put("loadAvg15", load.fifteen);
            o.put("runnable", load.runnable);
            o.put("loadThreads", load.totalTasks);
            o.put("blockedThreads", readBlockedTaskCount());
            o.put("appThreads", readSelfThreadCount());
            o.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
            o.put("wifiScanAgeMs", com.magneo.compass.WifiLocator.scanAgeMs());
            o.put("wifiResultAgeMs", com.magneo.compass.WifiLocator.resultAgeMs());
            o.put("conversationBytes", ConversationLog.size(app));
            o.put("debugBytes", DebugLog.size(app));
            com.magneo.compass.SensorHub h = com.magneo.compass.SensorHub.instance;
            String locSource = Prefs.locSource(app);
            String gpsTxt = "定位关闭";
            if (Prefs.LOC_SOURCE_WIFI_IP.equals(locSource)) {
                gpsTxt = "WiFi/IP 等待粗定位";
                if (h != null && !Double.isNaN(h.netLat)) {
                    String src = h.netSrc == null || h.netSrc.trim().isEmpty() ? "网络" : h.netSrc.trim();
                    gpsTxt = "粗定位(" + src + ") "
                            + String.format(java.util.Locale.US, "%.5f,%.5f ±%.0fm",
                            h.netLat, h.netLon, h.netAcc);
                }
            } else if (Prefs.LOC_SOURCE_GPS_DIAG.equals(locSource)) {
                gpsTxt = h == null ? "GPS诊断待机" : h.gpsStatus;
            }
            if (h != null && h.gpsEnabled) {
                if (!Double.isNaN(h.lat)) gpsTxt = h.gpsStatus + " 已定位";
                else gpsTxt = h.gpsStatus;
            }
            o.put("gps", gpsTxt);
            o.put("battery", h == null ? -1 : h.battery);
            o.put("batteryText", batteryText(h));
            o.put("batteryCharging", h != null && h.batteryCharging);
            o.put("batteryPlugged", h == null ? "" : h.batteryPlugged);
            o.put("hardware", hardwareStatus(h));
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static String batteryText(com.magneo.compass.SensorHub h) {
        if (h == null || h.battery < 0) return "--";
        String s = h.battery + "%";
        if (h.batteryFull) return s + " · 已充满";
        if (h.batteryCharging) {
            String plugged = h.batteryPlugged == null || h.batteryPlugged.trim().isEmpty()
                    ? "" : " " + h.batteryPlugged.trim();
            return s + " · 充电" + plugged;
        }
        return s + " · 未充电";
    }

    private static void serveGpsReset(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            com.magneo.compass.SensorHub h = com.magneo.compass.SensorHub.instance;
            if (h == null) {
                o.put("ok", false);
                o.put("err", "SensorHub 未启动，请先打开主屏或硬件自检页");
            } else if (!h.gpsEnabled) {
                o.put("ok", false);
                o.put("err", "GPS 已关闭，请进入坤·星图或把定位来源切到 GPS诊断");
            } else {
                h.requestGpsColdStart();
                o.put("ok", true);
                o.put("msg", "GPS 冷启动已触发，等待重新搜星");
            }
        } catch (Exception e) {
            try {
                o.put("ok", false);
                o.put("err", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {}
        }
        serveJson(out, o);
    }

    private static JSONObject hardwareStatus(com.magneo.compass.SensorHub h) {
        JSONObject o = new JSONObject();
        try {
            if (h == null) {
                o.put("sensors", "等待 App 前台采样");
                o.put("pose", "--");
                o.put("magnetic", "--");
                o.put("gpsAction", "--");
                o.put("gps", "--");
                o.put("untrusted", "--");
                o.put("magCalibration", "--");
                return o;
            }
            long now = System.currentTimeMillis();
            String sensors = "加 " + sensorState(h.hasAccelSensor, true, h.lastAccelMs, now)
                    + " · 陀 " + sensorState(h.hasGyroSensor, h.gyroSampling, h.lastGyroMs, now)
                    + " · 磁 " + sensorState(h.hasMagSensor, true, h.lastMagMs, now);
            float tilt = Math.max(Math.abs(h.pitch), Math.abs(h.roll));
            String pose = (tilt < 8f ? "设备平稳" : String.format(Locale.US, "倾斜 %.0f°", tilt))
                    + " · 动势 " + motionText(h);
            float mag = (float) Math.sqrt(h.mx * h.mx + h.my * h.my + h.mz * h.mz);
            String magnetic = mag > 1f ? String.format(Locale.US, "%.0fuT · %s", mag, magText(mag)) : "无磁力数据";
            String gpsReq = h.gpsEnabled
                    ? ((h.gpsRequestActive ? "请求中" : "未请求")
                    + " · Provider " + (h.gpsProviderEnabled ? "开" : "关")
                    + gpsRequestAge(h, now)
                    + (h.gpsLastError == null || h.gpsLastError.trim().isEmpty() ? "" : " · " + h.gpsLastError))
                    : "GPS关闭 · " + Prefs.locSourceLabel(app);
            JSONObject driver = gpsDriverStatus();
            String gpsDriver = gpsDriverText(driver, h);
            String gps = h.gpsEnabled
                    ? h.gpsStatus + " · " + h.usedSats + "/" + h.visibleSats
                    + " 星 · SNR " + (h.maxSnr > 0f ? String.format(Locale.US, "%.0f", h.maxSnr) : "--")
                    : (Prefs.locSourceWifiIp(app) && !Double.isNaN(h.netLat)
                    ? ("粗定位 " + h.netSrc + " ±" + String.format(Locale.US, "%.0fm", h.netAcc))
                    : "卫星关闭");
            String untrusted = "光 " + sensorValue(h.hasLightSensor, h.rawDiagnosticSampling, h.lastLightMs, h.light, now, true)
                    + " · 近 " + sensorValue(h.hasProximitySensor, h.rawDiagnosticSampling, h.lastProximityMs, h.proximity, now, true)
                    + " · 气压 " + sensorValue(h.hasPressureSensor, h.rawDiagnosticSampling, h.lastPressureMs, h.pressure, now, false);
            String cal = h.magCalQuality + String.format(Locale.US, " · 偏移 %.0f/%.0f/%.0f",
                    h.magOffsetX, h.magOffsetY, h.magOffsetZ);
            o.put("sensors", sensors);
            o.put("pose", pose);
            o.put("magnetic", magnetic);
            o.put("gpsRequest", gpsReq);
            o.put("gpsDriver", gpsDriver);
            o.put("gpsDriverRaw", driver);
            o.put("gpsAction", h.gpsLastAction == null || h.gpsLastAction.trim().isEmpty()
                    ? "--" : h.gpsLastAction);
            o.put("gps", gps);
            o.put("untrusted", untrusted);
            o.put("magCalibration", cal);
        } catch (Exception ignored) {}
        return o;
    }

    private static String gpsRequestAge(com.magneo.compass.SensorHub h, long now) {
        if (!h.gpsRequestActive || h.gpsRequestStartedMs <= 0) return "";
        long age = Math.max(0L, now - h.gpsRequestStartedMs) / 1000L;
        return " · " + age + "s";
    }

    private static long gpsDriverCacheMs = 0;
    private static JSONObject gpsDriverCache = null;

    private static JSONObject gpsDriverStatus() {
        long now = System.currentTimeMillis();
        JSONObject cached = gpsDriverCache;
        if (cached != null && now - gpsDriverCacheMs < 5000L) return cached;
        JSONObject o = new JSONObject();
        try {
            String out = runRootCapture("echo mnld=$(getprop init.svc.mnld); "
                    + "echo agpsd=$(getprop init.svc.agpsd); "
                    + "echo wifi2agps=$(getprop init.svc.wifi2agps); "
                    + "echo mnlprop=$(getprop persist.radio.mnl.prop); "
                    + "echo pwrctl=$(cat /sys/class/gpsdrv/gps/pwrctl 2>/dev/null); "
                    + "echo state=$(cat /sys/class/gpsdrv/gps/state 2>/dev/null); "
                    + "echo pwrsave=$(cat /sys/class/gpsdrv/gps/pwrsave 2>/dev/null); "
                    + "echo suspend=$(cat /sys/class/gpsdrv/gps/suspend 2>/dev/null)");
            for (String line : out.split("\\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.length() > 0) o.put(key, val);
            }
            String status = runRootCapture("cat /sys/class/gpsdrv/gps/status 2>/dev/null");
            if (status != null && status.length() > 180) status = status.substring(0, 180);
            o.put("status", status == null ? "" : status.trim());
        } catch (Exception e) {
            try { o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
            catch (Exception ignored) {}
        }
        gpsDriverCache = o;
        gpsDriverCacheMs = now;
        return o;
    }

    private static String gpsDriverText(JSONObject d, com.magneo.compass.SensorHub h) {
        if (d == null) return "--";
        String err = d.optString("error", "");
        if (!err.isEmpty()) return "root读取失败 · " + err;
        String pwr = emptyDash(d.optString("pwrctl", ""));
        String state = emptyDash(d.optString("state", ""));
        String save = emptyDash(d.optString("pwrsave", ""));
        String mnld = emptyDash(d.optString("mnld", ""));
        String agpsd = emptyDash(d.optString("agpsd", ""));
        String prop = d.optString("mnlprop", "");
        String prefix = "pwr " + pwr + " · state " + state + " · save " + save
                + " · mnld " + mnld + " · agps " + agpsd;
        if (h != null && h.gpsRequestActive && "0".equals(pwr) && "0".equals(state)) {
            prefix = "底层未开机 · " + prefix;
        }
        if (prop == null || prop.trim().isEmpty()) return prefix + " · mnlprop 空";
        return prefix + " · mnlprop " + prop;
    }

    private static String emptyDash(String s) {
        return s == null || s.trim().isEmpty() ? "--" : s.trim();
    }

    private static String sensorState(boolean exists, boolean active, long lastMs, long now) {
        if (!exists) return "无";
        if (!active) return "未启用";
        if (lastMs <= 0) return "待采样";
        long age = Math.max(0L, now - lastMs);
        return age < 5000L ? "正常" : ("停 " + (age / 1000L) + "s");
    }

    private static String sensorValue(boolean exists, boolean active, long lastMs, float value, long now, boolean zeroUntrusted) {
        if (!exists) return "无硬件";
        if (!active) return "未启用";
        if (lastMs <= 0) return "无事件";
        if (Float.isNaN(value)) return "无数据";
        String out = Math.abs(value) >= 10f
                ? String.format(Locale.US, "%.0f", value)
                : String.format(Locale.US, "%.1f", value);
        long age = Math.max(0L, now - lastMs);
        if (age >= 10000L) out += " 停" + (age / 1000L) + "s";
        if (zeroUntrusted && Math.abs(value) < 0.001f) out += " 不可信";
        return out;
    }

    private static String magText(float mag) {
        if (mag < 25f) return "偏弱";
        if (mag > 85f) return "偏强";
        return "正常";
    }

    private static String motionText(com.magneo.compass.SensorHub h) {
        float acc = (float) Math.sqrt(h.ax * h.ax + h.ay * h.ay + h.az * h.az);
        float shake = Math.abs(acc - 9.80665f);
        float spin = (float) Math.sqrt(h.gx * h.gx + h.gy * h.gy + h.gz * h.gz) * 57.29578f;
        if (spin > 35f || shake > 1.6f) return "明显";
        if (spin > 8f || shake > 0.45f) return "轻微";
        return "静置";
    }

    private static String qParam(String qs, String name) {
        if (qs == null) return null;
        String[] q = qs.split("\\?", 2);
        if (q.length < 2) return null;
        for (String kv : q[1].split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(name)) {
                try { return java.net.URLDecoder.decode(p[1], "UTF-8"); } catch (Exception e) { return p[1]; }
            }
        }
        return null;
    }

    /** 三大金刚键：返回/桌面/最近任务 */
    private static void serveKey(OutputStream out, String qs) throws IOException {
        String code = qParam(qs, "code");
        byte[] b = "ok".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
        com.magneo.compass.MainActivity.wakeScreenForInteractionActive();
        int key = parseBoundedInt(code, -1, 0, 300);
        if (key >= 0) runRoot("input keyevent " + key);
    }

    /** 远程触摸：tap=点击 long=长按 move=拖动（px/py 为上一坐标） */
    private static void serveTouch(OutputStream out, String qs) throws IOException {
        String act = qParam(qs, "act"), x = qParam(qs, "x"), y = qParam(qs, "y");
        byte[] b = "ok".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
        com.magneo.compass.MainActivity.wakeScreenForInteractionActive();
        int ix = parseBoundedInt(x, -1, 0, 800);
        int iy = parseBoundedInt(y, -1, 0, 800);
        if (act == null || ix < 0 || iy < 0) return;
        if (act.equals("tap")) runRoot("input tap " + ix + " " + iy);
        else if (act.equals("long")) runRoot("input swipe " + ix + " " + iy + " " + ix + " " + iy + " 800");
        else if (act.equals("move")) {
            String px = qParam(qs, "px"), py = qParam(qs, "py");
            int ipx = parseBoundedInt(px, ix, 0, 800);
            int ipy = parseBoundedInt(py, iy, 0, 800);
            runRoot("input swipe " + ipx + " " + ipy + " " + ix + " " + iy + " 50");
        }
    }

    private static int parseBoundedInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed < min || parsed > max ? fallback : parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static String runRootCapture(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        try { in.close(); } catch (Exception ignored) {}
        int code = p.waitFor();
        String out = new String(bos.toByteArray(), "UTF-8");
        if (code != 0) throw new IllegalStateException("su exit=" + code + " " + out.trim());
        return out;
    }

    private static long[] prevCpuTicks;
    private static int lastCpuPct = 0;
    private static long prevAppProcTicks = -1;
    private static long prevAppTotalTicks = -1;
    private static int lastAppCpuPct = 0;
    private static int lastAppCoreCpuPct = 0;
    private static final int[] cpuSmooth = new int[4];
    private static final int[] cpuSmoothState = new int[2];
    private static final int[] appCpuSmooth = new int[4];
    private static final int[] appCpuSmoothState = new int[2];
    private static final int[] appCoreCpuSmooth = new int[4];
    private static final int[] appCoreCpuSmoothState = new int[2];

    private static int readCpuPct() {
        try {
            long[] cur = readCpuTicks();
            if (cur == null) return lastCpuPct;
            if (prevCpuTicks == null) { prevCpuTicks = cur; return 0; }
            long busy = 0, total = 0;
            boolean glitch = false;
            for (int i = 0; i < 7; i++) {
                long d = cur[i] - prevCpuTicks[i];
                if (d < 0) glitch = true;   // MTK 热插拔/计数器跳变：本次采样作废
                total += d;
                if (i != 3 && i != 4) busy += d;
            }
            prevCpuTicks = cur;
            if (glitch || total <= 0) return lastCpuPct;
            int raw = (int) Math.min(100, Math.round(busy * 100.0 / total));
            lastCpuPct = smooth(raw, cpuSmooth, cpuSmoothState);
            return lastCpuPct;
        } catch (Exception e) { return lastCpuPct; }
    }

    private static final class LoadInfo {
        final String one, five, fifteen;
        final int runnable, totalTasks;
        LoadInfo(String one, String five, String fifteen, int runnable, int totalTasks) {
            this.one = one;
            this.five = five;
            this.fifteen = fifteen;
            this.runnable = runnable;
            this.totalTasks = totalTasks;
        }
    }

    private static LoadInfo readLoadInfo() {
        String s = readFile("/proc/loadavg", 256);
        if (s == null || s.trim().isEmpty()) return new LoadInfo("--", "--", "--", -1, -1);
        String[] p = s.trim().split("\\s+");
        String tasks = p.length > 3 ? p[3] : "";
        int slash = tasks.indexOf('/');
        int runnable = parseHealthInt(slash > 0 ? tasks.substring(0, slash) : "", -1);
        int total = parseHealthInt(slash > 0 && slash + 1 < tasks.length()
                ? tasks.substring(slash + 1) : "", -1);
        return new LoadInfo(p.length > 0 ? p[0] : "--", p.length > 1 ? p[1] : "--",
                p.length > 2 ? p[2] : "--", runnable, total);
    }

    private static int parseHealthInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static long procHealthAt;
    private static int cachedBlockedTasks = -1;
    private static synchronized int readBlockedTaskCount() {
        long now = System.currentTimeMillis();
        if (now - procHealthAt < 15000L) return cachedBlockedTasks;
        procHealthAt = now;
        int count = 0;
        try {
            File proc = new File("/proc");
            File[] processes = proc.listFiles();
            if (processes != null) {
                for (File process : processes) {
                    if (!isNumeric(process.getName())) continue;
                    File taskDir = new File(process, "task");
                    File[] tasks = taskDir.listFiles();
                    if (tasks == null) continue;
                    for (File task : tasks) {
                        String stat = readFile(new File(task, "stat").getAbsolutePath(), 1024);
                        if (stat != null) {
                            int end = stat.lastIndexOf(')');
                            if (end >= 0 && end + 2 < stat.length() && stat.charAt(end + 2) == 'D') count++;
                        }
                        if (count >= 999) return cachedBlockedTasks = count;
                    }
                }
            }
        } catch (Exception ignored) {}
        return cachedBlockedTasks = count;
    }

    private static int readSelfThreadCount() {
        try {
            File[] files = new File("/proc/self/task").listFiles();
            return files == null ? -1 : files.length;
        } catch (Exception e) { return -1; }
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static long[] readCpuTicks() throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/stat")));
        String line = r.readLine();
        r.close();
        if (line == null || !line.startsWith("cpu ")) return null;
        String[] p = line.trim().split("\\s+");
        long[] v = new long[7];
        for (int i = 1; i < p.length && i <= 7; i++) v[i - 1] = Long.parseLong(p[i]);
        return v;
    }

    private static long readCpuTotalTicks() throws Exception {
        long[] ticks = readCpuTicks();
        if (ticks == null) return -1;
        long total = 0;
        for (long t : ticks) total += t;
        return total;
    }

    private static int readAppCpuPct(int onlineCores) {
        try {
            long proc = readSelfProcTicks();
            long total = readCpuTotalTicks();
            if (proc < 0 || total <= 0) return lastAppCpuPct;
            if (prevAppProcTicks < 0 || prevAppTotalTicks < 0) {
                prevAppProcTicks = proc;
                prevAppTotalTicks = total;
                return 0;
            }
            long dProc = proc - prevAppProcTicks;
            long dTotal = total - prevAppTotalTicks;
            prevAppProcTicks = proc;
            prevAppTotalTicks = total;
            if (dProc < 0 || dTotal <= 0) return lastAppCpuPct;
            int online = Math.max(1, onlineCores);
            int rawTotal = Math.max(0, Math.min(100, (int) Math.round(dProc * 100.0 / dTotal)));
            int rawCore = Math.max(0, Math.min(999, (int) Math.round(dProc * 100.0 * online / dTotal)));
            lastAppCpuPct = smooth(rawTotal, appCpuSmooth, appCpuSmoothState);
            lastAppCoreCpuPct = smooth(rawCore, appCoreCpuSmooth, appCoreCpuSmoothState);
            return lastAppCpuPct;
        } catch (Exception e) {
            return lastAppCpuPct;
        }
    }

    private static int smooth(int raw, int[] samples, int[] state) {
        int pos = state[0];
        int count = state[1];
        samples[pos] = raw;
        pos = (pos + 1) % samples.length;
        if (count < samples.length) count++;
        state[0] = pos;
        state[1] = count;
        int sum = 0;
        for (int i = 0; i < count; i++) sum += samples[i];
        return count <= 0 ? raw : Math.round(sum / (float) count);
    }

    private static int readOnlineCpuCount() {
        String s = readFile("/sys/devices/system/cpu/online", 128);
        int n = countCpuRange(s);
        return n > 0 ? n : 1;
    }

    private static int readPossibleCpuCount() {
        String s = readFile("/sys/devices/system/cpu/possible", 128);
        int n = countCpuRange(s);
        if (n > 0) return n;
        File dir = new File("/sys/devices/system/cpu");
        File[] files = dir.listFiles();
        if (files == null) return Math.max(1, Runtime.getRuntime().availableProcessors());
        int count = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("cpu") && name.length() > 3) {
                boolean digits = true;
                for (int i = 3; i < name.length(); i++) {
                    char ch = name.charAt(i);
                    if (ch < '0' || ch > '9') { digits = false; break; }
                }
                if (digits) count++;
            }
        }
        return count > 0 ? count : Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int countCpuRange(String s) {
        if (s == null) return 0;
        int count = 0;
        String[] parts = s.trim().split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.length() == 0) continue;
            try {
                int dash = part.indexOf('-');
                if (dash >= 0) {
                    int lo = Integer.parseInt(part.substring(0, dash).trim());
                    int hi = Integer.parseInt(part.substring(dash + 1).trim());
                    if (hi >= lo) count += hi - lo + 1;
                } else {
                    Integer.parseInt(part);
                    count++;
                }
            } catch (Exception ignored) {}
        }
        return count;
    }

    private static long readSelfProcTicks() throws Exception {
        String s = readFile("/proc/self/stat", 4096);
        if (s == null) return -1;
        int end = s.lastIndexOf(')');
        if (end < 0 || end + 2 >= s.length()) return -1;
        String[] p = s.substring(end + 2).trim().split("\\s+");
        if (p.length < 13) return -1;
        long utime = Long.parseLong(p[11]);
        long stime = Long.parseLong(p[12]);
        return utime + stime;
    }

    private static long[] readMem() {
        long total = 0, free = 0, cached = 0, buffers = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/meminfo")));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal")) total = kbOf(line);
                else if (line.startsWith("MemFree")) free = kbOf(line);
                else if (line.startsWith("Cached")) cached = kbOf(line);
                else if (line.startsWith("Buffers")) buffers = kbOf(line);
            }
            r.close();
        } catch (Exception ignored) {}
        long used = Math.max(0, total - free - cached - buffers);
        return new long[]{total, used};
    }

    private static long kbOf(String line) {
        String[] p = line.trim().split("\\s+");
        try { return Long.parseLong(p[1]); } catch (Exception e) { return 0; }
    }

    private static GpuInfo readGpuInfo() {
        try {
            String s = readFile("/proc/mali/utilization");
            if (s == null || s.trim().isEmpty()) return new GpuInfo(-1, "--");
            if (s.contains("clock off")) return new GpuInfo(-1, "clock off");
            // 实际格式: "GPU/GP/PP: 60/17/59, Frequency: 500500"（GPU 为第一个数字）
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("GPU/GP/PP:\\s*(\\d+)").matcher(s);
            if (m.find()) {
                int pct = clampPct(Integer.parseInt(m.group(1)));
                return new GpuInfo(pct, pct + "%");
            }
            m = java.util.regex.Pattern.compile("\\d+").matcher(s);
            if (m.find()) {
                int pct = clampPct(Integer.parseInt(m.group()));
                return new GpuInfo(pct, pct + "%");
            }
            return new GpuInfo(-1, "--");
        } catch (Exception e) { return new GpuInfo(-1, "--"); }
    }

    private static final class GpuInfo {
        final int pct;
        final String text;
        GpuInfo(int pct, String text) {
            this.pct = pct;
            this.text = text;
        }
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static org.json.JSONArray readTemps() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (int i = 0; i < 12; i++) {
                String type = readFile("/sys/class/thermal/thermal_zone" + i + "/type");
                String t = readFile("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (type == null || t == null) continue;
                int milli;
                try { milli = Integer.parseInt(t.trim()); } catch (Exception e) { continue; }
                if (milli < -50000 || milli > 200000) continue;
                arr.put(new JSONObject().put("name", tempName(type.trim())).put("c", milli / 1000.0));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private static String tempName(String type) {
        if (type.contains("cpu")) return "CPU";
        if (type.contains("battery")) return "电池";
        if (type.contains("pmi")) return "PMIC";
        if (type.contains("wmt")) return "WiFi";
        if (type.contains("AP")) return "AP";
        if (type.matches("mtkts[0-9]+")) return "热区" + type.substring(5);
        return type;
    }

    private static String readFile(String path) {
        return readFile(path, 128);
    }

    private static String readFile(String path, int maxBytes) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            byte[] b = new byte[Math.max(128, maxBytes)];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (Exception e) { return null; }
    }

    private static void serveStream(Socket s) {
        ScreenStreamer.serve(s, app);
    }

    private static void serveH264(Socket s) {
        H264Streamer.serve(s, app);
    }

    private static void serveH264Fast(Socket s) {
        H264SurfaceStreamer.serve(s, app);
    }

    private static void serveStreamState(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("state", ScreenStreamer.state());
            o.put("fps", Prefs.getI(app, Prefs.K_STREAM_FPS, 1));
            o.put("quality", Prefs.getI(app, Prefs.K_STREAM_QUALITY, 55));
            o.put("scale", Prefs.getI(app, Prefs.K_STREAM_SCALE, 2));
            o.put("mode", H264SurfaceStreamer.isActive() ? "h264fast"
                    : (H264Streamer.isActive() ? "h264" : (ScreenStreamer.isActive() ? "mjpeg" : "idle")));
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void writeHead(OutputStream out, String type, long len) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + len
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
    }

    private static void serveBootAssetDownload(OutputStream out, String target) throws IOException {
        String kind = qParam(target, "kind");
        File file = BootAssetsManager.downloadFile(app, kind);
        if (file == null) {
            serve404(out);
            return;
        }
        String type = "manifest".equals(kind) || "sums".equals(kind)
                ? "text/plain; charset=utf-8" : "application/octet-stream";
        String name = file.getName().replace("\"", "");
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: "
                + file.length() + "\r\nContent-Disposition: attachment; filename=\"" + name
                + "\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .getBytes("ISO-8859-1"));
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
        }
    }

    private static void serve404(OutputStream out) throws IOException {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
    }
}
