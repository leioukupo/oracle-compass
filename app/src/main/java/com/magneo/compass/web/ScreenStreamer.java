package com.magneo.compass.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.magneo.compass.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 屏幕实时推流（root 常驻版）：一条 root 循环持续 screencap 写 raw 文件（无每帧 su 开销），
 * 应用按文件变更轮询 -> 直接按目标尺寸采样 -> setPixels -> JPEG ->
 * multipart/x-mixed-replace 推给网页 &lt;img&gt;。避开 ART 大数组 Critical 崩溃与 su 管道延迟。
 */
public class ScreenStreamer {
    private static final String TAG = "ScreenStreamer";
    private static final int W = 800, H = 800;
    private static final int FRAME_LEN = 12 + W * H * 4;
    private static final byte[] BOUNDARY = "truthscreen".getBytes();

    private static volatile boolean active;
    private static Bitmap fullBmp, smallBmp;
    private static int[] fullColors, smallColors;
    private static ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);

    private ScreenStreamer() {}

    public static String state() { return active ? "recording" : "idle"; }
    public static boolean isActive() { return active; }

    /** 处理一个 /stream 连接：拉起 root 采集循环，持续推帧；断开即停并清理。 */
    public static void serve(Socket s, Context ctx) {
        synchronized (ScreenStreamer.class) {
            if (active || H264Streamer.isActive() || H264SurfaceStreamer.isActive()) { writeBusy(s, "BUSY"); return; }
            active = true;
        }
        ScreenAwake.on(ctx);                           // 推流期间屏幕常亮，防息屏黑屏
        Process shell = null;
        InputStream in = null;
        try {
            shell = FifoCapture.start(ctx);
            in = FifoCapture.openReader(ctx);
            stream(s, ctx, in);
        } catch (Throwable t) {
            Log.w(TAG, "serve", t);
        } finally {
            active = false;
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            FifoCapture.stop(ctx, shell);
            ScreenAwake.off();                         // 恢复屏幕自动休眠
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void stream(Socket s, Context ctx, InputStream in) throws Exception {
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=truthscreen\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.flush();

        byte[] buf = new byte[FRAME_LEN];
        boolean first = true;
        long lastPushed = 0;
        while (!Thread.currentThread().isInterrupted()) {
            int fps = clamp(Prefs.getI(ctx, Prefs.K_STREAM_FPS, 1), 1, 10);
            int quality = clamp(Prefs.getI(ctx, Prefs.K_STREAM_QUALITY, 55), 20, 90);
            int scale = clamp(Prefs.getI(ctx, Prefs.K_STREAM_SCALE, 2), 1, 2);
            long interval = 1000L / fps;
            long now = System.currentTimeMillis();
            long wait = interval - (now - lastPushed);
            if (wait > 0) { Thread.sleep(wait); continue; }

            if (!FifoCapture.readFrame(in, buf)) return; // 写端退出
            int w = (buf[0] & 0xff) | ((buf[1] & 0xff) << 8) | ((buf[2] & 0xff) << 16) | ((buf[3] & 0xff) << 24);
            int h = (buf[4] & 0xff) | ((buf[5] & 0xff) << 8) | ((buf[6] & 0xff) << 16) | ((buf[7] & 0xff) << 24);
            int fmt = (buf[8] & 0xff) | ((buf[9] & 0xff) << 8) | ((buf[10] & 0xff) << 16) | ((buf[11] & 0xff) << 24);
            if (w != W || h != H || fmt != 1) continue;

            byte[] jpeg = encode(buf, quality, scale);
            if (jpeg == null || jpeg.length == 0) continue;

            try {
                if (first) out.write(("--" + new String(BOUNDARY, "US-ASCII") + "\r\n").getBytes("ISO-8859-1"));
                else out.write(("\r\n--" + new String(BOUNDARY, "US-ASCII") + "\r\n").getBytes("ISO-8859-1"));
                first = false;
                out.write(("Content-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes("ISO-8859-1"));
                out.write(jpeg);
                out.flush();
                lastPushed = System.currentTimeMillis();
            } catch (Exception e) {
                return; // 客户端断开
            }
        }
    }

    /** 按目标尺寸直接采样 RGBA -> int[] -> setPixels -> JPEG（不做二次缩放）。 */
    private static byte[] encode(byte[] frame, int quality, int scale) {
        try {
            if (scale > 1) {
                int sw = W / scale, sh = H / scale;
                if (smallBmp == null) {
                    smallBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    smallColors = new int[sw * sh];
                }
                for (int y = 0, k = 0; y < sh; y++) {
                    int row = (y * scale * W + 12);
                    for (int x = 0; x < sw; x++, k++) {
                        int j = row + x * scale * 4;
                        smallColors[k] = 0xFF000000 | ((frame[j] & 0xff) << 16)
                                | ((frame[j + 1] & 0xff) << 8) | (frame[j + 2] & 0xff);
                    }
                }
                smallBmp.setPixels(smallColors, 0, sw, 0, 0, sw, sh);
            } else {
                if (fullBmp == null) {
                    fullBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    fullColors = new int[W * H];
                }
                for (int i = 0, j = 12; i < fullColors.length; i++, j += 4) {
                    fullColors[i] = 0xFF000000 | ((frame[j] & 0xff) << 16)
                            | ((frame[j + 1] & 0xff) << 8) | (frame[j + 2] & 0xff);
                }
                fullBmp.setPixels(fullColors, 0, W, 0, 0, W, H);
            }
            Bitmap bmp = scale > 1 ? smallBmp : fullBmp;
            bos.reset();
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null;
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "encode", e);
            return null;
        }
    }

    private static void writeBusy(Socket s, String reason) {
        try {
            OutputStream out = s.getOutputStream();
            String body = "stream unavailable: " + reason;
            out.write(("HTTP/1.1 503 " + reason + "\r\nContent-Length: " + body.getBytes("UTF-8").length
                    + "\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + body).getBytes("ISO-8859-1"));
        } catch (Exception ignored) {}
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
