package com.magneo.compass.netfs;

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
    private final String base;
    private final String user, pass;

    public WebDavFs(FsManager.Conn c) {
        int port = FsManager.defaultPort(c);
        String scheme = port == 443 || port == 0 ? "https" : "http";
        base = scheme + "://" + c.host + (port == 443 || port == 0 ? "" : ":" + port) + (c.root == null ? "" : c.root);
        user = c.user;
        pass = c.pass;
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    private String full(String path) {
        String p = path == null || path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        String url = base + p;
        // 尽量安全编码：保留 / 与已有转义
        return url.replace(" ", "%20");
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
        Request req = auth(new Request.Builder()
                .url(full(path) + (path == null || path.isEmpty() ? "/" : ""))
                .method("PROPFIND", RequestBody.create(null, new byte[0]))
                .header("Depth", "1"))
                .build();
        try (Response resp = client.newCall(req).execute()) {
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
        boolean inResponse = false, inProp = false, isColl = false;
        String href = null;
        int evt;
        while ((evt = p.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = p.getName() == null ? "" : p.getName().toLowerCase();
            if (evt == XmlPullParser.START_TAG) {
                if (tag.equals("response")) { inResponse = true; cur = new Entry(); href = null; isColl = false; }
                else if (tag.equals("prop")) inProp = true;
                else if (tag.equals("collection")) isColl = true;
            } else if (evt == XmlPullParser.TEXT) {
                if (inResponse && cur != null && tag.isEmpty()) { /* skip */ }
            } else if (evt == XmlPullParser.END_TAG) {
                if (tag.equals("href") && inResponse) {
                    String h = p.getText();
                    if (h == null) h = "";
                    href = h;
                } else if (tag.equals("getcontentlength") && inResponse && cur != null) {
                    String t = p.getText();
                    if (t != null) try { cur.size = Long.parseLong(t.trim()); } catch (Exception ignored) {}
                } else if (tag.equals("getlastmodified") && inResponse && cur != null) {
                    // 忽略时间解析，保持 0
                } else if (tag.equals("prop")) inProp = false;
                else if (tag.equals("response") && inResponse) {
                    if (cur != null && href != null) {
                        String name = href;
                        int slash = name.endsWith("/") ? name.lastIndexOf('/', name.length() - 2) : name.lastIndexOf('/');
                        if (slash >= 0) name = name.substring(slash + 1);
                        name = name.replaceAll("/+$", "");
                        if (!name.isEmpty()) {
                            cur.name = name;
                            cur.dir = isColl || href.endsWith("/");
                            out.add(cur);
                        }
                    }
                    inResponse = false;
                }
            }
        }
        return out;
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
