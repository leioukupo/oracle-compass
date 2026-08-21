package com.magneo.compass.llm;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
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

    private final OkHttpClient client;
    public final String baseUrl, apiKey, voiceApiKey, textModel, visionModel, asrUrl, asrModel, ttsUrl, ttsModel, ttsVoice;

    public LlmClient(Context ctx) {
        OkHttpClient.Builder b = com.magneo.compass.netfs.Tls.builder(ctx)
                .connectionPool(SHARED_POOL)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS);
        client = b.build();
        baseUrl = strip(Prefs.get(ctx, Prefs.K_BASE_URL, "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        apiKey = Prefs.get(ctx, Prefs.K_API_KEY, "");
        voiceApiKey = Prefs.get(ctx, Prefs.K_VOICE_API_KEY, "");
        textModel = Prefs.get(ctx, Prefs.K_TEXT_MODEL, "qwen-plus");
        visionModel = Prefs.get(ctx, Prefs.K_VISION_MODEL, "qwen-vl-max");
        String asr = Prefs.get(ctx, Prefs.K_ASR_URL, "");
        if (asr.isEmpty() && !baseUrl.isEmpty()) asr = baseUrl; // 未单独配置时复用 Base URL
        asrUrl = asr;
        asrModel = Prefs.get(ctx, Prefs.K_ASR_MODEL, "whisper-1");
        String tts = Prefs.get(ctx, Prefs.K_TTS_URL, "");
        if (tts.isEmpty() && !baseUrl.isEmpty()) tts = baseUrl; // 未单独配置时复用 Base URL
        ttsUrl = tts;
        ttsModel = Prefs.get(ctx, Prefs.K_TTS_MODEL, "tts-1");
        ttsVoice = Prefs.get(ctx, Prefs.K_TTS_VOICE, "alloy");
    }

    private static String strip(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
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

    private static boolean looksHostRoot(String s) {
        int scheme = s.indexOf("://");
        if (scheme < 0) return false;
        int path = s.indexOf('/', scheme + 3);
        return path < 0 || path == s.length() - 1;
    }

    private String audioApiKey() {
        return voiceApiKey.isEmpty() ? apiKey : voiceApiKey;
    }

    public static class Msg {
        public final String role; // system/user/assistant
        public final String text;
        public final String imageBase64; // 可选
        public Msg(String role, String text) { this(role, text, null); }
        public Msg(String role, String text, String imageBase64) {
            this.role = role; this.text = text; this.imageBase64 = imageBase64;
        }
    }

    private JSONObject msgToJson(Msg m, boolean useVisionModel) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            if (m.imageBase64 != null && useVisionModel) {
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
            return o;
        } catch (Exception e) { return null; }
    }

    public interface StreamCallback {
        void onDelta(String s);
        void onDone(String full);
        void onError(String msg);
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
            return new ChatOptions(0.2f, 120);
        }

        public static ChatOptions gate() {
            return new ChatOptions(0.0f, 4);
        }
    }

    public Call chat(java.util.List<Msg> msgs, boolean useVisionModel, StreamCallback cb) {
        return chat(msgs, useVisionModel, ChatOptions.defaults(), cb);
    }

    public Call chat(java.util.List<Msg> msgs, boolean useVisionModel,
                     ChatOptions opts, StreamCallback cb) {
        try {
            JSONArray arr = new JSONArray();
            for (Msg m : msgs) arr.put(msgToJson(m, useVisionModel));
            JSONObject body = new JSONObject()
                    .put("model", useVisionModel ? visionModel : textModel)
                    .put("stream", true)
                    .put("messages", arr);
            if (opts != null) {
                if (opts.temperature != null) body.put("temperature", opts.temperature.floatValue());
                if (opts.maxTokens != null && opts.maxTokens.intValue() > 0) {
                    body.put("max_tokens", opts.maxTokens.intValue());
                }
            }
            Request req = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { cb.onError(e.getMessage()); }
                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        if (!resp.isSuccessful()) {
                            String b = resp.body() != null ? resp.body().string() : "";
                            cb.onError("HTTP " + resp.code() + " " + b);
                            return;
                        }
                        BufferedReader r = new BufferedReader(new InputStreamReader(resp.body().byteStream(), "UTF-8"));
                        StringBuilder full = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.equals("[DONE]")) break;
                            try {
                                JSONObject jo = new JSONObject(data);
                                JSONArray choices = jo.optJSONArray("choices");
                                if (choices == null || choices.length() == 0) continue;
                                JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                                if (delta == null) continue;
                                String d = delta.has("content") && !delta.isNull("content")
                                        ? delta.optString("content", "") : "";
                                if (d != null && !d.isEmpty()) { full.append(d); cb.onDelta(d); }
                            } catch (Exception ignored) {}
                        }
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

    public interface TextCallback { void onResult(String text); void onError(String msg); }

    /** ASR：OpenAI 兼容 /audio/transcriptions（multipart）。 */
    public Call transcribe(byte[] wav, TextCallback cb) {
        if (asrUrl.isEmpty()) { cb.onError("未配置 ASR 语音 API"); return null; }
        try {
            RequestBody file = RequestBody.create(AUDIO, wav);
            RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", asrModel)
                    .addFormDataPart("file", "input.wav", file)
                    .build();
            Request req = new Request.Builder().url(endpoint(asrUrl, "/audio/transcriptions"))
                    .addHeader("Authorization", "Bearer " + audioApiKey()).post(body).build();
            Call call = client.newCall(req);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    cb.onError(e.getMessage());
                }

                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        String s = resp.body() != null ? resp.body().string() : "";
                        if (!resp.isSuccessful()) { cb.onError("HTTP " + resp.code() + " " + s); return; }
                        try { cb.onResult(new JSONObject(s).optString("text", "")); }
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
}
