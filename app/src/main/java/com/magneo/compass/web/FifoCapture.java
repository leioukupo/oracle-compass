package com.magneo.compass.web;

import android.content.Context;
import android.util.Log;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * root screencap -> 命名管道（FIFO）帧交接：每帧固定 2,560,012 字节（12 头 + 800x800x4）。
 * FIFO 建在应用数据目录（app_data_file 标签，应用可读），root 写、应用读，
 * 写入端写完一整帧才写下一帧，读取端按帧长 readFully，天然原子。
 */
public class FifoCapture {
    private static final String TAG = "FifoCapture";
    public static final int FRAME_LEN = 12 + 800 * 800 * 4; // 2560012

    private FifoCapture() {}

    private static String fifo(Context ctx) {
        return ctx.getFilesDir() + "/truth.fifo";
    }

    /** 创建 FIFO（root mknod，落在应用数据目录自动带 app_data_file 标签）并拉起 root 写循环。 */
    public static Process start(Context ctx) throws Exception {
        String path = fifo(ctx);
        execSu("rm -f " + path + "; mknod " + path + " p; chmod 666 " + path);
        Process shell = Runtime.getRuntime().exec(new String[]{"su", "-c",
                "while true; do screencap > " + path + " || exit; done"});
        return shell;
    }

    /** 打开读端（阻塞直到写端就绪）。 */
    public static FileInputStream openReader(Context ctx) throws Exception {
        return new FileInputStream(fifo(ctx));
    }

    /** 读取一帧；EOF/中断返回 false。 */
    public static boolean readFrame(InputStream in, byte[] buf) {
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) return false;
                if (n == 0) { Thread.sleep(2); continue; }
                off += n;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "readFrame", e);
            return false;
        }
    }

    public static void stop(Context ctx, Process shell) {
        String path = fifo(ctx);
        try { if (shell != null) shell.destroy(); } catch (Exception ignored) {}
        execSu("pkill -f '" + path + "' 2>/dev/null; rm -f " + path);
    }

    private static void execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception ignored) {}
    }
}
