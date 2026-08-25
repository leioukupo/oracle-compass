package com.magneo.compass.mcp;

import android.content.Context;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class McpServerConfig {
    public final String id;
    public final String name;
    public final String url;
    public final boolean enabled;
    public final String bearerToken;
    public final int timeoutMs;

    public McpServerConfig(String id, String name, String url, boolean enabled,
                           String bearerToken, int timeoutMs) {
        this.id = sanitizeId(id);
        this.name = name == null || name.trim().isEmpty() ? this.id : name.trim();
        this.url = normalizeUrl(url);
        this.enabled = enabled;
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.timeoutMs = Math.max(1000, Math.min(60000,
                timeoutMs <= 0 ? Prefs.DEFAULT_MCP_TOOL_TIMEOUT_MS : timeoutMs));
    }

    public static List<McpServerConfig> load(Context ctx) {
        ArrayList<McpServerConfig> out = new ArrayList<>();
        String raw = Prefs.get(ctx, Prefs.K_MCP_SERVERS, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null || raw.trim().isEmpty() ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                McpServerConfig c = fromJson(o, "mcp" + (i + 1));
                if (!c.id.isEmpty() && !c.url.isEmpty()) out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static McpServerConfig fromJson(JSONObject o, String fallbackId) {
        String id = o.optString("id", fallbackId);
        String name = o.optString("name", id);
        String url = o.optString("url", "");
        boolean enabled = o.optBoolean("enabled", true);
        String token = o.optString("bearerToken", o.optString("token", ""));
        int timeout = o.optInt("timeoutMs", Prefs.DEFAULT_MCP_TOOL_TIMEOUT_MS);
        return new McpServerConfig(id, name, url, enabled, token, timeout);
    }

    public JSONObject toJson(boolean includeSecret) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("url", url);
            o.put("enabled", enabled);
            o.put("timeoutMs", timeoutMs);
            if (includeSecret) o.put("bearerToken", bearerToken);
            else o.put("bearerTokenSet", !bearerToken.isEmpty());
        } catch (Exception ignored) {}
        return o;
    }

    public static String sanitizeId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9_-]+", "_");
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = "mcp";
        if (s.length() > 24) s = s.substring(0, 24);
        return s;
    }

    public static String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/") && s.length() > "https://x".length()) s = s.substring(0, s.length() - 1);
        int scheme = s.indexOf("://");
        int path = scheme >= 0 ? s.indexOf('/', scheme + 3) : -1;
        if (path < 0) return s + "/mcp";
        return s;
    }
}
