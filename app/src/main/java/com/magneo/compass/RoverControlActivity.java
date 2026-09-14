package com.magneo.compass;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.magneo.compass.ui.Ui;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import org.webrtc.SurfaceViewRenderer;

/**
 * TALOS rover page.  The video is only a background preview; the raw joy
 * transport remains alive when RTSP cannot be decoded.
 */
public class RoverControlActivity extends BaseActivity implements
        RoverUdpTransport.Listener, RoverStatusClient.Listener {
    public static final String EXTRA_OPEN_SETTINGS = "open_rover_settings";
    private static final long WEBRTC_FALLBACK_DELAY_MS = 7000L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private SurfaceView video;
    private RoverControlView controls;
    private RoverUdpTransport transport;
    private RoverStatusClient statusClient;
    private volatile ExoPlayer mediaPlayer;
    private HandlerThread videoThread;
    private Handler videoWorker;
    private Surface videoSurface;
    private String activeVideoHost = "";
    private volatile boolean resumed;
    private volatile boolean videoStarting;
    private volatile int videoGeneration;
    private SurfaceViewRenderer webRtcView;
    private K230WebRtcReceiver webRtcReceiver;
    private boolean rtspTcpFallback;
    private boolean videoWebRtcFallback;
    private boolean webRtcFirst;
    private long videoConnectStartMs;
    private long videoFirstFrameMs;
    private long videoLastReconnectMs;
    private int videoReconnectCount;
    private int bufferOverrunCount;
    private final Runnable bufferWatchdog = this::checkVideoBuffer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // ExoPlayer may perform network I/O while setting up an RTSP source.
        // Keep all of that work off the UI thread so a slow/unavailable camera
        // cannot trigger NetworkOnMainThreadException or stall the controls.
        videoThread = new HandlerThread("rover-rtsp");
        videoThread.start();
        videoWorker = new Handler(videoThread.getLooper());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // SurfaceView lets MediaCodec render into a dedicated hardware layer.
        // TextureView adds a GPU composition/copy step on this Android 5.1
        // MT6580 device and can leave one or more old frames queued.
        video = new SurfaceView(this);
        video.setBackgroundColor(Color.BLACK);
        video.setClickable(false);
        video.setZOrderMediaOverlay(false);
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                videoSurface = holder.getSurface();
                if (resumed) {
                    String host = activeVideoHost.length() == 0 && transport != null
                            ? transport.activeHost() : activeVideoHost;
                    if (videoWebRtcFallback && host != null && host.length() > 0) {
                        startRtspVideo(host);
                    } else {
                        startVideo(host);
                    }
                }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                videoSurface = holder.getSurface();
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (videoWebRtcFallback) {
                    videoStarting = false;
                    ++videoGeneration;
                }
                ui.removeCallbacks(bufferWatchdog);
                releasePlayerAsync();
                videoSurface = null;
            }
        });
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        webRtcView = new SurfaceViewRenderer(this);
        webRtcView.setVisibility(View.GONE);
        webRtcView.setBackgroundColor(Color.BLACK);
        root.addView(webRtcView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webRtcReceiver = new K230WebRtcReceiver(this, new K230WebRtcReceiver.Listener() {
            @Override public void onState(String state, String detail) {
                if (!resumed || videoWebRtcFallback) return;
                if ("connected".equals(state)) {
                    controls.setVideoText("WebRTC 连接中");
                } else if ("reconnecting".equals(state)) {
                    markVideoReconnect();
                    controls.setVideoText(videoLabel("WebRTC 重连 " + videoReconnectCount));
                } else if ("error".equals(state) || "unavailable".equals(state)) {
                    // RTSP remains available when the current K230 firmware has
                    // no WebRTC module or ICE negotiation fails.
                    videoWebRtcFallback = true;
                    if (webRtcReceiver != null) webRtcReceiver.stop();
                    webRtcView.setVisibility(View.GONE);
                    video.setVisibility(View.VISIBLE);
                    startRtspVideo(activeVideoHost);
                } else {
                    controls.setVideoText("WebRTC " + state);
                }
            }
            @Override public void onFirstFrame() {
                if (resumed && !videoWebRtcFallback) {
                    webRtcFirst = true;
                    videoStarting = false;
                    videoFirstFrameMs = System.currentTimeMillis();
                    long cost = videoConnectStartMs > 0
                            ? videoFirstFrameMs - videoConnectStartMs : 0;
                    controls.setVideoText(videoLabel("WebRTC 在线 · 首帧 " + cost + "ms"));
                }
            }
        });
        webRtcReceiver.setRenderer(webRtcView);
        // Initialize the native WebRTC factory lazily, only after K230's
        // /api/webrtc/status confirms that its current firmware supports it.
        // Unsupported K230 images go straight to the RTSP fallback without
        // loading a native decoder on this older Android device.

        controls = new RoverControlView(this, new RoverControlView.Listener() {
            @Override public void onJoyChanged(int lx, int ly, int rx, int ry,
                                                boolean swL, boolean swR) {
                if (transport != null) transport.setInput(lx, ly, rx, ry, swL, swR);
            }
            @Override public void onStopRequested() {
                if (transport != null) transport.requestStop();
                controls.setTransportText("急停 · 已发送中立帧");
            }
        });
        root.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        transport = new RoverUdpTransport(this, this);
        statusClient = new RoverStatusClient(this);
        controls.setYInverted(Prefs.roverLeftYInverted(this), Prefs.roverRightYInverted(this));
        setContentView(root);

        if (getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            ui.postDelayed(this::showRoverSettings, 280L);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        controls.setYInverted(Prefs.roverLeftYInverted(this), Prefs.roverRightYInverted(this));
        transport.start();
        statusClient.start(transport.activeHost());
        startVideo(activeVideoHost.length() == 0 ? transport.activeHost() : activeVideoHost);
    }

    @Override protected void onPause() {
        resumed = false;
        // Neutralize before closing sockets so K230 sees a safe frame even when
        // Android is backgrounded or the screen is locked.
        if (transport != null) {
            transport.stop();
        }
        if (statusClient != null) statusClient.stop();
        releaseVideo();
        // SurfaceView owns this Surface; surfaceDestroyed() clears it.
        controls.stopAndReset();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onDestroy() {
        releaseVideo();
        if (webRtcReceiver != null) {
            webRtcReceiver.dispose();
            webRtcReceiver = null;
        }
        videoWorker = null;
        if (videoThread != null) {
            videoThread.quitSafely();
            videoThread = null;
        }
        super.onDestroy();
    }

    @Override public void onTargetChanged(final String host) {
        ui.post(() -> {
            if (controls == null) return;
            String shown = host == null || host.length() == 0 ? "等待 K230" : "K230 " + host;
            controls.setTargetText(shown);
            if (statusClient != null) statusClient.setHost(host);
            if (resumed && host != null && !host.equals(activeVideoHost)) startVideo(host);
        });
    }

    @Override public void onTransportState(final String target, final long sent, final long errors,
                                           final float rate, final String error) {
        ui.post(() -> {
            if (controls == null) return;
            String state;
            if (!transport.isRunning()) state = "控制已停止";
            else if (target == null || target.length() == 0) state = "UDP 广播等待 K230";
            else state = String.format(java.util.Locale.US, "UDP %.1fHz · %d 帧", rate, sent);
            if (errors > 0 && error != null && error.length() > 0) state += " · " + error;
            state = wifiLabel() + " · " + state;
            controls.setTransportText(state);
        });
    }

    @Override public void onMode(final String mode) {
        ui.post(() -> {
            if (controls != null) controls.setModeText(modeLabel(mode));
        });
    }

    @Override public void onStatus(final String status) {
        ui.post(() -> {
            if (controls != null) controls.setStatusText(status);
        });
    }

    private String modeLabel(String mode) {
        if (mode == null) return "模式未知";
        String m = mode.toLowerCase(java.util.Locale.US);
        if ("0".equals(m)) return "双臂模式";
        if ("1".equals(m)) return "云台模式";
        if (m.contains("arm") || m.contains("双臂")) return "双臂模式";
        if (m.contains("gimbal") || m.contains("云台")) return "云台模式";
        return mode;
    }

    private String wifiLabel() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) return "Wi-Fi 关闭";
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() < 0) return "Wi-Fi 未连接";
        return "Wi-Fi 已连接";
    }

    private void startVideo(String host) {
        if (!resumed || host == null || host.trim().length() == 0) {
            if (host == null || host.trim().length() == 0) controls.setVideoText("视频等待 K230");
            return;
        }
        final String targetHost = host.trim();
        if (videoStarting && targetHost.equals(activeVideoHost)) {
            // WebRTC does not need the RTSP SurfaceView. If it already failed
            // while that surface was unavailable, retry the pending fallback
            // as soon as SurfaceView becomes available.
            if (videoWebRtcFallback && videoSurface != null && mediaPlayer == null) {
                startRtspVideo(targetHost);
            }
            return;
        }
        activeVideoHost = targetHost;
        final int generation = ++videoGeneration;
        videoConnectStartMs = System.currentTimeMillis();
        videoFirstFrameMs = 0;
        videoLastReconnectMs = 0;
        videoReconnectCount = 0;
        bufferOverrunCount = 0;
        videoStarting = true;
        videoWebRtcFallback = false;
        webRtcFirst = false;
        rtspTcpFallback = false;
        controls.setVideoText("视频连接中");
        releasePlayerAsync();
        if (!Prefs.ROVER_VIDEO_WEBRTC.equals(Prefs.roverVideoMode(this))) {
            videoWebRtcFallback = true;
            if (webRtcReceiver != null) webRtcReceiver.stop();
            video.setVisibility(View.VISIBLE);
            webRtcView.setVisibility(View.GONE);
            startRtspVideo(targetHost);
            return;
        }
        video.setVisibility(View.GONE);
        webRtcView.setVisibility(View.VISIBLE);
        if (webRtcReceiver != null) webRtcReceiver.start(targetHost);
        // A missing WebRTC module is reported by the capability probe. Keep a
        // short deadline for a broken/old peer so the control page still gets
        // a usable RTSP picture without waiting indefinitely.
        ui.postDelayed(() -> {
            if (isVideoCurrent(generation) && !videoWebRtcFallback &&
                    videoStarting && !webRtcFirst) {
                videoWebRtcFallback = true;
                if (webRtcReceiver != null) webRtcReceiver.stop();
                webRtcView.setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                startRtspVideo(targetHost);
            }
        }, WEBRTC_FALLBACK_DELAY_MS);
    }

    private void startRtspVideo(String host) {
        if (!resumed || videoSurface == null || !videoSurface.isValid() ||
                host == null || host.length() == 0) {
            if (controls != null) controls.setVideoText("RTSP 等待画面");
            return;
        }
        final int generation = videoGeneration;
        final Surface surface = videoSurface;
        final String url = Uri.parse("rtsp://" + host + ":" + Prefs.roverRtspPort(this)
                + Prefs.roverRtspPath(this)).toString();
        Handler worker = videoWorker;
        if (worker == null) { videoFailed(generation); return; }
        if (videoConnectStartMs <= 0) videoConnectStartMs = System.currentTimeMillis();
        controls.setVideoText(rtspTcpFallback ? "RTSP TCP 连接中" : "RTSP UDP 连接中");
        worker.post(() -> prepareVideoOnWorker(generation, url, surface, rtspTcpFallback));
    }

    private void prepareVideoOnWorker(final int generation, String url, Surface surface,
                                      final boolean forceTcp) {
        releasePlayerOnWorker();
        if (!isVideoCurrent(generation) || surface == null) return;
        try {
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50, 150, 25, 25)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            DefaultTrackSelector selector = new DefaultTrackSelector(this);
            ExoPlayer mp = new ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .setTrackSelector(selector)
                    .build();
            mediaPlayer = mp;
            mp.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (!isVideoCurrent(generation) || mediaPlayer != mp) return;
                    ui.post(() -> {
                        if (!isVideoCurrent(generation) || mediaPlayer != mp) return;
                        if (state == Player.STATE_READY) {
                            bufferOverrunCount = 0;
                            controls.setVideoText((forceTcp ? "RTSP TCP" : "RTSP UDP") + " 已就绪");
                            ui.removeCallbacks(bufferWatchdog);
                            ui.post(bufferWatchdog);
                        } else if (state == Player.STATE_BUFFERING) {
                            controls.setVideoText("RTSP 缓冲");
                        }
                    });
                }
                @Override public void onRenderedFirstFrame() {
                    if (!isVideoCurrent(generation) || mediaPlayer != mp) return;
                    ui.post(() -> {
                        if (!isVideoCurrent(generation) || mediaPlayer != mp) return;
                        videoStarting = false;
                        if (videoFirstFrameMs == 0) {
                            videoFirstFrameMs = System.currentTimeMillis();
                        }
                        long cost = videoConnectStartMs > 0
                                ? videoFirstFrameMs - videoConnectStartMs : 0;
                        controls.setVideoText(videoLabel((forceTcp ? "RTSP TCP" : "RTSP UDP")
                                + " 在线 · 首帧 " + cost + "ms"));
                    });
                }
                @Override public void onPlayerError(PlaybackException error) {
                    if (!isVideoCurrent(generation) || mediaPlayer != mp) return;
                    if (!forceTcp) {
                        rtspTcpFallback = true;
                        markVideoReconnect();
                        ui.post(() -> startRtspVideo(activeVideoHost));
                    } else {
                        ui.post(() -> videoFailed(generation));
                    }
                }
            });
            RtspMediaSource source = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .setTimeoutMs(1200)
                    .createMediaSource(MediaItem.fromUri(url));
            mp.setVideoSurface(surface);
            mp.setMediaSource(source);
            mp.prepare();
            mp.play();
        } catch (Exception e) {
            if (!forceTcp) {
                rtspTcpFallback = true;
                markVideoReconnect();
                ui.post(() -> startRtspVideo(activeVideoHost));
            } else {
                ui.post(() -> videoFailed(generation));
            }
        }
    }

    private boolean isVideoCurrent(int generation) {
        return resumed && generation == videoGeneration;
    }

    private void videoFailed(int generation) {
        if (generation != videoGeneration) return;
        videoStarting = false;
        ++videoGeneration;
        releasePlayerAsync();
        if (controls != null) controls.setVideoText("视频不可用 · 控制仍运行");
    }

    private void releaseVideo() {
        videoStarting = false;
        ++videoGeneration;
        ui.removeCallbacks(bufferWatchdog);
        if (webRtcReceiver != null) webRtcReceiver.stop();
        releasePlayerAsync();
    }

    private void releasePlayerAsync() {
        Handler worker = videoWorker;
        if (worker != null) worker.post(this::releasePlayerOnWorker);
    }

    private void releasePlayerOnWorker() {
        ExoPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    private void checkVideoBuffer() {
        if (!resumed || !videoWebRtcFallback || mediaPlayer == null) return;
        // ExoPlayer was created on videoWorker, so reading its position from
        // the UI thread violates Player's application-looper contract.  On
        // API 22 this is an IllegalStateException that occurs just after the
        // first frame, making the whole car-control Activity disappear.  Do
        // the read and any player decision on the same worker as prepare().
        Handler worker = videoWorker;
        if (worker == null) return;
        final int generation = videoGeneration;
        worker.post(() -> {
            if (!isVideoCurrent(generation) || !videoWebRtcFallback) return;
            ExoPlayer player = mediaPlayer;
            if (player == null) return;
            long buffered;
            try {
                buffered = player.getBufferedPosition() - player.getCurrentPosition();
            } catch (RuntimeException ignored) {
                // The player may be released by a simultaneous pause/reconnect.
                return;
            }
            // Ignore ExoPlayer's TIME_UNSET/TIME_END_OF_SOURCE sentinels; only
            // a finite live queue above 300ms should trigger a reconnect.
            if (buffered > 300L && buffered < 5000L && !videoStarting) {
                // A single large buffer reading is normal while RTSP starts.
                // Reconnect only after four consecutive 500ms readings so a
                // transient Wi-Fi burst cannot create a reconnect/keyframe
                // wait that is longer than the original delay.
                bufferOverrunCount++;
                if (bufferOverrunCount >= 4) {
                    bufferOverrunCount = 0;
                    videoStarting = true;
                    markVideoReconnect();
                    ui.post(() -> {
                        if (isVideoCurrent(generation) && videoWebRtcFallback) {
                            startRtspVideo(activeVideoHost);
                        }
                    });
                    return;
                }
            } else {
                bufferOverrunCount = 0;
            }
            ui.postDelayed(bufferWatchdog, 500L);
        });
    }

    private void markVideoReconnect() {
        videoReconnectCount++;
        videoLastReconnectMs = System.currentTimeMillis();
    }

    private String videoLabel(String base) {
        if (videoLastReconnectMs <= 0) return base;
        CharSequence at = android.text.format.DateFormat.format("HH:mm:ss", videoLastReconnectMs);
        return base + " · 重连 " + at;
    }

    private void showRoverSettings() {
        if (isFinishing()) return;
        final EditText host = new EditText(this);
        host.setHint("K230 主机地址");
        host.setText(Prefs.roverTargetHost(this));
        final EditText port = new EditText(this);
        port.setHint("UDP 端口");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(Prefs.roverUdpPort(this)));
        final EditText rtspPort = new EditText(this);
        rtspPort.setHint("RTSP 端口");
        rtspPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        rtspPort.setText(String.valueOf(Prefs.roverRtspPort(this)));
        final EditText rtspPath = new EditText(this);
        rtspPath.setHint("RTSP 路径，如 /test");
        rtspPath.setText(Prefs.roverRtspPath(this));
        final CheckBox discovery = check("自动发现（7789）", Prefs.roverAutoDiscovery(this));
        final CheckBox broadcast = check("无目标时允许广播", Prefs.roverBroadcast(this));
        final CheckBox webRtc = check("优先 WebRTC（失败回退 RTSP）",
                Prefs.ROVER_VIDEO_WEBRTC.equals(Prefs.roverVideoMode(this)));
        final CheckBox leftInvert = check("左摇杆 Y 轴反向", Prefs.roverLeftYInverted(this));
        final CheckBox rightInvert = check("右摇杆 Y 轴反向", Prefs.roverRightYInverted(this));
        new RoundDialog(this)
                .title("车控设置")
                .text("UDP 5555 协议兼容 ESP32；WebRTC 优先，失败自动回退 RTSP")
                .field(host)
                .field(port)
                .field(rtspPort)
                .field(rtspPath)
                .view(discovery)
                .view(broadcast)
                .view(webRtc)
                .view(leftInvert)
                .view(rightInvert)
                .item("保存", () -> {
                    String h = host.getText().toString().trim();
                    int p = Prefs.DEFAULT_ROVER_UDP_PORT;
                    try { p = Integer.parseInt(port.getText().toString().trim()); }
                    catch (Exception ignored) {}
                    p = Math.max(1, Math.min(65535, p));
                    int rp = Prefs.DEFAULT_ROVER_RTSP_PORT;
                    try { rp = Integer.parseInt(rtspPort.getText().toString().trim()); }
                    catch (Exception ignored) {}
                    rp = Math.max(1, Math.min(65535, rp));
                    String path = rtspPath.getText().toString().trim();
                    if (path.length() == 0) path = Prefs.DEFAULT_ROVER_RTSP_PATH;
                    if (!path.startsWith("/")) path = "/" + path;
                    Prefs.put(this, Prefs.K_ROVER_TARGET_HOST, h);
                    Prefs.putI(this, Prefs.K_ROVER_UDP_PORT, p);
                    Prefs.putI(this, Prefs.K_ROVER_RTSP_PORT, rp);
                    Prefs.put(this, Prefs.K_ROVER_RTSP_PATH, path);
                    Prefs.putB(this, Prefs.K_ROVER_AUTO_DISCOVERY, discovery.isChecked());
                    Prefs.putB(this, Prefs.K_ROVER_BROADCAST, broadcast.isChecked());
                    Prefs.put(this, Prefs.K_ROVER_VIDEO_MODE,
                            webRtc.isChecked() ? Prefs.ROVER_VIDEO_WEBRTC : Prefs.ROVER_VIDEO_RTSP);
                    Prefs.putB(this, Prefs.K_ROVER_LEFT_Y_INVERT, leftInvert.isChecked());
                    Prefs.putB(this, Prefs.K_ROVER_RIGHT_Y_INVERT, rightInvert.isChecked());
                    controls.setYInverted(leftInvert.isChecked(), rightInvert.isChecked());
                    transport.reloadPrefs();
                    activeVideoHost = "";
                    if (resumed) startVideo(transport.activeHost());
                })
                .cancel()
                .show();
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Ui.COLOR_TEXT);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.COLOR_GOLD));
        box.setChecked(checked);
        return box;
    }
}
