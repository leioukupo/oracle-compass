package com.magneo.compass;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Receives the K230 offer as a LAN WebRTC video stream. Control stays UDP. */
public final class K230WebRtcReceiver {
    private static final String TAG = "K230WebRtc";
    public interface Listener {
        void onState(String state, String detail, int generation);
        void onFirstFrame(int generation);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    // SDP/ICE are control-plane operations; allowing a couple of seconds here
    // prevents a fragmented answer on Android 5.1 from being mistaken for a
    // dead K230.  Video packets never use this client.
    private static final long HTTP_TIMEOUT_MS = 3000L;
    private static final long RECONNECT_MS = 1000L;
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private static final class WebRtcUnavailableException extends IOException {
        WebRtcUnavailableException(String message) { super(message); }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "k230-webrtc");
        t.setDaemon(true);
        return t;
    });
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build();
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final Object peerLock = new Object();

    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoTrack videoTrack;
    private VideoSink sink;
    private SurfaceViewRenderer renderer;
    private boolean rendererInitialized;
    private String host = "";
    private volatile int generation;
    private boolean firstFrame;
    private boolean reconnectScheduled;
    private int connectAttempts;
    private volatile int remoteSession;
    private CountDownLatch iceGathered = new CountDownLatch(1);

    /**
     * Older K230 CanMV images omit packetization-mode from the H.264 fmtp
     * attribute. Android WebRTC rejects that offer before it can create an
     * answer, although the encoder actually sends packetized (mode 1) NALs.
     * Normalize only H.264 fmtp lines and leave all other SDP untouched.
     * Package visibility also lets a JVM unit test cover old-device offers.
     */
    static String normalizeH264Offer(String sdp) {
        if (sdp == null || sdp.length() == 0) return sdp;
        final boolean crlf = sdp.contains("\r\n");
        String[] lines = sdp.split("\\r?\\n", -1);
        Set<String> h264Payloads = new HashSet<>();
        for (String line : lines) {
            if (!line.startsWith("a=rtpmap:")) continue;
            int space = line.indexOf(' ');
            if (space <= "a=rtpmap:".length()) continue;
            String payload = line.substring("a=rtpmap:".length(), space).trim();
            String codec = line.substring(space + 1).trim();
            int slash = codec.indexOf('/');
            String name = slash >= 0 ? codec.substring(0, slash) : codec;
            if ("H264".equalsIgnoreCase(name)) h264Payloads.add(payload);
        }
        if (h264Payloads.isEmpty()) h264Payloads.add("96");
        StringBuilder out = new StringBuilder(sdp.length() + 32);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (String payload : h264Payloads) {
                String prefix = "a=fmtp:" + payload + " ";
                if (!line.startsWith(prefix)) continue;
                String params = line.substring(prefix.length());
                if (params.toLowerCase(Locale.US).contains("packetization-mode=")) break;
                String lower = params.toLowerCase(Locale.US);
                int profile = lower.indexOf("profile-level-id=");
                if (profile >= 0) {
                    int end = params.indexOf(';', profile);
                    if (end < 0) end = params.length();
                    line = prefix + params.substring(0, end)
                            + ";packetization-mode=1" + params.substring(end);
                } else {
                    line = prefix + "packetization-mode=1;" + params;
                }
                changed = true;
                break;
            }
            if (i > 0) out.append(crlf ? "\r\n" : "\n");
            out.append(line);
        }
        if (changed) Log.i(TAG, "normalized H264 offer: packetization-mode=1");
        return out.toString();
    }

    public K230WebRtcReceiver(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Must be called on the UI thread before start(). */
    public void setRenderer(SurfaceViewRenderer value) {
        renderer = value;
        if (renderer != null && egl != null && !rendererInitialized) {
            initializeRenderer(egl.getEglBaseContext());
        }
    }

    /** Initializes the WebRTC factory; call from the Activity/UI thread. */
    public void initialize() {
        ensureFactory();
    }

    public EglBase.Context eglContext() {
        return egl == null ? null : egl.getEglBaseContext();
    }

    public int start(String targetHost) {
        final String next = targetHost == null ? "" : targetHost.trim();
        final int run = ++generation;
        if (next.length() == 0) {
            stopped.set(true);
            stopPeerOnly();
            publish("idle", "等待 K230 地址", run);
            return run;
        }
        final String previous = host;
        final int previousSession = remoteSession;
        stopPeerOnly();
        synchronized (peerLock) { reconnectScheduled = false; }
        host = next;
        connectAttempts = 0;
        stopped.set(false);
        try {
            worker.execute(() -> {
                // stop() already queued the remote close for the same host
                // during pause. Sending a second close immediately on resume
                // can wedge older CanMV native PeerConnection builds and
                // leaves the K230 HTTP/RTSP listeners unavailable.
                if (previous != null && previous.length() > 0 && !previous.equals(next)) {
                    postClose(previous, previousSession);
                }
                connect(run, next);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            publish("error", "WebRTC 线程已退出", run);
        }
        return run;
    }

    public void stop() {
        boolean wasStopped = stopped.getAndSet(true);
        final int run = ++generation;
        final String target = host;
        final int session = remoteSession;
        synchronized (peerLock) { reconnectScheduled = false; }
        stopPeerOnly();
        if (!wasStopped) {
            try {
                worker.execute(() -> postClose(target, session));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // dispose() may have already shut down the worker.
            }
        }
        publish("stopped", "", run);
    }

    public void dispose() {
        stop();
        worker.shutdownNow();
        synchronized (peerLock) {
            if (factory != null) {
                try { factory.dispose(); } catch (Exception ignored) {}
                factory = null;
            }
            if (egl != null) {
                try { egl.release(); } catch (Exception ignored) {}
                egl = null;
            }
        }
        if (renderer != null) {
            try { renderer.release(); } catch (Exception ignored) {}
            rendererInitialized = false;
        }
    }

    private void ensureFactory() {
        EglBase.Context rendererContext;
        synchronized (peerLock) {
            if (factory == null) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                        .builder(context).setEnableInternalTracer(false).createInitializationOptions());
                egl = EglBase.create();
                factory = PeerConnectionFactory.builder()
                        // This peer is receive-only. Do not provision a camera,
                        // microphone, local track or software video encoder.
                        .setVideoDecoderFactory(new org.webrtc.DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                        .createPeerConnectionFactory();
            }
            rendererContext = egl.getEglBaseContext();
        }
        if (renderer != null && !rendererInitialized) {
            initializeRenderer(rendererContext);
        }
    }

    private void initializeRenderer(final EglBase.Context rendererContext) {
        Runnable init = () -> {
            if (renderer == null || rendererInitialized) return;
            renderer.init(rendererContext, null);
            renderer.setEnableHardwareScaler(true);
            renderer.setMirror(false);
            renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            rendererInitialized = true;
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            init.run();
            return;
        }
        final CountDownLatch ready = new CountDownLatch(1);
        main.post(() -> {
            try { init.run(); }
            finally { ready.countDown(); }
        });
        try { ready.await(1200L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void connect(final int run, final String target) {
        if (stopped.get() || run != generation) return;
        connectAttempts++;
        publish("connecting", target, run);
        String stage = "status";
        try {
            // Do not load/initialize the native WebRTC stack on a K230 image
            // that advertises the HTTP endpoint but has no `webrtc` module.
            // This is important on API 22 devices: initializing the native
            // factory can crash the process before the RTSP fallback gets a
            // chance to run.  The K230 status endpoint remains available even
            // when WebRTC itself is disabled.
            String statusUrl = "http://" + target + ":8080/api/webrtc/status";
            Log.i(TAG, "[" + run + "] status request " + statusUrl);
            JSONObject capability = getJson(statusUrl);
            String capabilityError = capability.optString("last_error", "");
            String capabilityState = capability.optString("state", "");
            Log.i(TAG, "[" + run + "] status available=" + capability.optInt("available", 0)
                    + " state=" + capabilityState + " session="
                    + capability.optInt("session", 0));
            if ((capability.has("available") && capability.optInt("available", 0) == 0) ||
                    "disabled".equalsIgnoreCase(capabilityState) ||
                    ("error".equalsIgnoreCase(capabilityState) &&
                            capability.optInt("session", 0) == 0) ||
                    capabilityError.toLowerCase(java.util.Locale.US).contains("unavailable")) {
                throw new WebRtcUnavailableException("K230 WebRTC 不可用，使用 RTSP");
            }
            stage = "factory";
            ensureFactory();
            stage = "offer";
            String offerUrl = "http://" + target + ":8080/api/webrtc/offer";
            Log.i(TAG, "[" + run + "] offer request " + offerUrl);
            JSONObject offerJson = getJson(offerUrl);
            final String rawSdp = offerJson.optString("sdp", "");
            if (rawSdp.length() == 0) throw new IOException("K230 offer 为空");
            final String sdp = normalizeH264Offer(rawSdp);
            Log.i(TAG, "[" + run + "] offer received session="
                    + offerJson.optInt("session", 0) + " sdp=" + rawSdp.length()
                    + " normalized=" + !rawSdp.equals(sdp));
            remoteSession = offerJson.optInt("session", 0);

            stage = "remote_sdp";
            final CountDownLatch remoteSet = new CountDownLatch(1);
            final String[] remoteError = {""};
            PeerConnection created = createPeer(run);
            if (created == null) throw new IOException("WebRTC PeerConnection 创建失败");
            created.setRemoteDescription(new SdpObserver() {
                @Override public void onSetSuccess() {
                    Log.i(TAG, "[" + run + "] remote SDP set success");
                    remoteSet.countDown();
                }
                @Override public void onSetFailure(String error) {
                    Log.e(TAG, "[" + run + "] remote SDP set failure: " + error);
                    remoteError[0] = error; remoteSet.countDown();
                }
                @Override public void onCreateSuccess(SessionDescription sd) {}
                @Override public void onCreateFailure(String error) {}
            }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
            if (!remoteSet.await(3000L, TimeUnit.MILLISECONDS)) {
                throw new IOException("WebRTC SDP 等待超时");
            }
            if (remoteError[0].length() > 0) {
                throw new IOException("WebRTC SDP 失败: " + remoteError[0]);
            }
            Log.i(TAG, "[" + run + "] remote SDP accepted");

            stage = "answer";
            final CountDownLatch answerSet = new CountDownLatch(1);
            final String[] answerError = {""};
            created.createAnswer(new SdpObserver() {
                @Override public void onCreateSuccess(final SessionDescription answer) {
                    Log.i(TAG, "[" + run + "] answer created sdp="
                            + (answer == null || answer.description == null
                            ? 0 : answer.description.length()));
                    created.setLocalDescription(new SdpObserver() {
                        @Override public void onSetSuccess() {
                            Log.i(TAG, "[" + run + "] local answer set");
                            answerSet.countDown();
                        }
                        @Override public void onSetFailure(String error) {
                            Log.e(TAG, "[" + run + "] local answer set failure: " + error);
                            answerError[0] = error; answerSet.countDown();
                        }
                        @Override public void onCreateSuccess(SessionDescription sd) {}
                        @Override public void onCreateFailure(String error) {}
                    }, answer);
                }
                @Override public void onCreateFailure(String error) {
                    Log.e(TAG, "[" + run + "] answer create failure: " + error);
                    answerError[0] = error; answerSet.countDown();
                }
                @Override public void onSetSuccess() {}
                @Override public void onSetFailure(String error) {}
            }, new MediaConstraints());
            if (!answerSet.await(3000L, TimeUnit.MILLISECONDS)) {
                throw new IOException("WebRTC answer 创建超时");
            }
            if (answerError[0].length() > 0) {
                throw new IOException("WebRTC answer 创建失败: " + answerError[0]);
            }
            Log.i(TAG, "[" + run + "] answer ready");

            // K230 uses non-trickle LAN signaling: wait until all candidates
            // are embedded in the local SDP before posting the answer.
            stage = "ice";
            Log.i(TAG, "[" + run + "] waiting for ICE gathering");
            // Host-only LAN sessions normally reach COMPLETE quickly.  Some
            // Android 5.1 WebRTC builds never deliver the final callback even
            // though the host candidate is already present in the local SDP;
            // the official CanMV browser client treats its 3 s wait as a
            // deadline rather than a hard negotiation failure.  Continue with
            // the gathered SDP so those devices can still send the answer.
            if (!iceGathered.await(2500L, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "[" + run + "] ICE gathering deadline reached; using current local SDP");
            } else {
                Log.i(TAG, "[" + run + "] ICE gathering complete");
            }
            SessionDescription local = created.getLocalDescription();
            if (local == null) throw new IOException("本地 answer 为空");
            if (stopped.get() || run != generation) return;
            JSONObject answer = new JSONObject();
            answer.put("type", "answer");
            answer.put("sdp", local.description);
            if (remoteSession > 0) answer.put("session", remoteSession);
            stage = "answer_post";
            String answerUrl = "http://" + target + ":8080/api/webrtc/answer";
            Log.i(TAG, "[" + run + "] answer POST " + answerUrl + " sdp="
                    + (local.description == null ? 0 : local.description.length()));
            int answerCode = postJson(answerUrl, answer);
            Log.i(TAG, "[" + run + "] answer POST HTTP " + answerCode);
            if (!stopped.get() && run == generation) {
                connectAttempts = 0;
                publish("connected", target, run);
                Log.i(TAG, "[" + run + "] signaling connected");
            }
        } catch (WebRtcUnavailableException e) {
            if (stopped.get() || run != generation) return;
            publish("unavailable", e.getMessage(), run);
        } catch (Exception e) {
            if (stopped.get() || run != generation) return;
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            if ("remote_sdp".equals(stage) && !detail.startsWith("WebRTC SDP")) {
                detail = "WebRTC SDP 失败: " + detail;
            } else if ("answer".equals(stage) && !detail.startsWith("WebRTC answer")) {
                detail = "WebRTC answer 创建失败: " + detail;
            } else if ("ice".equals(stage) && !detail.startsWith("WebRTC ICE")) {
                detail = "WebRTC ICE 等待超时: " + detail;
            } else if ("answer_post".equals(stage) && !detail.startsWith("WebRTC answer")) {
                detail = "WebRTC answer POST 失败: " + detail;
            }
            Log.e(TAG, "[" + run + "] stage=" + stage + " attempt="
                    + connectAttempts + " failed: " + detail, e);
            if (connectAttempts >= MAX_CONNECT_ATTEMPTS) {
                publish("error", detail, run);
            } else {
                publish("reconnecting", detail, run);
                scheduleReconnect(run, target);
            }
        } catch (Throwable e) {
            // Broken vendor WebRTC libraries on API 22 can throw a LinkageError
            // instead of an Exception. Convert that failure to the normal RTSP
            // fallback path instead of allowing the car-control Activity to die.
            if (stopped.get() || run != generation) return;
            Log.e(TAG, "[" + run + "] stage=" + stage + " fatal WebRTC failure", e);
            publish("error", e.toString(), run);
        }
    }

    private PeerConnection createPeer(final int run) {
        synchronized (peerLock) {
            if (factory == null) return null;
            iceGathered = new CountDownLatch(1);
            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(
                    new ArrayList<PeerConnection.IceServer>());
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
                @Override public void onIceCandidate(IceCandidate candidate) {
                    // The null candidate is the end-of-gathering marker on
                    // older libwebrtc builds.  API 22 devices have been seen
                    // to deliver it without a matching state callback.
                    if (candidate == null) iceGathered.countDown();
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                    Log.i(TAG, "[" + run + "] ICE gathering=" + s);
                    if (s == PeerConnection.IceGatheringState.COMPLETE) iceGathered.countDown();
                }
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    Log.i(TAG, "[" + run + "] ICE connection=" + s);
                    if ((s == PeerConnection.IceConnectionState.FAILED ||
                            s == PeerConnection.IceConnectionState.DISCONNECTED) &&
                            !stopped.get() && run == generation) {
                        publish("reconnecting", s.toString(), run);
                        scheduleReconnect(run, host);
                    }
                }
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onAddStream(MediaStream s) {
                    // Keep compatibility with WebRTC builds that deliver a
                    // remote track through the legacy stream callback
                    // instead of Unified Plan's onTrack callback.
                    if (s != null && s.videoTracks != null) {
                        for (VideoTrack track : s.videoTracks) attach(run, track);
                    }
                }
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(org.webrtc.DataChannel d) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                    attach(run, receiver.track());
                }
                @Override public void onRemoveTrack(RtpReceiver receiver) {}
                @Override public void onTrack(RtpTransceiver transceiver) {
                    attach(run, transceiver.getReceiver().track());
                }
            });
            if (peer == null) return null;
            // Do not add a local transceiver before applying K230's offer.
            // The offer already contains the single send-only video m-line;
            // Unified Plan creates its receive transceiver automatically.
            // Adding another RECV_ONLY transceiver here produces a second
            // m-line in the Android answer on some WebRTC 114 builds, which
            // leaves the K230 peer connected but no video track delivered.
            return peer;
        }
    }

    private void attach(final int run, MediaStreamTrack track) {
        if (!(track instanceof VideoTrack)) return;
        final VideoTrack next = (VideoTrack) track;
        final VideoSink nextSink = frame -> {
            if (stopped.get() || run != generation) return;
            SurfaceViewRenderer r = renderer;
            if (r != null) r.onFrame(frame);
            if (!firstFrame) {
                firstFrame = true;
                if (listener != null) {
                    main.post(() -> {
                        if (!stopped.get() && run == generation) listener.onFirstFrame(run);
                    });
                }
            }
        };
        VideoTrack old = videoTrack;
        if (old != null && sink != null) old.removeSink(sink);
        videoTrack = next;
        sink = nextSink;
        next.addSink(nextSink);
    }

    private void stopPeerOnly() {
        synchronized (peerLock) {
            VideoTrack old = videoTrack;
            VideoSink oldSink = sink;
            videoTrack = null;
            sink = null;
            if (old != null && oldSink != null) {
                try { old.removeSink(oldSink); } catch (Exception ignored) {}
            }
            PeerConnection oldPeer = peer;
            peer = null;
            if (oldPeer != null) {
                try { oldPeer.close(); } catch (Exception ignored) {}
                try { oldPeer.dispose(); } catch (Exception ignored) {}
            }
            firstFrame = false;
        }
    }

    private void scheduleReconnect(final int run, final String target) {
        synchronized (peerLock) {
            if (reconnectScheduled) return;
            reconnectScheduled = true;
        }
        main.postDelayed(() -> {
            synchronized (peerLock) { reconnectScheduled = false; }
            if (stopped.get() || run != generation) return;
            final int oldSession = remoteSession;
            stopPeerOnly();
            worker.execute(() -> {
                postClose(target, oldSession);
                connect(run, target);
            });
        }, RECONNECT_MS);
    }

    private JSONObject getJson(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            return new JSONObject(response.body().string());
        }
    }

    private int postJson(String url, JSONObject obj) throws Exception {
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create(JSON, obj.toString())).build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (!response.isSuccessful()) throw new IOException("HTTP " + code);
            return code;
        }
    }

    private void postClose(String target, int session) {
        if (target == null || target.length() == 0) return;
        try {
            JSONObject close = new JSONObject();
            if (session > 0) close.put("session", session);
            postJson("http://" + target + ":8080/api/webrtc/close", close);
        }
        catch (Exception ignored) {}
    }

    private void publish(final String state, final String detail, final int run) {
        if (listener != null) main.post(() -> {
            if (run == generation) listener.onState(state, detail == null ? "" : detail, run);
        });
    }
}
