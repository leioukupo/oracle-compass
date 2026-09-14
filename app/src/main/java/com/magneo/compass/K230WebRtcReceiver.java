package com.magneo.compass;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
    public interface Listener {
        void onState(String state, String detail);
        void onFirstFrame();
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long HTTP_TIMEOUT_MS = 1200L;
    private static final long RECONNECT_MS = 1000L;

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
    private String host = "";
    private int generation;
    private boolean firstFrame;
    private boolean reconnectScheduled;
    private CountDownLatch iceGathered = new CountDownLatch(1);

    public K230WebRtcReceiver(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Must be called on the UI thread before start(). */
    public void setRenderer(SurfaceViewRenderer value) {
        renderer = value;
        if (renderer != null && egl != null) {
            renderer.init(egl.getEglBaseContext(), null);
            renderer.setEnableHardwareScaler(true);
            renderer.setMirror(false);
            renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        }
    }

    /** Initializes the WebRTC factory; call from the Activity/UI thread. */
    public void initialize() {
        ensureFactory();
    }

    public EglBase.Context eglContext() {
        return egl == null ? null : egl.getEglBaseContext();
    }

    public void start(String targetHost) {
        final String next = targetHost == null ? "" : targetHost.trim();
        if (next.length() == 0) {
            publish("idle", "等待 K230 地址");
            return;
        }
        final String previous = host;
        stopPeerOnly();
        final int run = ++generation;
        host = next;
        stopped.set(false);
        try {
            worker.execute(() -> {
                if (previous != null && previous.length() > 0) postClose(previous);
                connect(run, next);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            publish("error", "WebRTC 线程已退出");
        }
    }

    public void stop() {
        stopped.set(true);
        ++generation;
        stopPeerOnly();
        try {
            worker.execute(() -> postClose(host));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // dispose() may have already shut down the worker.
        }
        publish("stopped", "");
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
        }
    }

    private void ensureFactory() {
        synchronized (peerLock) {
            if (factory != null) return;
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(context).setEnableInternalTracer(false).createInitializationOptions());
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new org.webrtc.SoftwareVideoEncoderFactory())
                    .setVideoDecoderFactory(new org.webrtc.DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                    .createPeerConnectionFactory();
            if (renderer != null) {
                renderer.init(egl.getEglBaseContext(), null);
                renderer.setEnableHardwareScaler(true);
                renderer.setMirror(false);
                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            }
        }
    }

    private void connect(final int run, final String target) {
        if (stopped.get() || run != generation) return;
        publish("connecting", target);
        try {
            ensureFactory();
            JSONObject offerJson = getJson("http://" + target + ":8080/api/webrtc/offer");
            final String sdp = offerJson.optString("sdp", "");
            if (sdp.length() == 0) throw new IOException("K230 offer 为空");

            final CountDownLatch remoteSet = new CountDownLatch(1);
            final String[] remoteError = {""};
            PeerConnection created = createPeer(run);
            if (created == null) throw new IOException("WebRTC PeerConnection 创建失败");
            created.setRemoteDescription(new SdpObserver() {
                @Override public void onSetSuccess() { remoteSet.countDown(); }
                @Override public void onSetFailure(String error) { remoteError[0] = error; remoteSet.countDown(); }
                @Override public void onCreateSuccess(SessionDescription sd) {}
                @Override public void onCreateFailure(String error) {}
            }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
            if (!remoteSet.await(1500L, TimeUnit.MILLISECONDS)) throw new IOException("设置 K230 offer 超时");
            if (remoteError[0].length() > 0) throw new IOException("设置 K230 offer 失败: " + remoteError[0]);

            final CountDownLatch answerSet = new CountDownLatch(1);
            final String[] answerError = {""};
            created.createAnswer(new SdpObserver() {
                @Override public void onCreateSuccess(final SessionDescription answer) {
                    created.setLocalDescription(new SdpObserver() {
                        @Override public void onSetSuccess() { answerSet.countDown(); }
                        @Override public void onSetFailure(String error) { answerError[0] = error; answerSet.countDown(); }
                        @Override public void onCreateSuccess(SessionDescription sd) {}
                        @Override public void onCreateFailure(String error) {}
                    }, answer);
                }
                @Override public void onCreateFailure(String error) { answerError[0] = error; answerSet.countDown(); }
                @Override public void onSetSuccess() {}
                @Override public void onSetFailure(String error) {}
            }, new MediaConstraints());
            if (!answerSet.await(1500L, TimeUnit.MILLISECONDS)) throw new IOException("创建 answer 超时");
            if (answerError[0].length() > 0) throw new IOException("创建 answer 失败: " + answerError[0]);

            // K230 uses non-trickle LAN signaling: wait until all candidates
            // are embedded in the local SDP before posting the answer.
            iceGathered.await(1500L, TimeUnit.MILLISECONDS);
            SessionDescription local = created.getLocalDescription();
            if (local == null) throw new IOException("本地 answer 为空");
            if (stopped.get() || run != generation) return;
            JSONObject answer = new JSONObject();
            answer.put("type", "answer");
            answer.put("sdp", local.description);
            postJson("http://" + target + ":8080/api/webrtc/answer", answer);
            if (!stopped.get() && run == generation) publish("connected", target);
        } catch (Exception e) {
            if (stopped.get() || run != generation) return;
            publish("error", e.getMessage() == null ? e.toString() : e.getMessage());
            scheduleReconnect(run, target);
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
                @Override public void onIceCandidate(IceCandidate candidate) {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) iceGathered.countDown();
                }
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    if ((s == PeerConnection.IceConnectionState.FAILED ||
                            s == PeerConnection.IceConnectionState.DISCONNECTED) &&
                            !stopped.get() && run == generation) {
                        publish("reconnecting", s.toString());
                        scheduleReconnect(run, host);
                    }
                }
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onAddStream(MediaStream s) {}
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(org.webrtc.DataChannel d) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                    attach(receiver.track());
                }
                @Override public void onRemoveTrack(RtpReceiver receiver) {}
                @Override public void onTrack(RtpTransceiver transceiver) {
                    attach(transceiver.getReceiver().track());
                }
            });
            if (peer == null) return null;
            try {
                peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        new RtpTransceiver.RtpTransceiverInit(
                                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            } catch (Exception ignored) {}
            return peer;
        }
    }

    private void attach(MediaStreamTrack track) {
        if (!(track instanceof VideoTrack)) return;
        final VideoTrack next = (VideoTrack) track;
        final VideoSink nextSink = frame -> {
            SurfaceViewRenderer r = renderer;
            if (r != null) r.onFrame(frame);
            if (!firstFrame) {
                firstFrame = true;
                if (listener != null) main.post(listener::onFirstFrame);
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
            stopPeerOnly();
            worker.execute(() -> {
                postClose(target);
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

    private void postJson(String url, JSONObject obj) throws Exception {
        Request request = new Request.Builder().url(url)
                .post(RequestBody.create(JSON, obj.toString())).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        }
    }

    private void postClose(String target) {
        if (target == null || target.length() == 0) return;
        try { postJson("http://" + target + ":8080/api/webrtc/close", new JSONObject()); }
        catch (Exception ignored) {}
    }

    private void publish(final String state, final String detail) {
        if (listener != null) main.post(() -> listener.onState(state, detail == null ? "" : detail));
    }
}
