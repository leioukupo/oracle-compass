package com.magneo.compass.mcp;

import android.content.Context;

import com.magneo.compass.DebugLog;
import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class McpClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ConcurrentHashMap<String, String> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CacheEntry> TOOL_CACHE = new ConcurrentHashMap<>();
    private static volatile int rpcId = 100;

    private final Context ctx;
    private final OkHttpClient baseClient;

    public McpClient(Context ctx) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        baseClient = com.magneo.compass.netfs.Tls.builder(ctx)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public List<McpTool> listTools(McpServerConfig cfg, boolean forceRefresh) throws IOException {
        if (cfg == null || !cfg.enabled || cfg.url.isEmpty()) return new ArrayList<>();
        String key = cacheKey(cfg);
        long now = System.currentTimeMillis();
        CacheEntry cached = TOOL_CACHE.get(key);
        if (!forceRefresh && cached != null && now - cached.at < 5 * 60_000L) {
            DebugLog.append(ctx, "mcp.tools.cache", cfg.name + " tools=" + cached.tools.size());
            return cached.tools;
        }
        DebugLog.append(ctx, "mcp.tools.list", cfg.name + " url=" + cfg.url
                + " timeoutMs=" + cfg.timeoutMs);
        initialize(cfg);
        JSONObject root = postRpc(cfg, "tools/list", new JSONObject(), true);
        JSONObject result = root.optJSONObject("result");
        if (result == null && root.has("tools")) result = root;
        JSONArray arr = result == null ? null : result.optJSONArray("tools");
        ArrayList<McpTool> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name", "").trim();
                if (name.isEmpty()) continue;
                JSONObject schema = t.optJSONObject("inputSchema");
                if (schema == null) schema = t.optJSONObject("input_schema");
                out.add(new McpTool(cfg, name, t.optString("description", ""), schema));
            }
        }
        TOOL_CACHE.put(key, new CacheEntry(now, out));
        DebugLog.append(ctx, "mcp.tools.result", cfg.name + " tools=" + out.size()
                + " " + toolNames(out));
        return out;
    }

    public ToolResult callTool(McpTool tool, JSONObject arguments) throws IOException {
        if (tool == null) throw new IOException("工具不存在");
        initialize(tool.server);
        JSONObject params = new JSONObject();
        try {
            params.put("name", tool.name);
            params.put("arguments", arguments == null ? new JSONObject() : arguments);
        } catch (Exception ignored) {}
        long start = System.currentTimeMillis();
        DebugLog.append(ctx, "mcp.call.request", tool.fullName + " args="
                + (arguments == null ? "{}" : arguments.toString()));
        JSONObject root = postRpc(tool.server, "tools/call", params, true);
        JSONObject result = root.optJSONObject("result");
        if (result == null) result = root;
        boolean isError = result.optBoolean("isError", false);
        String text = contentToText(result);
        DebugLog.append(ctx, isError ? "mcp.call.error" : "mcp.call.result",
                tool.fullName + " ms=" + (System.currentTimeMillis() - start)
                        + " result=" + text);
        return new ToolResult(tool.fullName, text, isError,
                System.currentTimeMillis() - start);
    }

    public void refresh(McpServerConfig cfg) throws IOException {
        TOOL_CACHE.remove(cacheKey(cfg));
        listTools(cfg, true);
    }

    private void initialize(McpServerConfig cfg) throws IOException {
        String key = cacheKey(cfg);
        if (SESSIONS.containsKey(key)) return;
        JSONObject params = new JSONObject();
        try {
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject()
                    .put("name", "oracle-compass")
                    .put("version", "1.0"));
        } catch (Exception ignored) {}
        postRpc(cfg, "initialize", params, true);
        if (!SESSIONS.containsKey(key)) SESSIONS.put(key, "");
        try {
            postRpc(cfg, "notifications/initialized", new JSONObject(), false);
        } catch (Exception ignored) {}
    }

    private JSONObject postRpc(McpServerConfig cfg, String method, JSONObject params,
                               boolean expectsResult) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            if (params != null) body.put("params", params);
            if (expectsResult) body.put("id", nextId());
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        OkHttpClient client = baseClient.newBuilder()
                .connectTimeout(Math.max(1000, cfg.timeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1000, cfg.timeoutMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1000, cfg.timeoutMs), TimeUnit.MILLISECONDS)
                .build();
        Request.Builder rb = new Request.Builder()
                .url(cfg.url)
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .post(RequestBody.create(JSON, body.toString()));
        if (!cfg.bearerToken.isEmpty()) rb.addHeader("Authorization", "Bearer " + cfg.bearerToken);
        String session = SESSIONS.get(cacheKey(cfg));
        if (session != null && !session.isEmpty()) rb.addHeader("Mcp-Session-Id", session);

        Response resp = client.newCall(rb.build()).execute();
        try {
            String sid = resp.header("Mcp-Session-Id");
            if (sid != null && !sid.trim().isEmpty()) SESSIONS.put(cacheKey(cfg), sid.trim());
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                DebugLog.append(ctx, "mcp.http_error", method + " " + cfg.url
                        + " HTTP " + resp.code() + " " + text);
                throw new IOException("HTTP " + resp.code() + " " + clip(text, 400));
            }
            if (!expectsResult) return new JSONObject();
            JSONObject root = parseBody(text, resp.header("Content-Type", ""));
            JSONObject err = root.optJSONObject("error");
            if (err != null) {
                DebugLog.append(ctx, "mcp.rpc_error", method + " " + err.toString());
                throw new IOException(err.optString("message", err.toString()));
            }
            return root;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage());
        } finally {
            if (resp.body() != null) resp.body().close();
        }
    }

    private JSONObject parseBody(String body, String contentType) throws Exception {
        String s = body == null ? "" : body.trim();
        if (s.isEmpty()) return new JSONObject();
        boolean sse = (contentType != null && contentType.toLowerCase(Locale.US).contains("text/event-stream"))
                || s.startsWith("event:") || s.startsWith("data:");
        if (!sse) {
            Object root = new JSONTokener(s).nextValue();
            if (root instanceof JSONObject) return (JSONObject) root;
            return new JSONObject().put("result", root);
        }
        JSONObject last = null;
        StringBuilder data = new StringBuilder();
        String[] lines = s.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith("data:")) {
                String d = line.substring(5).trim();
                if ("[DONE]".equals(d)) continue;
                data.append(d);
            } else if (line.trim().isEmpty() && data.length() > 0) {
                last = parseJsonObject(data.toString(), last);
                data.setLength(0);
            }
        }
        if (data.length() > 0) last = parseJsonObject(data.toString(), last);
        return last == null ? new JSONObject() : last;
    }

    private JSONObject parseJsonObject(String s, JSONObject fallback) {
        try {
            Object root = new JSONTokener(s).nextValue();
            if (root instanceof JSONObject) return (JSONObject) root;
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String contentToText(JSONObject result) {
        String direct = result.optString("text", "").trim();
        if (!direct.isEmpty()) return clip(direct, 6000);
        JSONArray content = result.optJSONArray("content");
        if (content == null) return clip(result.toString(), 6000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            Object item = content.opt(i);
            if (item instanceof JSONObject) {
                JSONObject o = (JSONObject) item;
                String type = o.optString("type", "");
                if ("text".equals(type)) sb.append(o.optString("text", ""));
                else if ("json".equals(type)) sb.append(o.opt("json"));
                else sb.append(o.toString());
            } else if (item != null) {
                sb.append(String.valueOf(item));
            }
            if (i + 1 < content.length()) sb.append('\n');
        }
        return clip(sb.toString(), 6000);
    }

    private static synchronized int nextId() {
        rpcId += 1;
        return rpcId;
    }

    private static String cacheKey(McpServerConfig cfg) {
        return cfg.id + "@" + cfg.url;
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        if (v.length() <= max) return v;
        return v.substring(0, max) + "...";
    }

    private static String toolNames(List<McpTool> tools) {
        JSONArray arr = new JSONArray();
        try {
            for (McpTool t : tools) arr.put(t.fullName);
        } catch (Exception ignored) {}
        return arr.toString();
    }

    private static class CacheEntry {
        final long at;
        final List<McpTool> tools;
        CacheEntry(long at, List<McpTool> tools) {
            this.at = at;
            this.tools = tools;
        }
    }

    public static class ToolResult {
        public final String name;
        public final String text;
        public final boolean isError;
        public final long ms;

        ToolResult(String name, String text, boolean isError, long ms) {
            this.name = name;
            this.text = text == null ? "" : text;
            this.isError = isError;
            this.ms = ms;
        }
    }
}
