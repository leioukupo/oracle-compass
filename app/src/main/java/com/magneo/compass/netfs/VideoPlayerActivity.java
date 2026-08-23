package com.magneo.compass.netfs;

import android.graphics.Color;
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
        title = safe(getIntent().getStringExtra("title"));
        if (title.isEmpty()) title = safe(getIntent().getStringExtra("name"));
        size = getIntent().getLongExtra("size", 0);
        if (title.isEmpty() && url != null) title = fileName(url);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        FrameLayout shell = new FrameLayout(this);

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

        FrameLayout videoArea = new FrameLayout(this);
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
        shell.addView(videoArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        int minSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                (int) (minSide * 0.54f), Ui.dp(this, 44),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bLp.bottomMargin = Ui.dp(this, 58);
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
        vv.setVideoURI(Uri.parse(url));
        hdelayedTimeout();
    }

    private void hdelayedTimeout() {
        ui.postDelayed(() -> {
            if (!prepared && !failed) fail("视频缓冲超时：\n网络较慢、远程服务断开，或格式超出本机硬解能力");
        }, 8000);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
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
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
