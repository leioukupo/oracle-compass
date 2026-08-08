package com.magneo.compass.cam;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.os.IBinder;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.util.List;

/** 摄像头网络流服务：前/后摄采集 -> H.264 硬编 -> RTSP / RTMP / fMP4(网页) / WebRTC 输出。 */
public class CameraStreamService extends Service {
    private static final String TAG = "CamStream";
    private static volatile CameraStreamService inst;
    private static volatile String status = "stopped";
    private static volatile String statusDetail = "";
    private static volatile String realFps = "-";
    private static volatile String fpsInfo = "";
    private static volatile String camDiag = "";

    private H264Encoder encoder;
    private RtpServer rtp;
    private RtmpPusher rtmp;
    private Camera camera;
    private int w, h;
    private volatile boolean running = false;

    public static String status() { return status; }
    public static String statusDetail() { return statusDetail; }
    public static String realFps() { return realFps; }
    public static String fpsInfo() { return fpsInfo; }
    public static String camDiag() { return camDiag; }
    public static boolean isRunning() { return "running".equals(status); }

    public static void start(Context c) {
        c.startService(new Intent(c, CameraStreamService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CameraStreamService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        stopAll();
        status = "starting";
        statusDetail = "";
        new Thread(this::startAll, "cam-start").start();
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

            camera = Camera.open(camId);
            Camera.Parameters p = camera.getParameters();
            java.util.List<Camera.Size> allSizes = p.getSupportedPreviewSizes();
            Camera.Size size = chooseSize(allSizes, wantW, wantH);
            w = size.width;
            h = size.height;
            StringBuilder szl = new StringBuilder("sizes:");
            if (allSizes != null) for (Camera.Size cs : allSizes) szl.append(cs.width).append("x").append(cs.height).append(",");
            fpsInfo = szl.toString();
            p.setPreviewSize(w, h);
            // setPreviewFormat 不强制：MTK 后摄在 NV21 请求下实测输出极暗，用默认格式（可能是 YV12/NV12）
            // 曝光保持自动（AE），不手动干预

            final int[] encFrames = {0};
            final int[] encBytes = {0};
            final int[] encKeys = {0};
            encoder = new H264Encoder(w, h, fps, bitrate, new H264Encoder.Listener() {
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
            int setFps = negotiateFps(p, fps);
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
                    camDiag = "Ymid=" + yMid + " UVa=" + uvA + " UVb=" + uvB + " " + H264Encoder.fmtDiag();
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
                                + " " + fpsInfo;
                    }
                }
                try {
                    long pts = System.nanoTime() / 1000;
                    if (encoder != null) encoder.feed(data, pts);
                    WebRtcStreamer wr2 = WebRtcStreamer.get();
                    if (wr2 != null) wr2.feedFrame(data, w, h);
                } catch (Exception e) {
                    Log.w(TAG, "frame failed", e);
                }
                cam.addCallbackBuffer(data);
            });
            camera.addCallbackBuffer(new byte[w * h * 3 / 2]);
            camera.addCallbackBuffer(new byte[w * h * 3 / 2]);
            camera.addCallbackBuffer(new byte[w * h * 3 / 2]);
            camera.addCallbackBuffer(new byte[w * h * 3 / 2]);
            // MTK 后摄纯回调模式传感器输出暗：GL 线程消费 SurfaceTexture，让 HAL 进入正常预览模式
            try {
                final android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(0);
                camera.setPreviewTexture(st);
                new Thread(() -> consumeSurface(st), "surf-consumer").start();
            } catch (Throwable stErr) {
                camDiag = "surfaceTex失败: " + stErr.getMessage();
            }
            camera.startPreview();
            running = true;
            status = "running";
            StringBuilder rng = new StringBuilder();
            java.util.List<int[]> ranges = p.getSupportedPreviewFpsRange();
            if (ranges != null) for (int[] r : ranges) rng.append("[").append(r[0] / 1000).append("-").append(r[1] / 1000).append("]");
            statusDetail = "cam=" + camId + " " + w + "x" + h + "@" + setFps + "fps " + fpsDiag + " 实际" + realFps + " range=" + rng;
        } catch (Throwable t) {
            Log.w(TAG, "startAll failed", t);
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

    /** GL 线程消费 SurfaceTexture 帧，激活传感器正常预览输出。 */
    private void consumeSurface(android.graphics.SurfaceTexture st) {
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
            while (running) {
                st.updateTexImage();
                Thread.sleep(16);
            }
        } catch (Throwable t) {
            Log.w(TAG, "surf consumer exit", t);
        } finally {
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

    private synchronized void stopAll() {
        running = false;
        status = "stopped";
        realFps = "-";
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception ignored) {}
            try { camera.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) {}
            try { camera.release(); } catch (Exception ignored) {}
            camera = null;
        }
        if (encoder != null) { try { encoder.release(); } catch (Exception ignored) {} encoder = null; }
        if (rtp != null) { try { rtp.stop(); } catch (Exception ignored) {} rtp = null; }
        if (rtmp != null) { try { rtmp.stop(); } catch (Exception ignored) {} rtmp = null; }
        try { WebRtcStreamer.get().teardown(); } catch (Exception ignored) {}
        try { CameraHttpStreamer.get().clear(); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        stopAll();
        inst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
