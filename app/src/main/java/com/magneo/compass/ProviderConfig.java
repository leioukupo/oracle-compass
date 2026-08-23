package com.magneo.compass;
import android.content.Context;

/** 大模型 Provider 预设（OpenAI 兼容协议）。语音端点独立配置，可留空。 */
public class ProviderConfig {
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_OPENAI_COMPAT = "openai兼容";
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";

    public final String name, baseUrl, textModel, visionModel, asrUrl, asrModel, ttsUrl, ttsModel, ttsVoice;

    public ProviderConfig(String name, String baseUrl, String textModel, String visionModel,
                          String asrUrl, String asrModel, String ttsUrl, String ttsModel, String ttsVoice) {
        this.name = name; this.baseUrl = baseUrl; this.textModel = textModel; this.visionModel = visionModel;
        this.asrUrl = asrUrl; this.asrModel = asrModel; this.ttsUrl = ttsUrl; this.ttsModel = ttsModel; this.ttsVoice = ttsVoice;
    }

    public static ProviderConfig deepseek() {
        return new ProviderConfig(PROVIDER_DEEPSEEK, DEEPSEEK_BASE_URL,
                "deepseek-chat", "deepseek-chat", "", "whisper-1", "", "", "");
    }

    public static ProviderConfig openaiCompatible() {
        return new ProviderConfig(PROVIDER_OPENAI_COMPAT, "",
                "gpt-4.1-mini", "gpt-4.1-mini", "", "whisper-1", "", "tts-1", "alloy");
    }

    public static ProviderConfig[] all() { return new ProviderConfig[]{deepseek(), openaiCompatible()}; }

    public static ProviderConfig byName(String n) {
        String normalized = normalizeName(n);
        for (ProviderConfig p : all()) if (p.name.equals(normalized)) return p;
        return openaiCompatible();
    }

    public static String normalizeName(String n) {
        if (n == null) return PROVIDER_OPENAI_COMPAT;
        String s = n.trim();
        String lower = s.toLowerCase(java.util.Locale.US);
        if (lower.contains("deepseek")) return PROVIDER_DEEPSEEK;
        if (lower.contains("openai")) return PROVIDER_OPENAI_COMPAT;
        return PROVIDER_OPENAI_COMPAT;
    }

    /** 把预设写入设置；语音相关字段仅在用户尚未填过时覆盖。 */
    public static void apply(Context ctx, ProviderConfig p) {
        Prefs.put(ctx, Prefs.K_PROVIDER, p.name);
        if (!p.baseUrl.isEmpty()) Prefs.put(ctx, Prefs.K_BASE_URL, p.baseUrl);
        else if (PROVIDER_OPENAI_COMPAT.equals(p.name)
                && DEEPSEEK_BASE_URL.equals(Prefs.get(ctx, Prefs.K_BASE_URL, "").trim())) {
            Prefs.put(ctx, Prefs.K_BASE_URL, "");
        }
        Prefs.put(ctx, Prefs.K_TEXT_BASE_URL, "");
        Prefs.put(ctx, Prefs.K_VISION_BASE_URL, "");
        Prefs.put(ctx, Prefs.K_TEXT_MODEL, p.textModel);
        Prefs.put(ctx, Prefs.K_VISION_MODEL, p.visionModel);
        if (Prefs.get(ctx, Prefs.K_ASR_URL, "").isEmpty()) Prefs.put(ctx, Prefs.K_ASR_URL, p.asrUrl);
        if (Prefs.get(ctx, Prefs.K_ASR_MODEL, "").isEmpty()) Prefs.put(ctx, Prefs.K_ASR_MODEL, p.asrModel);
        if (Prefs.get(ctx, Prefs.K_TTS_URL, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_URL, p.ttsUrl);
        if (Prefs.get(ctx, Prefs.K_TTS_MODEL, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_MODEL, p.ttsModel);
        if (Prefs.get(ctx, Prefs.K_TTS_VOICE, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_VOICE, p.ttsVoice);
    }
}
