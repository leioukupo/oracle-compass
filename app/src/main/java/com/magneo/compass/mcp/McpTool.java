package com.magneo.compass.mcp;

import org.json.JSONObject;

public class McpTool {
    public final McpServerConfig server;
    public final String name;
    public final String fullName;
    public final String description;
    public final JSONObject inputSchema;

    public McpTool(McpServerConfig server, String name, String description,
                   JSONObject inputSchema) {
        this.server = server;
        this.name = name == null ? "" : name.trim();
        String safeName = this.name.replaceAll("[^A-Za-z0-9_-]+", "_");
        String joined = server.id + "__" + (safeName.isEmpty() ? "tool" : safeName);
        this.fullName = joined.length() <= 64 ? joined : joined.substring(0, 64);
        this.description = description == null ? "" : description.trim();
        this.inputSchema = inputSchema == null ? new JSONObject() : inputSchema;
    }

    public JSONObject toOpenAiToolJson() {
        JSONObject root = new JSONObject();
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", fullName);
            String desc = description.isEmpty() ? ("MCP tool from " + server.name)
                    : ("[" + server.name + "] " + description);
            fn.put("description", desc.length() > 900 ? desc.substring(0, 900) : desc);
            JSONObject schema = inputSchema;
            if (schema.length() == 0) {
                schema = new JSONObject().put("type", "object")
                        .put("properties", new JSONObject());
            }
            fn.put("parameters", schema);
            root.put("type", "function");
            root.put("function", fn);
        } catch (Exception ignored) {}
        return root;
    }
}
