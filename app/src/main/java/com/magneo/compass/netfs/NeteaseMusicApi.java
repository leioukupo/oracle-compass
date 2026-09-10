package com.magneo.compass.netfs;

import android.content.Context;
import android.util.Base64;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NeteaseCloudMusicApi 兼容客户端。
 *
 * 这里只依赖少量稳定的 JSON 接口，服务地址由设备配置提供，不绑定任何公共实例。
 */
public final class NeteaseMusicApi {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 5.1; OracleCompass) AppleWebKit/537.36";

    private final Context context;
    private final OkHttpClient client;
    private final String baseUrl;
    private final String cookie;

    public NeteaseMusicApi(Context context) {
        this(context, Prefs.neteaseApiUrl(context), Prefs.neteaseCookie(context));
    }

    public NeteaseMusicApi(Context context, String baseUrl, String cookie) {
        this.context = context.getApplicationContext();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.cookie = cookie == null ? "" : cookie.trim();
        this.client = Tls.builder(this.context)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .writeTimeout(18, TimeUnit.SECONDS)
                .build();
    }

    public static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/") && !value.isEmpty()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String cookie() {
        return cookie;
    }

    public List<MusicTrack> searchSongs(String keywords, int limit) throws IOException {
        String q = keywords == null ? "" : keywords.trim();
        if (q.isEmpty()) throw new IOException("请输入搜索内容");
        Map<String, String> params = new HashMap<>();
        params.put("keywords", q);
        params.put("limit", String.valueOf(clamp(limit, 1, 100)));
        params.put("type", "1");
        JSONObject root = request("/cloudsearch", params);
        JSONObject result = root.optJSONObject("result");
        return parseSongs(result == null ? null : result.optJSONArray("songs"));
    }

    public List<Playlist> searchPlaylists(String keywords, int limit) throws IOException {
        String q = keywords == null ? "" : keywords.trim();
        if (q.isEmpty()) throw new IOException("请输入搜索内容");
        Map<String, String> params = new HashMap<>();
        params.put("keywords", q);
        params.put("limit", String.valueOf(clamp(limit, 1, 100)));
        params.put("type", "1000");
        JSONObject root = request("/cloudsearch", params);
        JSONObject result = root.optJSONObject("result");
        return parsePlaylists(result == null ? null : result.optJSONArray("playlists"));
    }

    public List<Playlist> personalizedPlaylists(int limit) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(clamp(limit, 1, 100)));
        JSONObject root = request("/personalized", params);
        return parsePlaylists(root.optJSONArray("result"));
    }

    public List<Playlist> userPlaylists() throws IOException {
        String uid = Prefs.get(context, Prefs.K_NETEASE_UID, "").trim();
        if (uid.isEmpty()) throw new IOException("登录后才能查看我的歌单");
        Map<String, String> params = new HashMap<>();
        params.put("uid", uid);
        params.put("limit", "50");
        JSONObject root = request("/user/playlist", params);
        return parsePlaylists(root.optJSONArray("playlist"));
    }

    public List<MusicTrack> recommendSongs(int limit) throws IOException {
        int max = clamp(limit, 1, 100);
        JSONObject root = request("/recommend/songs", new HashMap<String, String>());
        JSONObject data = root.optJSONObject("data");
        JSONArray songs = data == null ? null : data.optJSONArray("dailySongs");
        if (songs == null) songs = root.optJSONArray("result");
        List<MusicTrack> out = parseSongs(songs);
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
    }

    public List<MusicTrack> playlistTracks(long playlistId) throws IOException {
        if (playlistId <= 0L) throw new IOException("歌单 ID 无效");
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(playlistId));
        JSONObject root = request("/playlist/detail", params);
        JSONObject playlist = root.optJSONObject("playlist");
        if (playlist == null) playlist = root.optJSONObject("result");
        if (playlist == null) throw new IOException("歌单数据为空");

        List<MusicTrack> tracks = parseSongs(playlist.optJSONArray("tracks"));
        if (!tracks.isEmpty()) return tracks;

        JSONArray ids = playlist.optJSONArray("trackIds");
        if (ids == null || ids.length() == 0) return tracks;
        ArrayList<MusicTrack> out = new ArrayList<>();
        for (int start = 0; start < ids.length(); start += 200) {
            JSONArray batch = new JSONArray();
            for (int i = start; i < Math.min(ids.length(), start + 200); i++) {
                batch.put(ids.optJSONObject(i) == null ? ids.optLong(i) : ids.optJSONObject(i).optLong("id"));
            }
            Map<String, String> detailParams = new HashMap<>();
            detailParams.put("ids", batch.toString());
            JSONObject detail = request("/song/detail", detailParams);
            out.addAll(parseSongs(detail.optJSONArray("songs")));
        }
        return out;
    }

    public String resolveSongUrl(long songId) throws IOException {
        if (songId <= 0L) return "";
        String level = Prefs.neteaseQuality(context);
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(songId));
        params.put("level", level);
        String url = songUrl("/song/url/v1", params, songId);
        if (!url.isEmpty()) return url;

        params.remove("level");
        return songUrl("/song/url", params, songId);
    }

    private String songUrl(String path, Map<String, String> params, long songId) throws IOException {
        try {
            JSONObject root = request(path, params);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) return "";
            JSONObject item = data.optJSONObject(0);
            if (item == null || item.optLong("id", songId) != songId) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject candidate = data.optJSONObject(i);
                    if (candidate != null && candidate.optLong("id", 0L) == songId) {
                        item = candidate;
                        break;
                    }
                }
            }
            return item == null ? "" : item.optString("url", "").trim();
        } catch (IOException e) {
            return "";
        }
    }

    public QrCode createQrCode() throws IOException {
        JSONObject keyRoot = request("/login/qr/key", singleton("timestamp", now()));
        JSONObject keyData = keyRoot.optJSONObject("data");
        String key = keyData == null ? "" : keyData.optString("unikey", "");
        if (key.isEmpty()) throw new IOException("二维码 KEY 获取失败");

        Map<String, String> params = new HashMap<>();
        params.put("key", key);
        params.put("qrimg", "true");
        params.put("timestamp", now());
        JSONObject qrRoot = request("/login/qr/create", params);
        JSONObject qrData = qrRoot.optJSONObject("data");
        String image = qrData == null ? "" : qrData.optString("qrimg", "");
        String url = qrData == null ? "" : qrData.optString("qrurl", "");
        if (image.isEmpty()) throw new IOException("二维码图片获取失败");
        return new QrCode(key, image, url);
    }

    public QrStatus checkQrCode(String key) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("key", key == null ? "" : key);
        params.put("timestamp", now());
        ApiResponse response = requestResponse("/login/qr/check", params);
        JSONObject root = response.body;
        JSONObject data = root.optJSONObject("data");
        int code = root.optInt("code", data == null ? 0 : data.optInt("code", 0));
        String message = root.optString("message", "");
        if (data != null && message.isEmpty()) message = data.optString("message", "");
        String loginCookie = data == null ? "" : data.optString("cookie", "");
        if (loginCookie.isEmpty()) loginCookie = root.optString("cookie", "");
        if (loginCookie.isEmpty()) loginCookie = cookieFromHeaders(response.headers);
        return new QrStatus(code, message, loginCookie);
    }

    public LoginStatus loginStatus() throws IOException {
        return loginStatus(cookie);
    }

    public LoginStatus loginStatus(String cookieOverride) throws IOException {
        NeteaseMusicApi api = cookieOverride == null || cookieOverride.trim().isEmpty()
                ? this : new NeteaseMusicApi(context, baseUrl, cookieOverride);
        ApiResponse response = api.requestResponse("/login/status",
                singleton("timestamp", now()));
        JSONObject root = response.body;
        JSONObject data = root.optJSONObject("data");
        JSONObject account = data == null ? null : data.optJSONObject("account");
        JSONObject profile = data == null ? null : data.optJSONObject("profile");
        long uid = account == null ? 0L : account.optLong("id", 0L);
        if (uid <= 0L && profile != null) uid = profile.optLong("userId", 0L);
        String nickname = profile == null ? "" : profile.optString("nickname", "");
        boolean loggedIn = uid > 0L || (data != null && data.optBoolean("loginType", false));
        return new LoginStatus(loggedIn, uid, nickname, root.optString("message", ""));
    }

    private JSONObject request(String path, Map<String, String> params) throws IOException {
        return requestResponse(path, params).body;
    }

    private ApiResponse requestResponse(String path, Map<String, String> params) throws IOException {
        if (!isConfigured()) throw new IOException("未配置网易云 API 地址");
        HttpUrl base = HttpUrl.parse(baseUrl + (path.startsWith("/") ? path : "/" + path));
        if (base == null) throw new IOException("网易云 API 地址无效");
        HttpUrl.Builder ub = base.newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) ub.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        if (!cookie.isEmpty()) ub.addQueryParameter("cookie", cookie);
        Request.Builder rb = new Request.Builder()
                .url(ub.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");
        if (!cookie.isEmpty()) rb.header("Cookie", cookie);
        Response response = null;
        try {
            response = client.newCall(rb.get().build()).execute();
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("网易云 API HTTP " + response.code());
            }
            Object parsed = new org.json.JSONTokener(body).nextValue();
            if (!(parsed instanceof JSONObject)) throw new IOException("网易云 API 返回不是 JSON");
            JSONObject root = (JSONObject) parsed;
            int code = root.optInt("code", 200);
            if (code >= 400 && code != 801 && code != 802 && code != 803) {
                throw new IOException(root.optString("message",
                        root.optString("msg", "网易云 API 返回错误 " + code)));
            }
            return new ApiResponse(root, response.headers());
        } catch (org.json.JSONException e) {
            throw new IOException("网易云 API JSON 解析失败", e);
        } finally {
            if (response != null) response.close();
        }
    }

    private List<MusicTrack> parseSongs(JSONArray songs) {
        ArrayList<MusicTrack> out = new ArrayList<>();
        if (songs == null) return out;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            long id = song.optLong("id", 0L);
            if (id <= 0L) continue;
            JSONObject album = song.optJSONObject("al");
            if (album == null) album = song.optJSONObject("album");
            String title = song.optString("name", "");
            String artist = artists(song.optJSONArray("ar"));
            if (artist.isEmpty() && album != null) artist = album.optString("artist", "");
            String albumName = album == null ? "" : album.optString("name", "");
            String cover = album == null ? "" : album.optString("picUrl", "");
            if (cover.isEmpty()) cover = song.optString("picUrl", "");
            long duration = song.optLong("dt", song.optLong("duration", 0L));
            out.add(new MusicTrack(MusicTrack.SOURCE_NETEASE, "", id, title, artist,
                    albumName, cover, duration));
        }
        return out;
    }

    private List<Playlist> parsePlaylists(JSONArray values) {
        ArrayList<Playlist> out = new ArrayList<>();
        if (values == null) return out;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null) continue;
            long id = item.optLong("id", 0L);
            if (id <= 0L) continue;
            String name = item.optString("name", "");
            String cover = item.optString("picUrl", item.optString("coverImgUrl", ""));
            int count = item.optInt("trackCount", item.optInt("size", 0));
            out.add(new Playlist(id, name, cover, count));
        }
        return out;
    }

    private static String artists(JSONArray values) {
        if (values == null) return "";
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            String name = item == null ? "" : item.optString("name", "");
            if (!name.isEmpty()) names.add(name);
        }
        return join(names, " / ");
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static Map<String, String> singleton(String key, String value) {
        Map<String, String> out = new HashMap<>();
        out.put(key, value);
        return out;
    }

    private static String cookieFromHeaders(Headers headers) {
        if (headers == null) return "";
        StringBuilder out = new StringBuilder();
        for (String value : headers.values("Set-Cookie")) {
            int semi = value.indexOf(';');
            String part = semi >= 0 ? value.substring(0, semi) : value;
            if (part.trim().isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            out.append(part.trim());
        }
        return out.toString();
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    public static final class Playlist {
        public final long id;
        public final String name;
        public final String coverUrl;
        public final int trackCount;

        Playlist(long id, String name, String coverUrl, int trackCount) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
            this.trackCount = Math.max(0, trackCount);
        }
    }

    public static final class QrCode {
        public final String key;
        public final String imageData;
        public final String url;

        QrCode(String key, String imageData, String url) {
            this.key = key;
            this.imageData = imageData == null ? "" : imageData;
            this.url = url == null ? "" : url;
        }
    }

    public static final class QrStatus {
        public final int code;
        public final String message;
        public final String cookie;

        QrStatus(int code, String message, String cookie) {
            this.code = code;
            this.message = message == null ? "" : message;
            this.cookie = cookie == null ? "" : cookie;
        }
    }

    public static final class LoginStatus {
        public final boolean loggedIn;
        public final long uid;
        public final String nickname;
        public final String message;

        LoginStatus(boolean loggedIn, long uid, String nickname, String message) {
            this.loggedIn = loggedIn;
            this.uid = uid;
            this.nickname = nickname == null ? "" : nickname;
            this.message = message == null ? "" : message;
        }
    }

    private static final class ApiResponse {
        final JSONObject body;
        final Headers headers;

        ApiResponse(JSONObject body, Headers headers) {
            this.body = body;
            this.headers = headers;
        }
    }
}
