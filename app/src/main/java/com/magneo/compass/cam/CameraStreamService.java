package com.magneo.compass.cam;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.IBinder;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 摄像头网络流服务：前/后摄采集 -> H.264 硬编 -> RTSP / RTMP / fMP4(网页) / WebRTC 输出。 */
public class CameraStreamService extends Service {
    private static final String TAG = "CamStream";
    private static volatile CameraStreamService inst;
    private static volatile String status = "stopped";
    private static volatile String statusDetail = "";
    private static volatile String realFps = "-";
    private static volatile String fpsInfo = "";
    private static volatile String camDiag = "";
    private static volatile boolean visionSnapshotEnabled = false;
    private static volatile FrameSnapshot latestVisionSnapshot;
    private static volatile long latestVisionSeq = 0;
    private static volatile SurfaceTexture pendingVisionPreviewTexture;

    private H264Encoder encoder;
    private RtpServer rtp;
    private RtmpPusher rtmp;
    private Camera camera;
    private int w, h;
    private volatile boolean running = false;
    private final java.util.concurrent.ExecutorService cameraControl =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cam-control");
                t.setDaemon(true);
                return t;
            });
    private ScheduledExecutorService streamDemandMonitor;
    private volatile SurfaceTexture activePreviewTexture;
    private volatile SurfaceTexture fallbackPreviewTexture;
    private volatile boolean fallbackConsumerRunning;
    private volatile boolean visionPreviewDirect;
    private volatile boolean encodingActive;
    private volatile int configuredFps;
    private volatile int configuredBitrate;
    private volatile CameraSourceFormat configuredSourceFormat = CameraSourceFormat.NV21;
    private volatile String configuredRtmpUrl = "";
    private volatile byte[] visionNv21Scratch;
    private final int[] encFrames = {0};
    private final int[] encBytes = {0};
    private final int[] encKeys = {0};

    /** NV21 帧监听器（供灵眼等界面直接抓帧用，不走 RTSP 回环）。 */
    public interface FrameListener {
        void onFrame(byte[] nv21, int w, int h);
    }
    private volatile FrameListener frameListener;

    public static final class FrameSnapshot {
        public final byte[] nv21;
        public final int w;
        public final int h;
        public final long ptsUs;
        public final long seq;

        FrameSnapshot(byte[] nv21, int w, int h, long ptsUs, long seq) {
            this.nv21 = nv21;
            this.w = w;
            this.h = h;
            this.ptsUs = ptsUs;
            this.seq = seq;
        }
    }

    public static String status() { return status; }
    public static String statusDetail() { return statusDetail; }
    public static String realFps() { return realFps; }
    public static String fpsInfo() { return fpsInfo; }
    public static String camDiag() { return camDiag; }
    public static boolean isVisionPreviewDirect() {
        CameraStreamService s = inst;
        return s != null && s.visionPreviewDirect;
    }
    public static boolean isEncodingActive() {
        CameraStreamService s = inst;
        return s != null && s.encodingActive;
    }
    public static int externalConsumerCount() {
        CameraStreamService s = inst;
        return s == null ? 0 : s.externalConsumerCountInternal();
    }
    public static boolean isRunning() { return "running".equals(status); }
    public static boolean isStarting() { return "starting".equals(status); }
    public static boolean isActive() { return isRunning() || isStarting(); }

    public static void start(Context c) {
        c.startService(new Intent(c, CameraStreamService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CameraStreamService.class));
    }

    public static void setFrameListener(FrameListener l) {
        pendingListener = l;
        CameraStreamService s = inst;
        if (s != null) s.frameListener = l;
    }

    /**
     * 让灵眼的 TextureView 直接承接 Camera1 预览。服务尚未启动时会缓存，启动后自动绑定。
     * 传入 null 表示回退到服务内部的离屏预览目标。
     */
    public static void setVisionPreviewSurface(SurfaceTexture texture) {
        pendingVisionPreviewTexture = texture;
        CameraStreamService s = inst;
        if (s != null) s.schedulePreviewTarget(texture);
    }

    /** 仅清除调用方自己注册的 Surface，避免旧 Activity 销毁时抢走新页面的预览。 */
    public static void clearVisionPreviewSurface(SurfaceTexture texture) {
        if (texture == null || pendingVisionPreviewTexture == texture) {
            pendingVisionPreviewTexture = null;
            CameraStreamService s = inst;
            if (s != null) s.schedulePreviewTarget(null);
        }
    }

    public static void setVisionSnapshotEnabled(boolean enabled) {
        visionSnapshotEnabled = enabled;
    }

    public static FrameSnapshot latestVisionSnapshot() {
        return latestVisionSnapshot;
    }
    private static volatile FrameListener pendingListener;

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        if (pendingListener != null) frameListener = pendingListener;
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        cameraControl.execute(() -> {
            stopAll();
            status = "starting";
            statusDetail = "";
            startAll();
        });
        return START_STICKY;
    }

    private void startAll() {
        try {
            StringBuilder camList = new StringBuilder();
            try {
                int n = Camera.getNumberOfCameras();
                camList.append("cams=").append(n);
                for (int ci = 0; ci < n; ci++) {
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    Camera.getCameraInfo(ci, info);
                    camList.append("[").append(ci).append(info.facing == Camera.CameraInfo.CAMERA_FACING_BACK ? "后" : "前").append("]");
                }
            } catch (Throwable ignored) {}
            fpsInfo = camList.toString();
            int camId = Prefs.getI(this, Prefs.K_CAM_ID, 0);
            int wantW = Prefs.getI(this, Prefs.K_CAM_WIDTH, 1280);
            int wantH = Prefs.getI(this, Prefs.K_CAM_HEIGHT, 720);
            int fps = Prefs.getI(this, Prefs.K_CAM_FPS, 24);
            int bitrate = Prefs.getI(this, Prefs.K_CAM_BITRATE, 5000) * 1000;
            int rtspPort = Prefs.getI(this, Prefs.K_RTSP_PORT, 8554);
            String rtmpUrl = Prefs.get(this, Prefs.K_RTMP_URL, "");
            configuredRtmpUrl = rtmpUrl == null ? "" : rtmpUrl.trim();

            rtp = new RtpServer(rtspPort);
            rtp.start();

            if (rtmpUrl != null && !rtmpUrl.trim().isEmpty()) {
                try {
                    rtmp = new RtmpPusher(rtmpUrl.trim());
                    rtmp.start();
                } catch (Exception e) {
                    Log.w(TAG, "rtmp start failed", e);
                    statusDetail += "RTMP 启动失败: " + e.getMessage() + "; ";
                }
            }

            WebRtcStreamer wr = WebRtcStreamer.get();
            wr.init(getApplicationContext());

            com.magneo.compass.FlashlightController.releaseHardwareKeepingRequest();
            camera = Camera.open(camId);
            // 灵眼旧的 Bitmap 路径始终旋转 90 度；直出预览保持相同的纵向显示方向。
            try { camera.setDisplayOrientation(90); } catch (Throwable ignored) {}
            Camera.Parameters p = camera.getParameters();
            java.util.List<Camera.Size> allSizes = p.getSupportedPreviewSizes();
            Camera.Size size = chooseSize(allSizes, wantW, wantH);
            w = size.width;
            h = size.height;
            StringBuilder szl = new StringBuilder("sizes:");
            if (allSizes != null) for (Camera.Size cs : allSizes) szl.append(cs.width).append("x").append(cs.height).append(",");
            fpsInfo = szl.toString();
            p.setPreviewSize(w, h);
            // MTK 后摄 NV21 可能偏暗，这里优先尝试 NV21，失败则回退默认格式（通常是 YV12）
            try { p.setPreviewFormat(android.graphics.ImageFormat.NV21); } catch (Throwable ignored) {}
            try { camera.setParameters(p); } catch (Throwable ignored) {}
            final int camFmt = p.getPreviewFormat();
            final int yv12Align = (((w + 31) & ~31) * h) + ((((w / 2) + 15) & ~15) * (h / 2) * 2);
            final int frameSize = camFmt == android.graphics.ImageFormat.NV21
                    ? w * h * 3 / 2
                    : yv12Align;
            final CameraSourceFormat camSrcFmt = camFmt == android.graphics.ImageFormat.NV21
                    ? CameraSourceFormat.NV21
                    : CameraSourceFormat.YV12;
            // 曝光保持自动（AE），不手动干预

            int setFps = negotiateFps(p, fps);
            configuredFps = setFps;
            configuredBitrate = bitrate;
            configuredSourceFormat = camSrcFmt;
            encFrames[0] = 0;
            encBytes[0] = 0;
            encKeys[0] = 0;
            String fpsDiag = "fps=" + setFps + " camFmt=" + p.getPreviewFormat();
            boolean ok = false;
            try {
                p.setPreviewFpsRange(setFps * 1000, setFps * 1000);
                camera.setParameters(p);
                ok = true;
                fpsDiag += "[exact]";
            } catch (Throwable t1) {
                try { camera.setParameters(p); } catch (Throwable ignored) {}
            }
            try {
                p.setRecordingHint(true);      // 录制模式：MTK 预览回调才会跑满帧率
                camera.setParameters(p);
                fpsDiag += "[recHint]";
            } catch (Throwable t2) {
                fpsDiag += "[recHint失败]";
            }

            // MTK 私有参数：实测对前摄亮度有决定性影响（sensor-fps=60 时前摄 209，去掉后暗 7）
            try {
                Camera.Parameters pp = camera.getParameters();
                pp.set("sensor-fps", "60");
                camera.setParameters(pp);
                fpsDiag += "[sf60]";
            } catch (Throwable ignored) {}
            if (com.magneo.compass.FlashlightController.applyToOpenedCamera(camera)) {
                fpsDiag += "[torch]";
            }
            fpsInfo = fpsInfo + " " + fpsDiag;

            // 回调驱动的实际帧率统计（每 50 帧更新一次）
            final int[] fpsCount = {0};
            final long[] fpsT0 = {System.currentTimeMillis()};
            final int[] setFpsFinal = {setFps};
            final int[] camIdFinal = {camId};

            final long[] lumSum = {0};
            final int[] lumN = {0};
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (!running) return;
                fpsCount[0]++;
                // 采样亮度（基于实际尺寸的安全位置）
                int ySz = w * h;
                lumSum[0] += (data[ySz / 8] & 0xFF) + (data[ySz / 2] & 0xFF) + (data[ySz * 3 / 4] & 0xFF);
                lumN[0] += 3;
                if (fpsCount[0] % 200 == 1) {
                    int ySize = w * h;
                    int yMid = (data[ySize / 2] & 0xFF) + (data[ySize / 4] & 0xFF);
                    int uvA = (data[ySize + 100] & 0xFF) + (data[ySize + 1000] & 0xFF);
                    int uvB = (data[ySize + ySize / 4 + 100] & 0xFF) + (data[ySize + ySize / 4 + 1000] & 0xFF);
                    camDiag = "Ymid=" + yMid + " UVa=" + uvA + " UVb=" + uvB + " cam=" + camSrcFmt + " " + H264Encoder.fmtDiag();
                }
                if (fpsCount[0] >= 10) {
                    long dt = System.currentTimeMillis() - fpsT0[0];
                    if (dt >= 500) {
                        int f = (int) (fpsCount[0] * 1000L / dt);
                        realFps = String.valueOf(f);
                        fpsCount[0] = 0;
                        fpsT0[0] = System.currentTimeMillis();
                        int avgLum = lumN[0] > 0 ? (int) (lumSum[0] / lumN[0]) : 0;
                        statusDetail = "cam=" + camIdFinal[0] + " " + w + "x" + h + "@" + setFpsFinal[0]
                                + "fps 实际" + f + "fps 亮度" + avgLum
                                + " 编码" + encFrames[0] + "帧/" + (encBytes[0] / 1024) + "KB 关键帧" + encKeys[0]
                                + " " + pipelineStatus() + " " + fpsInfo;
                    }
                }
                long pts = System.nanoTime() / 1000;
                if (visionSnapshotEnabled) {
                    latestVisionSnapshot = new FrameSnapshot(copyAsNv21(data, camSrcFmt).clone(), w, h,
                            pts, ++latestVisionSeq);
                }
                try {
                    H264Encoder enc = encoder;
                    if (encodingActive && enc != null) enc.feedRaw(data, pts);
                    WebRtcStreamer wr2 = WebRtcStreamer.get();
                    if (encodingActive && wr2 != null && wr2.needsFrames()) {
                        wr2.feedFrameRaw(data, w, h, camSrcFmt);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "frame failed", e);
                }
                try {
                    FrameListener fl = frameListener;
                    if (fl != null) fl.onFrame(copyAsNv21(data, camSrcFmt), w, h);
                } catch (Exception ignored) {}
                cam.addCallbackBuffer(data);
            });
            camera.addCallbackBuffer(new byte[frameSize]);
            camera.addCallbackBuffer(new byte[frameSize]);
            camera.addCallbackBuffer(new byte[frameSize]);
            camera.addCallbackBuffer(new byte[frameSize]);
            bindPreviewTarget(pendingVisionPreviewTexture, false);
            camera.startPreview();
            running = true;
            if (activePreviewTexture == fallbackPreviewTexture) startFallbackConsumer(fallbackPreviewTexture);
            startStreamDemandMonitor();
            updateEncodingDemand();
            status = "running";
            StringBuilder rng = new StringBuilder();
            java.util.List<int[]> ranges = p.getSupportedPreviewFpsRange();
            if (ranges != null) for (int[] r : ranges) rng.append("[").append(r[0] / 1000).append("-").append(r[1] / 1000).append("]");
            statusDetail = "cam=" + camId + " " + w + "x" + h + "@" + setFps + "fps " + fpsDiag
                    + " 实际" + realFps + " " + pipelineStatus() + " range=" + rng;
        } catch (Throwable t) {
            Log.w(TAG, "startAll failed", t);
            stopAll();
            status = "error";
            statusDetail = t.getMessage() == null ? t.toString() : t.getMessage();
        }
    }

    /** 在传感器支持的帧率范围内选最接近目标的（60 优先，逐级降）。 */
    private int negotiateFps(Camera.Parameters p, int want) {
        int[] fallback = new int[]{want, 60, 30, 24, 15, 10};
        java.util.List<int[]> ranges = p.getSupportedPreviewFpsRange();
        if (ranges != null && !ranges.isEmpty()) {
            for (int f : fallback) {
                for (int[] r : ranges) {
                    if (r[0] <= f * 1000 && r[1] >= f * 1000) return f;
                }
            }
            int max = 0;
            for (int[] r : ranges) max = Math.max(max, r[1]);
            return max / 1000;
        }
        return want;
    }

    private void schedulePreviewTarget(final SurfaceTexture target) {
        cameraControl.execute(() -> {
            if (camera == null) return;
            bindPreviewTarget(target, true);
            updateEncodingDemand();
        });
    }

    /** Camera1 只能绑定一个预览目标；切换时短暂停预览，回调和编码器保持不变。 */
    private void bindPreviewTarget(SurfaceTexture requested, boolean restartPreview) {
        if (camera == null) return;
        SurfaceTexture next = requested == null ? ensureFallbackPreviewTexture() : requested;
        if (activePreviewTexture == next) {
            visionPreviewDirect = requested != null;
            return;
        }
        boolean restart = restartPreview && running;
        if (restart) {
            try { camera.stopPreview(); } catch (Throwable ignored) {}
        }
        SurfaceTexture old = activePreviewTexture;
        if (old == fallbackPreviewTexture) {
            fallbackConsumerRunning = false;
            fallbackPreviewTexture = null;
        }
        try {
            camera.setPreviewTexture(next);
            activePreviewTexture = next;
            visionPreviewDirect = requested != null;
            if (restart) camera.startPreview();
            if (running && next == fallbackPreviewTexture) startFallbackConsumer(next);
        } catch (Throwable t) {
            Log.w(TAG, "set preview target failed", t);
            visionPreviewDirect = false;
            if (requested != null) bindPreviewTarget(null, restartPreview);
        }
    }

    private SurfaceTexture ensureFallbackPreviewTexture() {
        SurfaceTexture texture = fallbackPreviewTexture;
        if (texture == null) {
            texture = new SurfaceTexture(0);
            fallbackPreviewTexture = texture;
        }
        return texture;
    }

    private void startFallbackConsumer(final SurfaceTexture texture) {
        if (texture == null || fallbackConsumerRunning) return;
        fallbackConsumerRunning = true;
        Thread t = new Thread(() -> consumeSurface(texture), "surf-consumer");
        t.setDaemon(true);
        t.start();
    }

    private byte[] copyAsNv21(byte[] raw, CameraSourceFormat format) {
        if (format == CameraSourceFormat.NV21) return raw;
        int bytes = w * h * 3 / 2;
        byte[] out = visionNv21Scratch;
        if (out == null || out.length != bytes) {
            out = new byte[bytes];
            visionNv21Scratch = out;
        }
        if (format == CameraSourceFormat.YV12) H264Encoder.yv12ToNv21(raw, out, w, h);
        else H264Encoder.nv12ToNv21(raw, out, w, h);
        return out;
    }

    private void startStreamDemandMonitor() {
        stopStreamDemandMonitor();
        streamDemandMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cam-demand");
            t.setDaemon(true);
            return t;
        });
        streamDemandMonitor.scheduleWithFixedDelay(
                () -> cameraControl.execute(this::updateEncodingDemand), 250, 250, TimeUnit.MILLISECONDS);
    }

    private void stopStreamDemandMonitor() {
        if (streamDemandMonitor != null) {
            streamDemandMonitor.shutdownNow();
            streamDemandMonitor = null;
        }
    }

    private int externalConsumerCountInternal() {
        int count = configuredRtmpUrl.isEmpty() ? 0 : 1;
        RtpServer rtsp = rtp;
        if (rtsp != null) count += rtsp.playingClientCount();
        count += CameraHttpStreamer.get().clientCount();
        if (WebRtcStreamer.get().needsFrames()) count++;
        return count;
    }

    private void updateEncodingDemand() {
        if (!running) return;
        boolean needEncoding = !visionPreviewDirect || externalConsumerCountInternal() > 0;
        if (needEncoding == encodingActive) return;
        if (needEncoding) enableEncoding();
        else disableEncoding();
        statusDetail = pipelineStatus() + " " + statusDetail;
    }

    private void enableEncoding() {
        if (encoder != null || w <= 0 || h <= 0 || configuredFps <= 0) return;
        try {
            H264Encoder enc = new H264Encoder(w, h, configuredFps, configuredBitrate,
                    new H264Encoder.Listener() {
                        @Override public void onSpsPps(byte[] sps, byte[] pps) {
                            if (rtp != null) rtp.setSpsPps(sps, pps);
                            if (rtmp != null) rtmp.setSpsPps(sps, pps);
                            CameraHttpStreamer.get().setSpsPps(w, h, sps, pps);
                        }

                        @Override public void onFrame(byte[] nal, long ptsUs, boolean keyframe) {
                            encFrames[0]++;
                            encBytes[0] += nal.length;
                            if (keyframe) encKeys[0]++;
                            if (rtp != null) rtp.feed(nal, ptsUs);
                            if (rtmp != null) rtmp.feed(nal, ptsUs, keyframe);
                            CameraHttpStreamer.get().feed(nal, ptsUs, keyframe);
                        }
                    });
            enc.setSourceFormat(configuredSourceFormat);
            encoder = enc;
            encodingActive = true;
            enc.requestKeyframe();
        } catch (Throwable t) {
            encodingActive = false;
            Log.w(TAG, "encoder start failed", t);
            statusDetail = "编码启动失败: " + t.getMessage();
        }
    }

    private void disableEncoding() {
        encodingActive = false;
        H264Encoder enc = encoder;
        encoder = null;
        if (enc != null) {
            try { enc.release(); } catch (Throwable ignored) {}
        }
        try { CameraHttpStreamer.get().resetCodecConfig(); } catch (Throwable ignored) {}
    }

    private String pipelineStatus() {
        return "本地预览=" + (visionPreviewDirect ? "直出" : "离屏")
                + " 编码=" + (encodingActive ? "运行" : "暂停")
                + " 外部观看=" + externalConsumerCountInternal();
    }

    /** GL 线程消费 SurfaceTexture 帧，激活传感器正常预览输出。 */
    private void consumeSurface(SurfaceTexture st) {
        javax.microedition.khronos.egl.EGL10 egl =
                (javax.microedition.khronos.egl.EGL10) javax.microedition.khronos.egl.EGLContext.getEGL();
        javax.microedition.khronos.egl.EGLDisplay dpy = null;
        javax.microedition.khronos.egl.EGLContext ctx = null;
        javax.microedition.khronos.egl.EGLSurface surf = null;
        int[] tex = new int[1];
        try {
            dpy = egl.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY);
            egl.eglInitialize(dpy, new int[2]);
            int[] cfgAttr = {javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 0x4, javax.microedition.khronos.egl.EGL10.EGL_NONE};
            javax.microedition.khronos.egl.EGLConfig[] cfgs = new javax.microedition.khronos.egl.EGLConfig[1];
            int[] n = new int[1];
            egl.eglChooseConfig(dpy, cfgAttr, cfgs, 1, n);
            int[] ctxAttr = {0x3098, 2, javax.microedition.khronos.egl.EGL10.EGL_NONE};
            ctx = egl.eglCreateContext(dpy, cfgs[0], javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, ctxAttr);
            surf = egl.eglCreatePbufferSurface(dpy, cfgs[0], new int[]{0x3057, 2, 0x3056, 2, javax.microedition.khronos.egl.EGL10.EGL_NONE});
            egl.eglMakeCurrent(dpy, surf, surf, ctx);
            android.opengl.GLES20.glGenTextures(1, tex, 0);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            st.attachToGLContext(tex[0]);
            while (running && fallbackConsumerRunning && st == activePreviewTexture) {
                st.updateTexImage();
                Thread.sleep(16);
            }
        } catch (Throwable t) {
            Log.w(TAG, "surf consumer exit", t);
        } finally {
            if (st == activePreviewTexture) fallbackConsumerRunning = false;
            try { st.release(); } catch (Exception ignored) {}
            try { egl.eglMakeCurrent(dpy, javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
                    javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE, javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT); } catch (Exception ignored) {}
            try { if (surf != null) egl.eglDestroySurface(dpy, surf); } catch (Exception ignored) {}
            try { if (ctx != null) egl.eglDestroyContext(dpy, ctx); } catch (Exception ignored) {}
        }
    }

    private Camera.Size chooseSize(List<Camera.Size> sizes, int w, int h) {
        Camera.Size best = null;
        long bestDiff = Long.MAX_VALUE;
        if (sizes != null) {
            for (Camera.Size s : sizes) {
                long diff = Math.abs(s.width - w) + Math.abs(s.height - h);
                if (diff < bestDiff) { bestDiff = diff; best = s; }
            }
        }
        return best;
    }

    private void stopAll() {
        stopStreamDemandMonitor();
        running = false;
        fallbackConsumerRunning = false;
        visionPreviewDirect = false;
        status = "stopped";
        realFps = "-";
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        disableEncoding();
        if (rtp != null) { try { rtp.stop(); } catch (Exception ignored) {} rtp = null; }
        if (rtmp != null) { try { rtmp.stop(); } catch (Exception ignored) {} rtmp = null; }
        try { WebRtcStreamer.get().teardown(); } catch (Exception ignored) {}
        try { CameraHttpStreamer.get().clear(); } catch (Exception ignored) {}
        latestVisionSnapshot = null;
        latestVisionSeq = 0;
        activePreviewTexture = null;
        fallbackPreviewTexture = null;
        visionNv21Scratch = null;
        configuredRtmpUrl = "";
    }

    @Override public void onDestroy() {
        cameraControl.execute(this::stopAll);
        cameraControl.shutdown();
        inst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
