package com.magneo.compass.llm;

import android.content.Context;
import android.util.Log;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
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

    private final OkHttpClient client;
    public final String baseUrl, apiKey, textModel, visionModel, asrUrl, asrModel, ttsUrl, ttsModel, ttsVoice;

    public LlmClient(Context ctx) {
        boolean ignoreSsl = Prefs.getB(ctx, Prefs.K_IGNORE_SSL, false);
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS);
        if (ignoreSsl) {
            try {
                TrustManager[] tms = {new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, tms, new java.security.SecureRandom());
                b.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) tms[0]);
                b.hostnameVerifier((h, s) -> true);
            } catch (Exception e) { Log.w(TAG, "ssl bypass failed", e); }
        } else {
            // 安卓 5.1 信任库较旧：追加内置的现代根证书（ISRG/GTS 等）
            try {
                TrustManager[] tms = bundledTrustManagers(ctx);
                if (tms != null) {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, tms, null);
                    b.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) tms[0]);
                }
            } catch (Exception e) { Log.w(TAG, "bundled trust failed", e); }
        }
        client = b.build();
        baseUrl = strip(Prefs.get(ctx, Prefs.K_BASE_URL, "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        apiKey = Prefs.get(ctx, Prefs.K_API_KEY, "");
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

    /** 系统信任库 + assets/certs 内置根证书的复合 TrustManager。 */
    private static TrustManager[] bundledTrustManagers(Context ctx) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] files = ctx.getAssets().list("certs");
            if (files != null) {
                for (String f : files) {
                    try {
                        InputStream in = ctx.getAssets().open("certs/" + f);
                        Certificate c = cf.generateCertificate(in);
                        in.close();
                        ks.setCertificateEntry(f, c);
                    } catch (Exception ignored) {}
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            return tmf.getTrustManagers();
        } catch (Exception e) {
            Log.w(TAG, "bundled trust init failed", e);
            return null;
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
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

    public void chat(java.util.List<Msg> msgs, boolean useVisionModel, StreamCallback cb) {
        try {
            JSONArray arr = new JSONArray();
            for (Msg m : msgs) arr.put(msgToJson(m, useVisionModel));
            JSONObject body = new JSONObject()
                    .put("model", useVisionModel ? visionModel : textModel)
                    .put("stream", true)
                    .put("messages", arr);
            Request req = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            client.newCall(req).enqueue(new Callback() {
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
                                String d = delta.optString("content", null);
                                if (d != null && !d.isEmpty()) { full.append(d); cb.onDelta(d); }
                            } catch (Exception ignored) {}
                        }
                        cb.onDone(full.toString());
                    } finally {
                        if (resp.body() != null) resp.body().close();
                    }
                }
            });
        } catch (Exception e) { cb.onError(e.getMessage()); }
    }

    public interface TextCallback { void onResult(String text); void onError(String msg); }

    /** ASR：OpenAI 兼容 /audio/transcriptions（multipart）。 */
    public void transcribe(byte[] wav, TextCallback cb) {
        if (asrUrl.isEmpty()) { cb.onError("未配置 ASR 语音 API"); return; }
        RequestBody file = RequestBody.create(AUDIO, wav);
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", asrModel)
                .addFormDataPart("file", "input.wav", file)
                .build();
        Request req = new Request.Builder().url(strip(asrUrl) + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey).post(body).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) { cb.onError(e.getMessage()); }
            @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                try {
                    String s = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) { cb.onError("HTTP " + resp.code() + " " + s); return; }
                    try { cb.onResult(new JSONObject(s).optString("text", "")); }
                    catch (Exception e) { cb.onError("解析失败"); }
                } finally { if (resp.body() != null) resp.body().close(); }
            }
        });
    }

    public interface BytesCallback { void onResult(byte[] audio); void onError(String msg); }

    /** TTS：OpenAI 兼容 /audio/speech，返回音频字节。 */
    public void synthesize(String text, BytesCallback cb) {
        if (ttsUrl.isEmpty()) { cb.onError("未配置 TTS 语音 API"); return; }
        try {
            JSONObject body = new JSONObject()
                    .put("model", ttsModel).put("voice", ttsVoice).put("input", text);
            Request req = new Request.Builder().url(strip(ttsUrl) + "/audio/speech")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(JSON, body.toString())).build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { cb.onError(e.getMessage()); }
                @Override public void onResponse(Call call, Response resp) throws java.io.IOException {
                    try {
                        if (!resp.isSuccessful()) {
                            String s = resp.body() != null ? resp.body().string() : "";
                            cb.onError("HTTP " + resp.code() + " " + s);
                            return;
                        }
                        cb.onResult(resp.body().bytes());
                    } finally { if (resp.body() != null) resp.body().close(); }
                }
            });
        } catch (Exception e) { cb.onError(e.getMessage()); }
    }
}
