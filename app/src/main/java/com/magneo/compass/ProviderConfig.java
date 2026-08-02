package com.magneo.compass;
import android.content.Context;

/** 大模型 Provider 预设（OpenAI 兼容协议）。语音端点独立配置，可留空。 */
public class ProviderConfig {
    public final String name, baseUrl, textModel, visionModel, asrUrl, asrModel, ttsUrl, ttsModel, ttsVoice;

    public ProviderConfig(String name, String baseUrl, String textModel, String visionModel,
                          String asrUrl, String asrModel, String ttsUrl, String ttsModel, String ttsVoice) {
        this.name = name; this.baseUrl = baseUrl; this.textModel = textModel; this.visionModel = visionModel;
        this.asrUrl = asrUrl; this.asrModel = asrModel; this.ttsUrl = ttsUrl; this.ttsModel = ttsModel; this.ttsVoice = ttsVoice;
    }

    public static ProviderConfig qwen() {
        return new ProviderConfig("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus", "qwen-vl-max", "", "whisper-1", "", "qwen-tts", "Cherry");
    }

    public static ProviderConfig deepseek() {
        return new ProviderConfig("DeepSeek", "https://api.deepseek.com/v1",
                "deepseek-chat", "deepseek-chat", "", "whisper-1", "", "", "");
    }

    public static ProviderConfig openai() {
        return new ProviderConfig("OpenAI", "https://api.openai.com/v1",
                "gpt-4.1-mini", "gpt-4.1-mini",
                "https://api.openai.com/v1/audio/transcriptions", "whisper-1",
                "https://api.openai.com/v1/audio/speech", "tts-1", "alloy");
    }

    public static ProviderConfig[] all() { return new ProviderConfig[]{qwen(), deepseek(), openai()}; }

    public static ProviderConfig byName(String n) {
        for (ProviderConfig p : all()) if (p.name.equals(n)) return p;
        return qwen();
    }

    /** 把预设写入设置；语音相关字段仅在用户尚未填过时覆盖。 */
    public static void apply(Context ctx, ProviderConfig p) {
        Prefs.put(ctx, Prefs.K_PROVIDER, p.name);
        Prefs.put(ctx, Prefs.K_BASE_URL, p.baseUrl);
        Prefs.put(ctx, Prefs.K_TEXT_MODEL, p.textModel);
        Prefs.put(ctx, Prefs.K_VISION_MODEL, p.visionModel);
        if (Prefs.get(ctx, Prefs.K_ASR_URL, "").isEmpty()) Prefs.put(ctx, Prefs.K_ASR_URL, p.asrUrl);
        if (Prefs.get(ctx, Prefs.K_ASR_MODEL, "").isEmpty()) Prefs.put(ctx, Prefs.K_ASR_MODEL, p.asrModel);
        if (Prefs.get(ctx, Prefs.K_TTS_URL, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_URL, p.ttsUrl);
        if (Prefs.get(ctx, Prefs.K_TTS_MODEL, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_MODEL, p.ttsModel);
        if (Prefs.get(ctx, Prefs.K_TTS_VOICE, "").isEmpty()) Prefs.put(ctx, Prefs.K_TTS_VOICE, p.ttsVoice);
    }
}
