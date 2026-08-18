package com.magneo.compass.voice;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** WebSocket client for FunASR runtime 2-pass streaming protocol. */
class FunAsrStreamingClient {
    private static final String TAG = "FunAsrStreaming";
    private static final int NORMAL_CLOSE = 1000;

    interface Listener {
        void onOpen();
        void onPartial(String text);
        void onFinal(String text);
        void onError(String msg);
        void onClosed();
    }

    private final String wsUrl;
    private final Listener listener;
    private final OkHttpClient client;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ArrayDeque<byte[]> pendingPcm = new ArrayDeque<>();
    private static final int MAX_PENDING_BYTES = 320000;

    private volatile WebSocket ws;
    private volatile boolean connected;
    private volatile boolean readyForAudio;
    private volatile boolean finishRequested;
    private int pendingBytes;
    private int utteranceSeq;

    FunAsrStreamingClient(String rawUrl, Listener listener) {
        this.wsUrl = normalizeWsUrl(rawUrl);
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    static boolean isStreamingUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String s = rawUrl.trim().toLowerCase(Locale.US);
        if (s.startsWith("ws://") || s.startsWith("wss://") || s.contains(":10096")) {
            return true;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false;

        // FunASR behind an FRP TCP tunnel usually has a different public port.
        // A host-root HTTP URL is therefore treated as the same WebSocket service;
        // explicit OpenAI-compatible paths remain on the multipart fallback.
        try {
            String path = Uri.parse(s).getPath();
            String p = path == null ? "" : path;
            if (p.contains("/audio/transcriptions")
                    || p.equals("/v1") || p.startsWith("/v1/")
                    || p.contains("/openai")) {
                return false;
            }
            return p.isEmpty() || p.equals("/");
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String normalizeWsUrl(String rawUrl) {
        String s = rawUrl == null ? "" : rawUrl.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("http://")) return "ws://" + s.substring("http://".length());
        if (s.startsWith("https://")) return "wss://" + s.substring("https://".length());
        if (!s.startsWith("ws://") && !s.startsWith("wss://")) return "ws://" + s;
        return s;
    }

    void connect() {
        if (closed.get() || connected || !connecting.compareAndSet(false, true)) return;
        Request req = new Request.Builder().url(wsUrl).build();
        client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                ws = webSocket;
                connected = true;
                readyForAudio = false;
                connecting.set(false);
                sendStart();
                Log.i(TAG, "FunASR WS connected " + wsUrl);
                if (listener != null) listener.onOpen();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(NORMAL_CLOSE, null);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                readyForAudio = false;
                connecting.set(false);
                if (ws == webSocket) ws = null;
                if (listener != null) listener.onClosed();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                readyForAudio = false;
                connecting.set(false);
                if (ws == webSocket) ws = null;
                String msg = t == null ? "unknown" : t.getMessage();
                Log.w(TAG, "FunASR WS failed: " + msg);
                if (listener != null) listener.onError(msg == null ? "FunASR 连接失败" : msg);
            }
        });
    }

    synchronized void sendPcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        WebSocket w = ws;
        if (!connected || w == null) {
            if (!closed.get() && pendingBytes + pcm.length <= MAX_PENDING_BYTES) {
                pendingPcm.add(pcm);
                pendingBytes += pcm.length;
            }
            connect();
            return;
        }
        if (!readyForAudio) {
            if (!closed.get() && pendingBytes + pcm.length <= MAX_PENDING_BYTES) {
                pendingPcm.add(pcm);
                pendingBytes += pcm.length;
            }
            return;
        }
        try {
            w.send(ByteString.of(pcm));
        } catch (Throwable t) {
            Log.w(TAG, "send pcm", t);
        }
    }

    void finishUtterance() {
        finishRequested = true;
        WebSocket w = ws;
        if (connected && w != null && readyForAudio) {
            sendFinish(w);
        }
    }

    void close() {
        closed.set(true);
        connected = false;
        readyForAudio = false;
        connecting.set(false);
        synchronized (this) {
            pendingPcm.clear();
            pendingBytes = 0;
        }
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try { w.close(NORMAL_CLOSE, "bye"); } catch (Throwable ignored) {}
        }
    }

    private synchronized void sendStart() {
        WebSocket w = ws;
        if (w == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("mode", "2pass");
            o.put("wav_name", "oracle-" + System.currentTimeMillis() + "-" + (++utteranceSeq));
            o.put("wav_format", "pcm");
            o.put("audio_fs", 16000);
            o.put("is_speaking", true);
            o.put("chunk_size", new org.json.JSONArray("[5,10,5]"));
            o.put("chunk_interval", 10);
            o.put("encoder_chunk_look_back", 4);
            o.put("decoder_chunk_look_back", 0);
            o.put("itn", true);
            w.send(o.toString());
            readyForAudio = true;
            while (!pendingPcm.isEmpty() && connected && !closed.get()) {
                byte[] pcm = pendingPcm.removeFirst();
                pendingBytes -= pcm.length;
                w.send(ByteString.of(pcm));
            }
            if (finishRequested) sendFinish(w);
        } catch (Throwable t) {
            Log.w(TAG, "send start", t);
        }
    }

    private void sendFinish(WebSocket w) {
        try {
            JSONObject o = new JSONObject();
            o.put("is_speaking", false);
            w.send(o.toString());
            readyForAudio = false;
        } catch (Throwable t) {
            Log.w(TAG, "finish utterance", t);
        }
    }

    private void handleMessage(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String result = o.optString("text", "").trim();
            String mode = o.optString("mode", "");
            boolean online = mode.contains("online");
            boolean isFinal = mode.contains("offline")
                    || (!online && o.optBoolean("is_final", false));
            if (listener == null) return;
            if (isFinal) {
                listener.onFinal(result);
            } else if (!result.isEmpty()) {
                listener.onPartial(result);
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse message: " + text, t);
        }
    }

}
