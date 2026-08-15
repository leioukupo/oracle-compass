package com.magneo.compass.cam;

import android.content.Context;
import android.util.Log;

import org.webrtc.CapturerObserver;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.NV21Buffer;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;

/** WebRTC 摄像头推流：网页 RTCPeerConnection 播放。信令走本地轮询（offer/answer）。 */
public class WebRtcStreamer {
    private static final String TAG = "WebRtc";
    private static volatile WebRtcStreamer inst;

    private volatile PeerConnectionFactory factory;
    private volatile PeerConnection pc;
    private volatile VideoSource videoSource;
    private volatile CapturerObserver observer;
    private volatile VideoTrack videoTrack;
    private EglBase eglBase;

    private volatile String answerOut = "";
    private volatile String state = "idle";
    private volatile String lastError = "";

    public static synchronized WebRtcStreamer get() {
        if (inst == null) inst = new WebRtcStreamer();
        return inst;
    }

    public synchronized void init(Context ctx) {
        if (factory != null) return;
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx)
                    .setEnableInternalTracer(false).createInitializationOptions());
            eglBase = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new org.webrtc.SoftwareVideoEncoderFactory())
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();
        } catch (Throwable t) {
            Log.w(TAG, "init failed", t);
            state = "error";
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
        }
    }

    public String state() { return state; }
    public String error() { return lastError; }
    public String answer() { return answerOut; }
    public void clear() { answerOut = ""; }

    /** 注入一帧 NV21（由采集线程调用）。 */
    public void feedFrame(byte[] nv21, int w, int h) {
        CapturerObserver o = observer;
        if (o == null) return;
        try {
            VideoFrame frame = new VideoFrame(new NV21Buffer(nv21, w, h, null), 0, System.nanoTime());
            o.onFrameCaptured(frame);
        } catch (Throwable t) {
            Log.w(TAG, "feedFrame failed", t);
        }
    }

    /** 注入一帧原始预览帧（支持 NV21 / YV12 / NV12，内部按需转 NV21）。 */
    public void feedFrameRaw(byte[] raw, int w, int h, CameraSourceFormat fmt) {
        if (fmt == CameraSourceFormat.NV21) {
            feedFrame(raw, w, h);
            return;
        }
        byte[] nv21 = new byte[w * h * 3 / 2];
        if (fmt == CameraSourceFormat.YV12) {
            H264Encoder.yv12ToNv21(raw, nv21, w, h);
        } else {
            H264Encoder.nv12ToNv21(raw, nv21, w, h);
        }
        feedFrame(nv21, w, h);
    }

    /** 网页 POST 来的 offer SDP；返回是否受理。 */
    public synchronized boolean handleOffer(String sdp) {
        try {
            teardown();
            if (factory == null) return false;
            videoSource = factory.createVideoSource(false);
            observer = videoSource.getCapturerObserver();
            observer.onCapturerStarted(true);
            videoTrack = factory.createVideoTrack("cam0", videoSource);

            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(new ArrayList<PeerConnection.IceServer>());
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
                @Override public void onIceCandidate(IceCandidate candidate) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState gs) {
                    if (gs == PeerConnection.IceGatheringState.COMPLETE) {
                        try {
                            SessionDescription ld = pc.getLocalDescription();
                            if (ld != null) answerOut = ld.description;
                            else lastError = "本地 SDP 为空";
                        } catch (Exception e) {
                            lastError = "getLocalDescription: " + e.getMessage();
                        }
                    }
                }
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState cs) {
                    if (cs == PeerConnection.IceConnectionState.CONNECTED) state = "connected";
                    else if (cs == PeerConnection.IceConnectionState.FAILED
                            || cs == PeerConnection.IceConnectionState.CLOSED) state = "idle";
                }
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onAddStream(MediaStream s) {}
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(org.webrtc.DataChannel d) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
                @Override public void onRemoveTrack(RtpReceiver r) {}
                @Override public void onTrack(RtpTransceiver t) {}
            });
            pc.addTrack(videoTrack, Collections.singletonList("stream0"));
            answerOut = "";
            state = "connecting";

            pc.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription sd) {}
                @Override public void onSetSuccess() {
                    pc.createAnswer(new SdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription sd) {
                            pc.setLocalDescription(new SdpObserver() {
                                @Override public void onCreateSuccess(SessionDescription sd2) {}
                                @Override public void onSetSuccess() {
                                    // 等 ICE gathering COMPLETE 后 answerOut 就绪
                                }
                                @Override public void onCreateFailure(String err) {}
                                @Override public void onSetFailure(String err) {}
                            }, sd);
                        }
                        @Override public void onCreateFailure(String err) {
                            state = "error";
                            lastError = "createAnswer: " + err;
                        }
                        @Override public void onSetSuccess() {}
                        @Override public void onSetFailure(String err) {
                            state = "error";
                            lastError = "setLocalDescription: " + err;
                        }
                    }, new MediaConstraints());
                }
                @Override public void onCreateFailure(String err) {
                    Log.w(TAG, "createAnswer failed: " + err);
                    state = "error";
                    lastError = "createAnswer: " + err;
                }
                @Override public void onSetFailure(String err) {
                    Log.w(TAG, "setRemoteDescription failed: " + err);
                    state = "error";
                    lastError = "setRemoteDescription: " + err;
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "handleOffer failed", t);
            state = "error";
            lastError = "handleOffer: " + (t.getMessage() == null ? t.toString() : t.getMessage());
            return false;
        }
    }

    public synchronized void teardown() {
        answerOut = "";
        if (observer != null) { try { observer.onCapturerStopped(); } catch (Exception ignored) {} }
        observer = null;
        if (videoTrack != null) { try { videoTrack.dispose(); } catch (Exception ignored) {} }
        videoTrack = null;
        if (videoSource != null) { try { videoSource.dispose(); } catch (Exception ignored) {} }
        videoSource = null;
        if (pc != null) { try { pc.close(); } catch (Exception ignored) {} }
        pc = null;
        if (state.equals("connecting") || state.equals("connected")) state = "idle";
    }

    public void dispose() {
        teardown();
        if (factory != null) { try { factory.dispose(); } catch (Exception ignored) {} }
        factory = null;
        if (eglBase != null) { try { eglBase.release(); } catch (Exception ignored) {} }
        eglBase = null;
    }
}
