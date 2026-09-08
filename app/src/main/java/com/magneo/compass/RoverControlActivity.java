package com.magneo.compass;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.magneo.compass.ui.OutlineUtil;
import com.magneo.compass.ui.Ui;

/**
 * TALOS rover page.  The video is only a background preview; the raw joy
 * transport remains alive when RTSP cannot be decoded.
 */
public class RoverControlActivity extends BaseActivity implements
        RoverUdpTransport.Listener, RoverStatusClient.Listener {
    public static final String EXTRA_OPEN_SETTINGS = "open_rover_settings";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView video;
    private RoverControlView controls;
    private RoverUdpTransport transport;
    private RoverStatusClient statusClient;
    private volatile MediaPlayer mediaPlayer;
    private HandlerThread videoThread;
    private Handler videoWorker;
    private Surface videoSurface;
    private String activeVideoHost = "";
    private volatile boolean resumed;
    private boolean videoStarting;
    private volatile int videoGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // MediaPlayer may perform network I/O while setting up an RTSP source.
        // Keep all of that work off the UI thread so a slow/unavailable camera
        // cannot trigger NetworkOnMainThreadException or stall the controls.
        videoThread = new HandlerThread("rover-rtsp");
        videoThread.start();
        videoWorker = new Handler(videoThread.getLooper());

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new TextureView(this);
        video.setOpaque(true);
        video.setBackgroundColor(Color.BLACK);
        video.setClickable(false);
        OutlineUtil.oval(video);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                videoSurface = new Surface(surface);
                if (resumed) {
                    String host = activeVideoHost.length() == 0 && transport != null
                            ? transport.activeHost() : activeVideoHost;
                    startVideo(host);
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releaseVideo();
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        controls.stopAndReset();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onDestroy() {
        releaseVideo();
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
        if (!resumed || videoSurface == null || host == null || host.trim().length() == 0) {
            if (host == null || host.trim().length() == 0) controls.setVideoText("视频等待 K230");
            return;
        }
        host = host.trim();
        if (videoStarting && host.equals(activeVideoHost)) return;
        activeVideoHost = host;
        final int generation = ++videoGeneration;
        videoStarting = true;
        controls.setVideoText("视频连接中");
        final String url = Uri.parse("rtsp://" + host + ":" + Prefs.roverRtspPort(this)
                + Prefs.roverRtspPath(this)).toString();
        final Surface surface = videoSurface;
        Handler worker = videoWorker;
        if (worker == null) {
            videoFailed(generation);
            return;
        }
        worker.post(() -> prepareVideoOnWorker(generation, url, surface));
    }

    private void prepareVideoOnWorker(final int generation, String url, Surface surface) {
        releasePlayerOnWorker();
        if (!isVideoCurrent(generation) || surface == null) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setOnPreparedListener(player -> {
                if (!isVideoCurrent(generation) || mediaPlayer != player) return;
                try {
                    player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    player.start();
                    ui.post(() -> {
                        if (isVideoCurrent(generation) && mediaPlayer == player) {
                            videoStarting = false;
                            controls.setVideoText("视频在线");
                        }
                    });
                } catch (Exception e) {
                    ui.post(() -> videoFailed(generation));
                }
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    ui.post(() -> {
                        if (isVideoCurrent(generation) && mediaPlayer == player)
                            controls.setVideoText("视频缓冲");
                    });
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    ui.post(() -> {
                        if (isVideoCurrent(generation) && mediaPlayer == player)
                            controls.setVideoText("视频在线");
                    });
                }
                return false;
            });
            mp.setOnErrorListener((player, what, extra) -> {
                ui.post(() -> videoFailed(generation));
                return true;
            });
            // This call can touch the network, so it deliberately runs on the
            // rover-rtsp HandlerThread rather than the main/UI thread.
            mp.setDataSource(this, Uri.parse(url));
            mp.setSurface(surface);
            mp.prepareAsync();
        } catch (Exception e) {
            ui.post(() -> videoFailed(generation));
        }
    }

    private boolean isVideoCurrent(int generation) {
        return resumed && generation == videoGeneration;
    }

    private void videoFailed(int generation) {
        if (generation != videoGeneration) return;
        videoStarting = false;
        ++videoGeneration;
        postReleasePlayer();
        if (controls != null) controls.setVideoText("视频不可用 · 控制仍运行");
    }

    private void releaseVideo() {
        videoStarting = false;
        ++videoGeneration;
        postReleasePlayer();
    }

    private void postReleasePlayer() {
        Handler worker = videoWorker;
        if (worker != null) worker.post(this::releasePlayerOnWorker);
    }

    private void releasePlayerOnWorker() {
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.reset(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
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
        final CheckBox leftInvert = check("左摇杆 Y 轴反向", Prefs.roverLeftYInverted(this));
        final CheckBox rightInvert = check("右摇杆 Y 轴反向", Prefs.roverRightYInverted(this));
        new RoundDialog(this)
                .title("车控设置")
                .text("UDP 5555 协议兼容 ESP32；视频地址由下面 RTSP 端口和路径组成")
                .field(host)
                .field(port)
                .field(rtspPort)
                .field(rtspPath)
                .view(discovery)
                .view(broadcast)
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
