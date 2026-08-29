package com.magneo.compass.llm;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.DebugLog;
import com.magneo.compass.Prefs;
import com.magneo.compass.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** OpenAI 兼容大模型客户端：文本/视觉对话（SSE 流式）、ASR、TTS。 */
public class LlmClient {
    private static final String TAG = "LlmClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType AUDIO = MediaType.parse("audio/wav");
    private static final ConnectionPool SHARED_POOL = new ConnectionPool(8, 5, TimeUnit.MINUTES);

    private final Context ctx;
    private final OkHttpClient client;
    public final String provider, baseUrl, textBaseUrl, visionBaseUrl, apiKey, voiceApiKey, reasoningEffort, textModel, visionModel,
            asrUrl, asrFinalUrl, asrModel, ttsUrl, ttsModel, ttsVoice;
    public final int textMaxTokens, voiceMaxTokens, visionMaxTokens;
    public final float textTemperature, voiceTemperature, visionTemperature;

    public LlmClient(Context ctx) {
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
        OkHttpClient.Builder b = com.magneo.compass.netfs.Tls.builder(ctx)
                .connectionPool(SHARED_POOL)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS);
        client = b.build();
        provider = ProviderConfig.normalizeName(Prefs.get(ctx, Prefs.K_PROVIDER, ""));
        ProviderConfig preset = ProviderConfig.byName(provider);
        String commonBase = strip(Prefs.get(ctx, Prefs.K_BASE_URL, ""));
        if (commonBase.isEmpty() && ProviderConfig.PROVIDER_DEEPSEEK.equals(provider)) {
            commonBase = ProviderConfig.DEEPSEEK_BASE_URL;
        }
        baseUrl = commonBase;
        String textBase = strip(Prefs.get(ctx, Prefs.K_TEXT_BASE_URL, ""));
        String visionBase = strip(Prefs.get(ctx, Prefs.K_VISION_BASE_URL, ""));
        textBaseUrl = textBase.isEmpty() ? baseUrl : textBase;
        visionBaseUrl = visionBase.isEmpty() ? baseUrl : visionBase;
        apiKey = Prefs.get(ctx, Prefs.K_API_KEY, "");
        voiceApiKey = Prefs.get(ctx, Prefs.K_VOICE_API_KEY, "");
        reasoningEffort = normalizeReasoningEffort(Prefs.get(ctx, Prefs.K_REASONING_EFFORT, Prefs.DEFAULT_REASONING_EFFORT));
        textMaxTokens = clampInt(Prefs.getI(ctx, Prefs.K_TEXT_MAX_TOKENS,
                Prefs.DEFAULT_TEXT_MAX_TOKENS), 0, 8192);
        voiceMaxTokens = clampInt(Prefs.getI(ctx, Prefs.K_VOICE_MAX_TOKENS,
                Prefs.DEFAULT_VOICE_MAX_TOKENS), 0, 8192);
        visionMaxTokens = clampInt(Prefs.getI(ctx, Prefs.K_VISION_MAX_TOKENS,
                Prefs.DEFAULT_VISION_MAX_TOKENS), 0, 8192);
        textTemperature = clampFloat(Prefs.getF(ctx, Prefs.K_TEXT_TEMPERATURE,
                Prefs.DEFAULT_TEXT_TEMPERATURE), 0f, 2f);
        voiceTemperature = clampFloat(Prefs.getF(ctx, Prefs.K_VOICE_TEMPERATURE,
                Prefs.DEFAULT_VOICE_TEMPERATURE), 0f, 2f);
        visionTemperature = clampFloat(Prefs.getF(ctx, Prefs.K_VISION_TEMPERATURE,
                Prefs.DEFAULT_VISION_TEMPERATURE), 0f, 2f);
        textModel = nonEmpty(Prefs.get(ctx, Prefs.K_TEXT_MODEL, ""), preset.textModel);
        visionModel = nonEmpty(Prefs.get(ctx, Prefs.K_VISION_MODEL, ""), preset.visionModel);
        String asr = Prefs.get(ctx, Prefs.K_ASR_URL, "");
        if (asr.isEmpty() && !baseUrl.isEmpty()) asr = baseUrl; // 未单独配置时复用 Base URL
        asrUrl = asr;
        String asrFinal = Prefs.get(ctx, Prefs.K_ASR_FINAL_URL, "");
        if (asrFinal.isEmpty()) asrFinal = asr;
        asrFinalUrl = asrFinal;
        asrModel = Prefs.get(ctx, Prefs.K_ASR_MODEL, "whisper-1");
        String tts = Prefs.get(ctx, Prefs.K_TTS_URL, "");
        if (tts.isEmpty() && !baseUrl.isEmpty()) tts = baseUrl; // 未单独配置时复用 Base URL
        ttsUrl = tts;
        ttsModel = Prefs.get(ctx, Prefs.K_TTS_MODEL, "tts-1");
        ttsVoice = Prefs.get(ctx, Prefs.K_TTS_VOICE, "alloy");
    }

    private static String strip(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFloat(float v, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static String endpoint(String baseOrEndpoint, String suffix) {
        String s = strip(baseOrEndpoint);
        if (s.startsWith("ws://")) s = "http://" + s.substring("ws://".length());
        else if (s.startsWith("wss://")) s = "https://" + s.substring("wss://".length());
        if (s.endsWith(suffix)) return s;
        if (s.endsWith("/v1")) return s + suffix;
        if (looksHostRoot(s)) return s + "/v1" + suffix;
        return s + suffix;
    }

    private static String voicesEndpoint(String baseOrEndpoint) {
        String s = strip(baseOrEndpoint);
        if (s.endsWith("/audio/speech")) {
            s = s.substring(0, s.length() - "/audio/speech".length());
        }
        if (s.endsWith("/voices")) return s;
        if (s.endsWith("/v1")) return s + "/voices";
        if (looksHostRoot(s)) return s + "/v1/voices";
        return s + "/voices";
    }

    private static String transcribeEndpoint(String baseOrEndpoint) {
        if (baseOrEndpoint == null || baseOrEndpoint.trim().isEmpty()) return "";
        String s = strip(baseOrEndpoint);
        if (s.startsWith("ws://")) s = "http://" + s.substring("ws://".length());
        else if (s.startsWith("wss://")) s = "https://" + s.substring("wss://".length());
        String lower = s.toLowerCase(Locale.US);
        if (lower.endsWith("/audio/transcriptions") || lower.endsWith("/api/v1/asr")) return s;
        if (lower.endsWith("/v1")) return s + "/audio/transcriptions";
        if (lower.endsWith("/api/v1")) return s + "/asr";
        if (looksHostRoot(s)) {
            if (lower.contains("openai.com") || lower.contains("dashscope") || lower.contains("deepseek")) {
                return s + "/v1/audio/transcriptions";
            }
            return s + "/api/v1/asr";
        }
        if (lower.contains("openai.com") || lower.contains("dashscope") || lower.contains("deepseek")) {
            return s + "/audio/transcriptions";
        }
        return s + "/api/v1/asr";
    }

    private static boolean isSenseVoiceTranscribe(String url) {
        String lower = strip(url).toLowerCase(Locale.US);
        return lower.contains("/api/v1/asr");
    }

    private static boolean looksHostRoot(String s) {
        int scheme = s.indexOf("://");
        if (scheme < 0) return false;
        int path = s.indexOf('/', scheme + 3);
        return path < 0 || path == s.length() - 1;
    }

    private String audioApiKey() {
        return voiceApiKey.isEmpty() ? apiKey : voiceApiKey;
    }

    private boolean isDeepSeekLike(String activeBaseUrl) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.US);
        String u = activeBaseUrl == null ? "" : activeBaseUrl.toLowerCase(Locale.US);
        return p.contains("deepseek") || u.contains("deepseek");
    }

    private boolean isOpenAiLike(String activeBaseUrl) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.US);
        String u = activeBaseUrl == null ? "" : activeBaseUrl.toLowerCase(Locale.US);
        return p.contains("openai") || u.contains("openai.com") || u.contains("api.openai.com");
    }

    private static String normalizeReasoningEffort(String effort) {
        if (effort == null) return Prefs.DEFAULT_REASONING_EFFORT;
        String s = effort.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return Prefs.DEFAULT_REASONING_EFFORT;
        if ("auto".equals(s) || "none".equals(s) || "low".equals(s) || "medium".equals(s)
                || "high".equals(s) || "max".equals(s)) {
            return s;
        }
        return Prefs.DEFAULT_REASONING_EFFORT;
    }

    public static class Msg {
        public final String role; // system/user/assistant/tool
        public final String text;
        public final String imageBase64; // 可选
        public final String toolCallId;
        public final JSONArray toolCalls;
        /** DeepSeek 思考模式的上下文，仅在后续带工具的 DeepSeek 请求中回传。 */
        public final String reasoningContent;
        public Msg(String role, String text) { this(role, text, null); }
        public Msg(String role, String text, String imageBase64) {
            this(role, text, imageBase64, null, null, null);
        }
        private Msg(String role, String text, String imageBase64,
                    String toolCallId, JSONArray toolCalls, String reasoningContent) {
            this.role = role;
            this.text = text;
            this.imageBase64 = imageBase64;
            this.toolCallId = toolCallId;
            this.toolCalls = toolCalls;
            this.reasoningContent = reasoningContent;
        }
        public static Msg toolResult(String toolCallId, String text) {
            return new Msg("tool", text, null, toolCallId, null, null);
        }
        public static Msg assistantToolCalls(JSONArray toolCalls) {
            return assistantToolCalls("", null, toolCalls);
        }
        public static Msg assistantToolCalls(String text, String reasoningContent, JSONArray toolCalls) {
            return new Msg("assistant", text, null, null, toolCalls, reasoningContent);
        }
        public static Msg assistantResponse(String text, String reasoningContent) {
            return new Msg("assistant", text, null, null, null, reasoningContent);
        }
    }

    private JSONObject msgToJson(Msg m, boolean useVisionModel, boolean includeReasoningContent) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            if ("tool".equals(m.role)) {
                o.put("tool_call_id", m.toolCallId == null ? "" : m.toolCallId);
                o.put("content", m.text == null ? "" : m.text);
            } else if (m.toolCalls != null) {
                o.put("content", m.text == null || m.text.isEmpty() ? JSONObject.NULL : m.text);
                o.put("tool_calls", m.toolCalls);
            } else if (m.imageBase64 != null && useVisionModel) {
                JSONArray arr = new JSONArray();
                JSONObject t = new JSONObject(); t.put("type", "text"); t.put("text", m.text);
                arr.put(t);
                JSONObject img = new JSONObject(); img.put("type", "image_url");
                JSONObject url = new JSONObject();
                url.put("url", "data:image/jpeg;base64," + m.imageBase64);
                img.put("image_url", url);
                arr.put(img);
                o.put("content", arr);
            } else {
                o.put("content", m.text);
            }
            if (includeReasoningContent && "assistant".equals(m.role)
                    && m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                o.put("reasoning_content", m.reasoningContent);
            }
            return o;
        } catch (Exception e) { return null; }
    }

    public interface StreamCallback {
        void onDelta(String s);
        default void onReasoningContent(String content) {}
        default void onToolCalls(List<ToolCall> calls) {}
        void onDone(String full);
        void onError(String msg);
    }

    public static class ToolCall {
        public final String id;
        public final String name;
        public final String argumentsJson;

        ToolCall(String id, String name, String argumentsJson) {
            this.id = id == null || id.isEmpty() ? "call_" + System.currentTimeMillis() : id;
            this.name = name == null ? "" : name;
            this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        }

        public JSONObject toAssistantJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("type", "function");
                o.put("function", new JSONObject()
                        .put("name", name)
                        .put("arguments", argumentsJson));
            } catch (Exception ignored) {}
            return o;
        }
    }

    private static class ToolCallAcc {
        String id = "";
        String type = "function";
        String name = "";
        StringBuilder args = new StringBuilder();
    }

    public static class ChatOptions {
        public final Float temperature;
        public final Integer maxTokens;

        private ChatOptions(Float temperature, Integer maxTokens) {
            this.temperature = temperature;
            this.maxTokens = maxTokens;
        }

        public static ChatOptions defaults() {
            return new ChatOptions(null, null);
        }

        public static ChatOptions voice() {
            return new ChatOptions(Prefs.DEFAULT_VOICE_TEMPERATURE,
                    Prefs.DEFAULT_VOICE_MAX_TOKENS);
        }

        public static ChatOptions gate() {
            return new ChatOptions(0.0f, 4);
        }

        public static ChatOptions oracle() {
            return new ChatOptions(0.45f, 360);
        }
    }

    public ChatOptions defaultOptions(boolean useVisionModel) {
        return new ChatOptions(useVisionModel ? visionTemperature : textTemperature,
                useVisionModel ? visionMaxTokens : textMaxTokens);
    }

    public ChatOptions voiceOptions() {
        return new ChatOptions(voiceTemperature, voiceMaxTokens);
    }

    public Call chat(java.util.List<Msg> msgs, boolean useVisionModel, StreamCallback cb) {
        return chat(msgs, useVisionModel, defaultOptions(useVisionModel), cb);
    }

    public Call chat(java.util.List<Msg> msgs, boolean useVisionModel,
                     ChatOptions opts, StreamCallback cb) {
        return chat(msgs, useVisionModel, opts, null, cb);
    }

    public Call chat(java.util.List<Msg> msgs, boolean useVisionModel,
                     ChatOptions opts, JSONArray tools, StreamCallback cb) {
        try {
            String chatBaseUrl = useVisionModel ? visionBaseUrl : textBaseUrl;
            if (chatBaseUrl == null || chatBaseUrl.isEmpty()) {
                cb.onError("未配置大模型 Base URL");
                return null;
            }
            boolean deepSeekWithTools = isDeepSeekLike(chatBaseUrl)
                    && tools != null && tools.length() > 0;
            JSONArray arr = new JSONArray();
            for (Msg m : msgs) arr.put(msgToJson(m, useVisionModel, deepSeekWithTools));
            JSONObject body = new JSONObject()
                    .put("model", useVisionModel ? visionModel : textModel)
                    .put("stream", true)
                    .put("messages", arr);
            if (opts != null) {
                // DeepSeek 思考模式不支持 temperature；省略它以免设置页产生误导。
                boolean deepSeekThinking = isDeepSeekLike(chatBaseUrl) && !"none".equals(reasoningEffort);
                if (opts.temperature != null && !deepSeekThinking) {
                    body.put("temperature", opts.temperature.floatValue());
                }
                if (opts.maxTokens != null && opts.maxTokens.intValue() > 0) {
                    body.put("max_tokens", opts.maxTokens.intValue());
                }
            }
            if (tools != null && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
            String effort = reasoningEffort;
            if (!"auto".equals(effort)) {
                if (isDeepSeekLike(chatBaseUrl)) {
                    if ("none".equals(effort)) {
                        body.put("thinking", new JSONObject().put("type", "disabled"));
                    } else {
                        body.put("thinking", new JSONObject().put("type", "enabled"));
                        body.put("reasoning_effort", effort);
                    }
                } else if (isOpenAiLike(chatBaseUrl)) {
                    body.put("reasoning_effort", effort);
                }
            }
            Request req = new Request.Builder()
                    .url(chatBaseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            DebugLog.append(ctx, "llm.request", "url=" + chatBaseUrl + "/chat/completions"
                    + " model=" + (useVisionModel ? visionModel : textModel)
                    + " tools=" + (tools == null ? 0 : tools.length())
                    + "\n" + body.toString());
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    DebugLog.append(ctx, "llm.error", e.getMessage());
                    cb.onError(e.getMessage());
                }
                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        if (!resp.isSuccessful()) {
                            String b = resp.body() != null ? resp.body().string() : "";
                            DebugLog.append(ctx, "llm.http_error", "HTTP " + resp.code() + " " + b);
                            cb.onError("HTTP " + resp.code() + " " + b);
                            return;
                        }
                        BufferedReader r = new BufferedReader(new InputStreamReader(resp.body().byteStream(), "UTF-8"));
                        StringBuilder full = new StringBuilder();
                        StringBuilder reasoning = new StringBuilder();
                        ArrayList<ToolCallAcc> toolAcc = new ArrayList<>();
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JSONObject jo = new JSONObject(data);
                                JSONArray choices = jo.optJSONArray("choices");
                                if (choices == null || choices.length() == 0) continue;
                                JSONObject choice = choices.optJSONObject(0);
                                if (choice == null) continue;
                                JSONObject delta = choice.optJSONObject("delta");
                                if (delta == null) continue;
                                JSONArray calls = delta.optJSONArray("tool_calls");
                                if (calls != null) mergeToolCalls(toolAcc, calls);
                                String thought = delta.has("reasoning_content") && !delta.isNull("reasoning_content")
                                        ? delta.optString("reasoning_content", "") : "";
                                if (thought != null && !thought.isEmpty()) reasoning.append(thought);
                                String d = delta.has("content") && !delta.isNull("content")
                                        ? delta.optString("content", "") : "";
                                if (d != null && !d.isEmpty()) { full.append(d); cb.onDelta(d); }
                            } catch (Exception ignored) {}
                        }
                        List<ToolCall> calls = buildToolCalls(toolAcc);
                        if (reasoning.length() > 0 && isDeepSeekLike(chatBaseUrl)) {
                            cb.onReasoningContent(reasoning.toString());
                        }
                        if (!calls.isEmpty()) {
                            DebugLog.append(ctx, "llm.tool_calls", toolCallsToDebug(calls));
                            cb.onToolCalls(calls);
                        }
                        DebugLog.append(ctx, "llm.done", full.toString());
                        cb.onDone(full.toString());
                    } finally {
                        if (resp.body() != null) resp.body().close();
                    }
                }
            });
            return call;
        } catch (Exception e) { cb.onError(e.getMessage()); }
        return null;
    }

    private static void mergeToolCalls(ArrayList<ToolCallAcc> acc, JSONArray calls) {
        for (int i = 0; i < calls.length(); i++) {
            JSONObject c = calls.optJSONObject(i);
            if (c == null) continue;
            int idx = Math.max(0, c.optInt("index", i));
            while (acc.size() <= idx) acc.add(new ToolCallAcc());
            ToolCallAcc a = acc.get(idx);
            String id = c.optString("id", "");
            if (!id.isEmpty()) a.id = id;
            String type = c.optString("type", "");
            if (!type.isEmpty()) a.type = type;
            JSONObject fn = c.optJSONObject("function");
            if (fn != null) {
                String name = fn.optString("name", "");
                if (!name.isEmpty()) a.name = name;
                String args = fn.optString("arguments", "");
                if (!args.isEmpty()) a.args.append(args);
            }
        }
    }

    private static List<ToolCall> buildToolCalls(ArrayList<ToolCallAcc> acc) {
        ArrayList<ToolCall> out = new ArrayList<>();
        for (int i = 0; i < acc.size(); i++) {
            ToolCallAcc a = acc.get(i);
            if (a == null || a.name == null || a.name.isEmpty()) continue;
            String args = a.args.length() == 0 ? "{}" : a.args.toString();
            out.add(new ToolCall(a.id.isEmpty() ? "call_" + i : a.id, a.name, args));
        }
        return out;
    }

    private static String toolCallsToDebug(List<ToolCall> calls) {
        JSONArray arr = new JSONArray();
        try {
            for (ToolCall c : calls) {
                arr.put(new JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("arguments", c.argumentsJson));
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    public interface TextCallback { void onResult(String text); void onError(String msg); }

    /** ASR：OpenAI 兼容 /audio/transcriptions（multipart）。 */
    public Call transcribe(byte[] wav, TextCallback cb) {
        String endpoint = transcribeEndpoint(asrFinalUrl);
        if (endpoint.isEmpty()) { cb.onError("未配置 ASR 语音 API"); return null; }
        try {
            Request req;
            if (isSenseVoiceTranscribe(endpoint)) {
                RequestBody file = RequestBody.create(AUDIO, wav);
                RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("files", "input.wav", file)
                        .addFormDataPart("lang", "auto")
                        .addFormDataPart("use_itn", "true")
                        .build();
                req = new Request.Builder().url(endpoint)
                        .addHeader("Authorization", "Bearer " + audioApiKey())
                        .post(body).build();
            } else {
                RequestBody file = RequestBody.create(AUDIO, wav);
                RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", asrModel)
                        .addFormDataPart("file", "input.wav", file)
                        .build();
                req = new Request.Builder().url(endpoint)
                        .addHeader("Authorization", "Bearer " + audioApiKey())
                        .post(body).build();
            }
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    cb.onError(e.getMessage());
                }

                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        String s = resp.body() != null ? resp.body().string() : "";
                        if (!resp.isSuccessful()) { cb.onError("HTTP " + resp.code() + " " + s); return; }
                        try { cb.onResult(parseTranscribeText(s)); }
                        catch (Exception e) { cb.onError("解析失败"); }
                    } finally { if (resp.body() != null) resp.body().close(); }
                }
            });
            return call;
        } catch (Exception e) {
            cb.onError(e.getMessage());
            return null;
        }
    }

    public interface BytesCallback {
        void onResult(byte[] audio);
        default void onResult(byte[] audio, String contentType) { onResult(audio); }
        void onError(String msg);
    }

    public interface VoicesCallback {
        void onResult(List<String> voices);
        void onError(String msg);
    }

    /** TTS：OpenAI 兼容 /audio/speech，返回音频字节。 */
    public Call synthesize(String text, BytesCallback cb) {
        return synthesize(text, null, cb);
    }

    public Call synthesize(String text, String voiceOverride, BytesCallback cb) {
        if (ttsUrl.isEmpty()) { cb.onError("未配置 TTS 语音 API"); return null; }
        try {
            String voice = voiceOverride == null || voiceOverride.trim().isEmpty()
                    ? ttsVoice : voiceOverride.trim();
            JSONObject body = new JSONObject()
                    .put("model", ttsModel)
                    .put("voice", voice)
                    .put("input", text)
                    .put("response_format", "wav");
            Request req = new Request.Builder().url(endpoint(ttsUrl, "/audio/speech"))
                    .addHeader("Authorization", "Bearer " + audioApiKey())
                    .addHeader("Connection", "close")
                    .post(RequestBody.create(JSON, body.toString())).build();
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { cb.onError(e.getMessage()); }
                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        if (!resp.isSuccessful()) {
                            String s = resp.body() != null ? resp.body().string() : "";
                            cb.onError("HTTP " + resp.code() + " " + s);
                            return;
                        }
                        cb.onResult(resp.body().bytes(), resp.header("Content-Type", ""));
                    } finally { if (resp.body() != null) resp.body().close(); }
                }
            });
            return call;
        } catch (Exception e) { cb.onError(e.getMessage()); }
        return null;
    }

    public Call listTtsVoices(VoicesCallback cb) {
        if (ttsUrl.isEmpty()) { cb.onError("未配置 TTS 语音 API"); return null; }
        try {
            Request req = new Request.Builder().url(voicesEndpoint(ttsUrl))
                    .addHeader("Authorization", "Bearer " + audioApiKey())
                    .get().build();
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    cb.onError(e.getMessage());
                }

                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        String s = resp.body() != null ? resp.body().string() : "";
                        if (!resp.isSuccessful()) {
                            cb.onError("HTTP " + resp.code() + " " + s);
                            return;
                        }
                        cb.onResult(parseTtsVoices(s));
                    } finally { if (resp.body() != null) resp.body().close(); }
                }
            });
            return call;
        } catch (Exception e) { cb.onError(e.getMessage()); }
        return null;
    }

    private static List<String> parseTtsVoices(String json) {
        ArrayList<String> out = new ArrayList<>();
        try {
            Object root = new org.json.JSONTokener(json).nextValue();
            if (root instanceof JSONArray) {
                addVoiceArray(out, (JSONArray) root, false);
            } else if (root instanceof JSONObject) {
                JSONObject o = (JSONObject) root;
                addVoiceArray(out, o.optJSONArray("custom_voices"), true);
                addVoiceArray(out, o.optJSONArray("preset_voices"), false);
                addVoiceArray(out, o.optJSONArray("voices"), false);
                addVoiceArray(out, o.optJSONArray("data"), false);
                addVoiceObject(out, o, false);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void addVoiceArray(ArrayList<String> out, JSONArray arr, boolean custom) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) addVoiceObject(out, (JSONObject) item, custom);
            else if (item != null) addVoiceCandidate(out, String.valueOf(item));
        }
    }

    private static void addVoiceObject(ArrayList<String> out, JSONObject o, boolean custom) {
        String id = o.optString("id", "").trim();
        if (id.isEmpty()) id = o.optString("voice_id", "").trim();
        if (id.isEmpty()) id = o.optString("voice", "").trim();
        if (id.isEmpty() && !custom) id = o.optString("name", "").trim();
        if (id.isEmpty()) id = o.optString("name", "").trim();
        addVoiceCandidate(out, id);
    }

    private static void addVoiceCandidate(ArrayList<String> out, String voice) {
        String v = voice == null ? "" : voice.trim();
        if (v.isEmpty() || out.contains(v)) return;
        out.add(v);
    }

    private static String parseTranscribeText(String json) {
        try {
            Object root = new org.json.JSONTokener(json).nextValue();
            if (root instanceof JSONObject) {
                JSONObject o = (JSONObject) root;
                String text = o.optString("text", "").trim();
                if (!text.isEmpty()) return text;
                Object result = o.opt("result");
                if (result instanceof JSONArray) {
                    JSONArray arr = (JSONArray) result;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject j = (JSONObject) item;
                        String[] keys = {"text", "clean_text", "raw_text", "sentence", "transcript"};
                        for (String k : keys) {
                            String v = j.optString(k, "").trim();
                            if (!v.isEmpty()) return v;
                        }
                    }
                } else if (result != null) {
                    String v = String.valueOf(result).trim();
                    if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
                }
                String[] keys = {"clean_text", "raw_text", "result", "sentence", "transcript"};
                for (String k : keys) {
                    String v = o.optString(k, "").trim();
                    if (!v.isEmpty()) return v;
                }
            } else if (root instanceof JSONArray) {
                JSONArray arr = (JSONArray) root;
                if (arr.length() > 0) {
                    Object item = arr.opt(0);
                    if (item instanceof JSONObject) {
                        JSONObject j = (JSONObject) item;
                        String[] keys = {"text", "clean_text", "raw_text", "sentence", "transcript"};
                        for (String k : keys) {
                            String v = j.optString(k, "").trim();
                            if (!v.isEmpty()) return v;
                        }
                    } else if (item != null) {
                        String v = String.valueOf(item).trim();
                        if (!v.isEmpty()) return v;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
