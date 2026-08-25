package com.magneo.compass.mcp;

import android.content.Context;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

public class McpManager {
    public static JSONArray openAiTools(Context ctx) {
        JSONArray arr = new JSONArray();
        for (McpTool t : listToolsBestEffort(ctx, false)) {
            arr.put(t.toOpenAiToolJson());
        }
        return arr;
    }

    public static List<McpTool> listToolsBestEffort(Context ctx, boolean forceRefresh) {
        ArrayList<McpTool> out = new ArrayList<>();
        if (!Prefs.mcpEnabled(ctx)) return out;
        McpClient client = new McpClient(ctx);
        for (McpServerConfig cfg : McpServerConfig.load(ctx)) {
            if (!cfg.enabled) continue;
            try {
                out.addAll(client.listTools(cfg, forceRefresh));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static McpClient.ToolResult call(Context ctx, String fullName, String argsJson)
            throws Exception {
        if (!Prefs.mcpEnabled(ctx)) throw new IllegalStateException("MCP 未启用");
        McpClient client = new McpClient(ctx);
        for (McpServerConfig cfg : McpServerConfig.load(ctx)) {
            if (!cfg.enabled) continue;
            List<McpTool> tools = client.listTools(cfg, false);
            for (McpTool t : tools) {
                if (!t.fullName.equals(fullName)) continue;
                return client.callTool(t, parseArgs(argsJson));
            }
        }
        throw new IllegalArgumentException("未找到 MCP 工具：" + fullName);
    }

    public static JSONObject status(Context ctx, boolean forceRefresh) {
        JSONObject root = new JSONObject();
        JSONArray servers = new JSONArray();
        JSONArray tools = new JSONArray();
        JSONArray errors = new JSONArray();
        long start = System.currentTimeMillis();
        try {
            root.put("ok", true);
            root.put("enabled", Prefs.mcpEnabled(ctx));
            McpClient client = new McpClient(ctx);
            for (McpServerConfig cfg : McpServerConfig.load(ctx)) {
                JSONObject so = cfg.toJson(false);
                servers.put(so);
                if (!cfg.enabled) continue;
                try {
                    List<McpTool> list = client.listTools(cfg, forceRefresh);
                    so.put("toolCount", list.size());
                    so.put("ok", true);
                    for (McpTool t : list) {
                        tools.put(new JSONObject()
                                .put("name", t.fullName)
                                .put("server", cfg.name)
                                .put("rawName", t.name)
                                .put("description", t.description));
                    }
                } catch (Exception e) {
                    so.put("ok", false);
                    so.put("err", e.getMessage());
                    errors.put(cfg.name + ": " + e.getMessage());
                }
            }
            root.put("servers", servers);
            root.put("tools", tools);
            root.put("errors", errors);
            root.put("ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            try {
                root.put("ok", false);
                root.put("err", e.getMessage());
            } catch (Exception ignored) {}
        }
        return root;
    }

    private static JSONObject parseArgs(String argsJson) {
        String s = argsJson == null ? "" : argsJson.trim();
        if (s.isEmpty()) return new JSONObject();
        try {
            Object root = new JSONTokener(s).nextValue();
            if (root instanceof JSONObject) return (JSONObject) root;
        } catch (Exception ignored) {}
        return new JSONObject();
    }
}
