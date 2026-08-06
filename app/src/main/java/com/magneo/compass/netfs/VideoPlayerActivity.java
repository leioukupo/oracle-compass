package com.magneo.compass.netfs;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** 视频播放器（走本地流代理，圆形遮罩）。
 *  MT6580 硬解上限为 H.264 ≤1080p：HEVC/4K/10bit 无法解码时音频照常播放但画面黑屏，
 *  这里用“视频帧渲染超时”检测，超时或出错就给出明确提示并停止播放，不再尝试 NAS 转码。 */
public class VideoPlayerActivity extends com.magneo.compass.BaseActivity {
    private final Handler h = new Handler();
    private boolean videoStarted = false;
    private TextView hint;
    private VideoView vv;

    private void fail(String msg) {
        hint.setText(msg);
        hint.setVisibility(View.VISIBLE);
        try {
            vv.stopPlayback();
        } catch (Exception ignored) {}
    }

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!videoStarted) {
                fail("视频无法解码：HEVC/4K/10bit 超出本机硬解能力\n（MT6580 仅支持 H.264 ≤1080p）");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra("url");
        vv = new VideoView(this);
        vv.setBackgroundColor(Color.BLACK);
        MediaController mc = new MediaController(this);
        vv.setMediaController(mc);
        mc.setAnchorView(vv);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipToOutline(true);
        wrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        wrap.addView(new com.magneo.compass.BackButton(this));

        FrameLayout videoArea = new FrameLayout(this);
        videoArea.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        hint = new TextView(this);
        hint.setTextColor(Color.rgb(212, 175, 55));
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER;
        videoArea.addView(hint, hlp);
        wrap.addView(videoArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        vv.setVideoURI(Uri.parse(url));
        setContentView(wrap);
        vv.setOnPreparedListener(m -> vv.start());
        vv.setOnInfoListener((m, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                videoStarted = true;
                h.removeCallbacks(timeout);
                hint.setVisibility(View.GONE);
            }
            return false;
        });
        vv.setOnErrorListener((m, what, extra) -> {
            fail("视频无法解码或加载失败：\n本机仅支持 H.264 ≤1080p 的视频格式");
            return true;
        });
        h.postDelayed(timeout, 8000);
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(timeout);
        super.onDestroy();
    }
}
