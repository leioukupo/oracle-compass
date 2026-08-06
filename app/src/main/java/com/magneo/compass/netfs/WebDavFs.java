package com.magneo.compass.netfs;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** WebDAV 连接器（OkHttp：PROPFIND/GET/PUT/MKCOL/MOVE/DELETE）。 */
public class WebDavFs implements NetFs {
    private final OkHttpClient client;
    private final String baseHost;
    private final String rootRaw;
    private final String user, pass;

    public WebDavFs(Context ctx, FsManager.Conn cn) {
        int port = FsManager.defaultPort(cn);
        String scheme = port == 443 || port == 0 ? "https" : "http";
        baseHost = scheme + "://" + cn.host + (port == 443 || port == 0 ? "" : ":" + port);
        rootRaw = cn.root == null ? "" : cn.root;
        user = cn.user;
        pass = cn.pass;
        client = Tls.builder(ctx)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    private String full(String path) {
        String p = path == null || path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        if (rootRaw.endsWith("/") && p.startsWith("/")) p = p.substring(1);
        return baseHost + encodeUrlPath(rootRaw + p);
    }

    private Request.Builder auth(Request.Builder b) {
        if (user != null && !user.isEmpty()) {
            String cred = android.util.Base64.encodeToString((user + ":" + pass).getBytes(),
                    android.util.Base64.NO_WRAP);
            b.header("Authorization", "Basic " + cred);
        }
        return b;
    }

    @Override public List<Entry> list(String path) throws Exception {
        String url = full(path);
        if (!url.endsWith("/")) url += "/";   // 目录 PROPFIND 标准带尾斜杠
        List<Entry> r = propfindList(url);
        if (r == null) {
            // 部分服务器对带尾斜杠的 PROPFIND 反而 404，去掉尾斜杠再试一次
            String u2 = url.substring(0, url.length() - 1);
            r = propfindList(u2);
            if (r == null) throw new Exception("WebDAV 列目录失败 HTTP 404");
        }
        return r;
    }

    /** PROPFIND：成功返回条目；HTTP 404 返回 null；其他错误抛异常。 */
    private List<Entry> propfindList(String url) throws Exception {
        Request req = auth(new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .header("Depth", "1"))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 404) return null;
            if (!resp.isSuccessful() && resp.code() != 207) throw new Exception("WebDAV 列目录失败 HTTP " + resp.code());
            byte[] body = resp.body().bytes();
            return parsePropfind(body);
        }
    }

    private List<Entry> parsePropfind(byte[] xml) throws Exception {
        List<Entry> out = new ArrayList<>();
        XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        Entry cur = null;
        boolean inResponse = false, isColl = false;
        String href = null, buf = null;
        int evt;
        while ((evt = p.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = localName(p);
            if (evt == XmlPullParser.START_TAG) {
                if (tag.equals("response")) { inResponse = true; cur = new Entry(); href = null; isColl = false; }
                else if (tag.equals("collection")) isColl = true;
                buf = null;
            } else if (evt == XmlPullParser.TEXT) {
                buf = p.getText();
            } else if (evt == XmlPullParser.END_TAG) {
                if (tag.equals("href") && inResponse) href = buf == null ? "" : buf.trim();
                else if (tag.equals("getcontentlength") && inResponse && cur != null) {
                    if (buf != null) try { cur.size = Long.parseLong(buf.trim()); } catch (Exception ignored) {}
                } else if (tag.equals("response") && inResponse) {
                    if (cur != null && href != null) {
                        String name = lastSegmentName(href);
                        if (!name.isEmpty()) {
                            cur.name = name;
                            cur.dir = isColl || href.endsWith("/");
                            out.add(cur);
                        }
                    }
                    inResponse = false;
                }
                buf = null;
            }
        }
        return out;
    }

    /** kxml2 的 getName() 带命名空间前缀（如 D:response），剥离前缀再匹配。 */
    private static String localName(XmlPullParser p) {
        String raw = p.getName();
        if (raw == null) return "";
        String n = raw.toLowerCase();
        int c = n.indexOf(':');
        if (c >= 0) n = n.substring(c + 1);
        return n;
    }

    /** 从 href 取最后一段文件名并解码（%XX → 原字符，+ 保持字面量）。 */
    private static String lastSegmentName(String href) {
        String path = href;
        try {
            java.net.URI u = new java.net.URI(href);
            if (u.getPath() != null) path = u.getPath();
        } catch (Exception e) {
            path = decodePercent(href);
        }
        String name = path;
        int slash = name.endsWith("/") ? name.lastIndexOf('/', name.length() - 2) : name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("/+$", "");
        return name;
    }

    /** 只解码 %XX、保留 + 的宽松解码（URI 构造失败时兜底）。 */
    private static String decodePercent(String s) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        bos.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                if (c < 0x80) bos.write(c);
                else {
                    byte[] b = String.valueOf(c).getBytes("UTF-8");
                    bos.write(b, 0, b.length);
                }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) { return s; }
    }

    /** 路径统一编码：保留 / 与已有 %XX，编码中文/空格/特殊字符，+ 保持字面量。 */
    private static String encodeUrlPath(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                sb.append(c).append(s.charAt(i + 1)).append(s.charAt(i + 2));
                i += 2;
            } else if (c <= 0x7F && (Character.isLetterOrDigit(c) || "-_.~/@+:".indexOf(c) >= 0)) {
                sb.append(c);
            } else if (c <= 0x7F) {
                appendHex(sb, (byte) c);
            } else {
                try { appendHex(sb, String.valueOf(c).getBytes("UTF-8")); } catch (Exception ignored) {}
            }
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static void appendHex(StringBuilder sb, byte... b) {
        for (byte x : b) sb.append('%').append(String.format("%02X", x & 0xFF));
    }

    @Override public InputStream open(String path) throws Exception {
        Request req = auth(new Request.Builder().url(full(path)).get()).build();
        Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful()) throw new Exception("WebDAV 打开失败 HTTP " + resp.code());
        return resp.body().byteStream();
    }

    @Override public void upload(String path, InputStream in, long len) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        Request req = auth(new Request.Builder().url(full(path))
                .put(RequestBody.create(MediaType.parse("application/octet-stream"), bos.toByteArray()))).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("WebDAV 上传失败 HTTP " + resp.code());
        }
    }

    @Override public void mkdir(String path) throws Exception {
        Request req = auth(new Request.Builder().url(full(path)).method("MKCOL", RequestBody.create(null, new byte[0]))).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() && resp.code() != 405) throw new Exception("MKCOL 失败 HTTP " + resp.code());
        }
    }

    @Override public void rename(String from, String to) throws Exception {
        Request req = auth(new Request.Builder().url(full(from))
                .method("MOVE", RequestBody.create(null, new byte[0]))
                .header("Destination", full(to))).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("重命名失败 HTTP " + resp.code());
        }
    }

    @Override public void delete(String path) throws Exception {
        Request req = auth(new Request.Builder().url(full(path)).delete()).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("删除失败 HTTP " + resp.code());
        }
    }

    @Override public void close() {}
}
