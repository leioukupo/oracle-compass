package com.magneo.compass.netfs;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.magneo.compass.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 视频播放器：沉浸预览 + 自定义浮层控制 + 轻触/滑动快进。 */
public class VideoPlayerActivity extends com.magneo.compass.BaseActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideChromeTask = () -> setChromeVisible(false);
    private final Runnable progressTask = this::updateProgress;

    private VideoView vv;
    private TextView titleView;
    private TextView hint;
    private LinearLayout bottomBar;
    private Button playBtn;
    private SeekBar seekBar;
    private TextView timeView;

    private String title = "";
    private long size = 0;
    private boolean prepared = false;
    private boolean failed = false;
    private boolean draggingSeek = false;
    private boolean chromeVisible = true;
    private boolean userPaused = false;
    private boolean buffering = false;
    private int durationMs = 0;
    private float downX;
    private float downY;
    private long downAt;
    private String url = "";
    private String connId = "";
    private String remotePath = "";
    private volatile boolean cancelled;
    private volatile boolean downloading;
    private Thread downloadThread;

    private void fail(String msg) {
        if (failed) return;
        failed = true;
        hint.setText(msg);
        hint.setVisibility(View.VISIBLE);
        if (prepared) {
            try { vv.stopPlayback(); } catch (Exception ignored) {}
        }
        prepared = false;
        updatePlayButton();
        setChromeVisible(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra("url");
        connId = safe(getIntent().getStringExtra("connId"));
        remotePath = safe(getIntent().getStringExtra("path"));
        title = safe(getIntent().getStringExtra("title"));
        if (title.isEmpty()) title = safe(getIntent().getStringExtra("name"));
        size = getIntent().getLongExtra("size", 0);
        if (title.isEmpty() && url != null) title = fileName(url);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        FrameLayout shell = new FrameLayout(this);
        int minSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);

        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Ui.COLOR_TEXT);
        titleView.setTextSize(13);
        titleView.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        titleView.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.48f),
                Ui.dp(this, 34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleLp.topMargin = Ui.dp(this, 38);
        shell.addView(titleView, titleLp);

        PlayerStage videoStage = new PlayerStage(this);
        int stagePad = Ui.dp(this, 4);
        videoStage.setPadding(stagePad, stagePad, stagePad, stagePad);
        FrameLayout videoArea = new FrameLayout(this);
        videoArea.setBackgroundColor(Color.BLACK);
        vv = new VideoView(this);
        vv.setBackgroundColor(Color.BLACK);
        videoArea.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        hint = new TextView(this);
        hint.setText("加载中…");
        hint.setTextColor(Ui.COLOR_GOLD);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        videoArea.addView(hint, hlp);

        videoArea.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    downAt = android.os.SystemClock.uptimeMillis();
                    showChromeTransient();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    long dt = android.os.SystemClock.uptimeMillis() - downAt;
                    if (Math.abs(dx) > Ui.dp(this, 42)) {
                        seekBy(dx > 0 ? 10_000 : -10_000);
                    } else if (dt < 350 && Math.abs(dx) < Ui.dp(this, 18) && Math.abs(dy) < Ui.dp(this, 18)) {
                        float w = v.getWidth();
                        if (ev.getX() < w / 3f) seekBy(-10_000);
                        else if (ev.getX() > w * 2f / 3f) seekBy(10_000);
                        else togglePlayPause();
                    }
                    showChromeTransient();
                    return true;
            }
            return true;
        });
        videoStage.addView(videoArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int stageW = (int) (minSide * 0.82f);
        int stageH = (int) (stageW * 9f / 16f);
        int maxStageH = (int) (minSide * 0.48f);
        if (stageH > maxStageH) stageH = maxStageH;
        FrameLayout.LayoutParams stageLp = new FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER);
        shell.addView(videoStage, stageLp);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        bottomBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        playBtn = toolButton("▶");
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgress(0);
        timeView = new TextView(this);
        timeView.setTextColor(Ui.COLOR_TEXT_DIM);
        timeView.setTextSize(12);
        timeView.setSingleLine(true);
        timeView.setText("00:00 / 00:00");
        bottomBar.addView(playBtn, toolLp());
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = Ui.dp(this, 8);
        bottomBar.addView(timeView, timeLp);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                (int) (minSide * 0.58f), Ui.dp(this, 40),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bLp.bottomMargin = Ui.dp(this, 68);
        shell.addView(bottomBar, bLp);

        root.addView(shell);
        titleView.bringToFront();
        bottomBar.bringToFront();
        setContentView(root);

        playBtn.setOnClickListener(v -> togglePlayPause());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) {
                    updateTime(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                draggingSeek = true;
                showChromeTransient();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                draggingSeek = false;
                if (prepared) {
                    int pos = seekBar.getProgress();
                    vv.seekTo(pos);
                    updateTime(pos);
                }
            }
        });

        vv.setOnPreparedListener(m -> {
            failed = false;
            prepared = true;
            durationMs = Math.max(1, vv.getDuration());
            seekBar.setMax(durationMs);
            updateTitle();
            hint.setVisibility(View.GONE);
            buffering = false;
            userPaused = false;
            vv.start();
            updatePlayButton();
            showChromeTransient();
            ui.removeCallbacks(progressTask);
            ui.post(progressTask);
        });
        vv.setOnCompletionListener(m -> {
            userPaused = true;
            updatePlayButton();
            showChromeVisible();
        });
        vv.setOnInfoListener((m, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                hint.setVisibility(View.GONE);
                buffering = false;
                showChromeTransient();
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                buffering = true;
                hint.setText("缓冲中…");
                hint.setVisibility(View.VISIBLE);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                buffering = false;
                if (prepared) hint.setVisibility(View.GONE);
            }
            return false;
        });
        vv.setOnErrorListener((m, what, extra) -> {
            fail(mediaErrorMessage(what, extra));
            return true;
        });

        updateTitle();
        prepareVideoSource();
    }

    /**
     * Android 5.1's MediaPlayer is unreliable when an MP4 is fed through a
     * WebDAV/FTP range proxy (audio may start while the video track stays
     * black).  Downloading to the app cache first uses the same local-file
     * path that is known to decode correctly on this device and avoids
     * server-specific Range/Content-Range behaviour.
     */
    private void prepareVideoSource() {
        if (url == null || url.trim().isEmpty()) {
            fail("视频地址为空");
            return;
        }
        if (connId.isEmpty() || remotePath.isEmpty()
                || !url.toLowerCase(java.util.Locale.US).startsWith("http")) {
            try { vv.setVideoURI(Uri.parse(url)); } catch (Exception e) { fail("视频地址无效"); }
            hdelayedTimeout();
            return;
        }

        final FsManager.Conn cn = FsManager.byId(this, connId);
        if (cn == null) {
            fail("网盘连接已不存在，请返回重试");
            return;
        }
        final File cacheDir = new File(getCacheDir(), "netfs-video");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            fail("无法创建视频缓存目录");
            return;
        }
        final String key = Integer.toHexString((connId + "|" + remotePath + "|" + size).hashCode());
        // Keep the media suffix so the legacy framework can select the right
        // extractor/decoder before it has inspected the file contents.
        final File cached = new File(cacheDir, "video-" + key + mediaSuffix());
        if (size > 0 && cached.isFile() && cached.length() == size) {
            setLocalVideo(cached);
            return;
        }

        downloading = true;
        hint.setText("正在缓存视频…");
        hint.setVisibility(View.VISIBLE);
        downloadThread = new Thread(() -> {
            File part = new File(cacheDir, cached.getName() + ".part");
            NetFs fs = null;
            InputStream in = null;
            FileOutputStream out = null;
            long total = 0;
            long lastUi = 0;
            try {
                if (part.exists()) part.delete();
                fs = FsManager.connect(this, cn);
                in = fs.open(remotePath);
                out = new FileOutputStream(part);
                byte[] buf = new byte[32 * 1024];
                int n;
                while (!cancelled && (n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUi > 400) {
                        lastUi = now;
                        final long done = total;
                        ui.post(() -> {
                            if (!downloading || failed) return;
                            if (size > 0) {
                                int pct = (int) Math.max(0, Math.min(99, done * 100L / size));
                                hint.setText("正在缓存视频… " + pct + "%");
                            } else {
                                hint.setText("正在缓存视频… " + humanSize(done));
                            }
                        });
                    }
                }
                if (cancelled) return;
                if (size > 0 && total != size) throw new java.io.IOException("文件大小不完整");
                out.flush();
                out.close();
                out = null;
                if (cached.exists() && !cached.delete()) throw new java.io.IOException("无法更新缓存");
                if (!part.renameTo(cached)) throw new java.io.IOException("无法保存视频缓存");
                ui.post(() -> {
                    downloading = false;
                    if (!cancelled && !isFinishing()) setLocalVideo(cached);
                });
            } catch (Exception e) {
                if (!cancelled) ui.post(() -> {
                    downloading = false;
                    fail("视频缓存失败：\n" + safeError(e));
                });
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (fs != null) try { fs.close(); } catch (Exception ignored) {}
                if (cancelled && part.exists()) part.delete();
            }
        }, "video-cache");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void setLocalVideo(File file) {
        if (cancelled || file == null || !file.isFile()) return;
        try {
            hint.setText("正在打开视频…");
            hint.setVisibility(View.VISIBLE);
            vv.setVideoURI(Uri.fromFile(file));
            hdelayedTimeout();
        } catch (Exception e) {
            fail("视频文件无效：\n" + safeError(e));
        }
    }

    private String safeError(Exception e) {
        String s = e == null ? "未知错误" : e.getMessage();
        return s == null || s.trim().isEmpty() ? "网络或存储异常" : s;
    }

    private String mediaSuffix() {
        String n = title;
        if (n == null || n.trim().isEmpty()) n = remotePath;
        int dot = n == null ? -1 : n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) {
            String ext = n.substring(dot).toLowerCase(java.util.Locale.US);
            if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
        }
        return ".mp4";
    }

    private void hdelayedTimeout() {
        ui.postDelayed(() -> {
            // A remote MP4 may need a second range request for its index or
            // first key frame. Eight seconds is a useful slow-network signal,
            // but it is not evidence of a format/decoder failure.
            if (!prepared && !failed) {
                hint.setText("网络较慢，继续缓冲中…");
                hint.setVisibility(View.VISIBLE);
            }
        }, 8000);
        ui.postDelayed(() -> {
            if (!prepared && !failed) {
                fail("视频加载超时：\n远程服务响应过慢或连接已断开");
            }
        }, 45000);
    }

    private String mediaErrorMessage(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_TIMED_OUT || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            return "视频加载超时：\n网络较慢或远程文件响应太慢";
        }
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            return "视频服务异常：\n请返回后重新打开";
        }
        return "视频无法播放：\n可能是格式不支持、网络中断或文件损坏";
    }

    private void updatePlayButton() {
        playBtn.setText(prepared && vv.isPlaying() ? "⏸" : "▶");
    }

    private void updateTime(int pos) {
        timeView.setText(formatTime(pos) + " / " + formatTime(durationMs));
    }

    private void updateProgress() {
        if (!prepared) return;
        if (!draggingSeek) {
            int pos = vv.getCurrentPosition();
            seekBar.setProgress(pos);
            updateTime(pos);
        }
        updatePlayButton();
        if (chromeVisible && vv.isPlaying()) {
            ui.removeCallbacks(progressTask);
            ui.postDelayed(progressTask, 400);
        }
    }

    private void seekBy(int deltaMs) {
        if (!prepared) return;
        int pos = Math.max(0, Math.min(durationMs, vv.getCurrentPosition() + deltaMs));
        vv.seekTo(pos);
        seekBar.setProgress(pos);
        updateTime(pos);
        hint.setText(deltaMs > 0 ? "快进 10 秒" : "快退 10 秒");
        hint.setVisibility(View.VISIBLE);
        showChromeTransient();
        ui.removeCallbacks(progressTask);
        ui.postDelayed(progressTask, 100);
    }

    private void togglePlayPause() {
        if (!prepared) return;
        if (vv.isPlaying()) {
            vv.pause();
            userPaused = true;
            hint.setText("已暂停");
            hint.setVisibility(View.VISIBLE);
        } else {
            vv.start();
            userPaused = false;
            hint.setVisibility(View.GONE);
            ui.removeCallbacks(progressTask);
            ui.post(progressTask);
        }
        updatePlayButton();
        showChromeTransient();
    }

    private void showChromeVisible() {
        chromeVisible = true;
        bottomBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideChromeTask);
        ui.postDelayed(hideChromeTask, 2600);
    }

    private void setChromeVisible(boolean visible) {
        chromeVisible = visible;
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void showChromeTransient() {
        setChromeVisible(true);
        ui.removeCallbacks(hideChromeTask);
        if (prepared && vv.isPlaying()) {
            ui.removeCallbacks(progressTask);
            ui.post(progressTask);
        }
        ui.postDelayed(hideChromeTask, 2600);
    }

    private Button toolButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        Ui.styleIconButton(b);
        b.setTextSize(17);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private LinearLayout.LayoutParams toolLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
        lp.rightMargin = Ui.dp(this, 6);
        return lp;
    }

    private String formatTime(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int min = total / 60;
        int sec = total % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", min, sec);
    }

    private void updateTitle() {
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append(title);
        if (size > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(humanSize(size));
        }
        titleView.setText(sb.length() == 0 ? "视频" : sb.toString());
    }

    private String humanSize(long s) {
        if (s < 1024) return s + " B";
        if (s < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", s / 1024f);
        if (s < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", s / 1024f / 1024f);
        return String.format(java.util.Locale.US, "%.1f GB", s / 1024f / 1024f / 1024f);
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }

    private String fileName(String u) {
        try {
            String p = Uri.parse(u).getPath();
            if (p == null) return u;
            int i = p.lastIndexOf('/');
            return i >= 0 ? p.substring(i + 1) : p;
        } catch (Exception e) {
            return u;
        }
    }

    @Override protected void onDestroy() {
        cancelled = true;
        downloading = false;
        if (downloadThread != null) downloadThread.interrupt();
        ui.removeCallbacksAndMessages(null);
        try { vv.stopPlayback(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private static class PlayerStage extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        PlayerStage(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(Color.argb(190, 2, 2, 2));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            rect.set(d, d, getWidth() - d, getHeight() - d);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(Color.argb(175, 210, 171, 69));
            canvas.drawRoundRect(rect, 12f * d, 12f * d, paint);
        }
    }
}
