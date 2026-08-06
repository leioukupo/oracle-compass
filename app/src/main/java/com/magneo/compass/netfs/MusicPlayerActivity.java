package com.magneo.compass.netfs;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

/** 音乐播放器（远程流/本地），圆屏罗盘风格：内容收在正圆内，曲目列表当前项金色高亮。 */
public class MusicPlayerActivity extends com.magneo.compass.BaseActivity implements MusicService.Listener {

    private TextView title, pos;
    private SeekBar seek;
    private Button play;
    private LinearLayout playlistBox;
    private int lastIdx = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new com.magneo.compass.BackButton(this));

        // 正圆内容区：列表 + 进度 + 控制都收在圆内
        int size = (int) (Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.90f);
        FrameLayout area = new FrameLayout(this);
        area.setClipToOutline(true);
        area.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        int pad = dp2(22);
        center.setPadding(pad, pad, pad, pad);

        title = new TextView(this);
        title.setTextColor(Color.rgb(212, 175, 55));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        center.addView(title);

        playlistBox = new LinearLayout(this);
        playlistBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scv = new ScrollView(this);
        scv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scv.addView(playlistBox, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        center.addView(scv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        pos = new TextView(this);
        pos.setTextColor(Color.rgb(232, 220, 192));
        pos.setGravity(Gravity.CENTER);
        center.addView(pos);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && MusicService.get() != null) MusicService.get().seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp2(18); slp.rightMargin = dp2(18);
        center.addView(seek, slp);

        LinearLayout row = new LinearLayout(this);
        Button prev = new Button(this); prev.setText("上一首");
        prev.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        prev.setTextColor(Color.rgb(232, 220, 192));
        play = new Button(this); play.setText("播放/暂停");
        play.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_gold);
        play.setTextColor(Color.rgb(232, 220, 192));
        Button next = new Button(this); next.setText("下一首");
        next.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        next.setTextColor(Color.rgb(232, 220, 192));
        prev.setOnClickListener(v -> { if (MusicService.get() != null) MusicService.get().prev(); });
        play.setOnClickListener(v -> { if (MusicService.get() != null) MusicService.get().toggle(); });
        next.setOnClickListener(v -> { if (MusicService.get() != null) MusicService.get().next(); });
        row.addView(prev); row.addView(play); row.addView(next);
        center.addView(row);

        area.addView(center, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(size, size);
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.topMargin = dp2(4);
        root.addView(area, alp);

        setContentView(root);

        List<String> urls = getIntent().getStringArrayListExtra("urls");
        if (urls == null || urls.isEmpty()) {
            title.setText("未选择音频，请从网盘文件列表进入");
            return;
        }
        MusicService.startPlaylist(this, urls);
    }

    private void renderPlaylist(int current) {
        playlistBox.removeAllViews();
        MusicService svc = MusicService.get();
        if (svc == null) return;
        List<String> urls = svc.playlist();
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            String name = urls.get(i);
            String n = name.substring(name.lastIndexOf('/') + 1);
            if (n.isEmpty()) n = name;
            MarqueeText tv = new MarqueeText(this);
            tv.setText((i + 1) + ". " + n);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp2(5), 0, dp2(5));
            tv.setTextColor(i == current ? Color.rgb(212, 175, 55) : Color.rgb(232, 220, 192));
            tv.setOnClickListener(v -> { if (MusicService.get() != null) MusicService.get().playAt(idx); });
            playlistBox.addView(tv);
        }
    }

    private int dp2(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() {
        super.onResume();
        if (MusicService.get() != null) MusicService.get().setListener(this);
    }

    @Override protected void onPause() {
        super.onPause();
        if (MusicService.get() != null) MusicService.get().setListener(null);
    }

    @Override protected void onDestroy() {
        if (MusicService.get() != null) MusicService.get().setListener(null);
        super.onDestroy();
    }

    /** 单行跑马灯文本：isFocused() 恒真让超长曲目名自动滚动，且不抢占点击。 */
    private static class MarqueeText extends TextView {
        MarqueeText(android.content.Context c) {
            super(c);
            setSingleLine(true);
            setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            setMarqueeRepeatLimit(-1);
        }
        @Override public boolean isFocused() { return true; }
    }

    @Override public void onState(String t, boolean playing, int p, int d, int idx) {
        title.setText(t);
        pos.setText((p / 1000) + "s / " + (d / 1000) + "s");
        seek.setMax(Math.max(d, 1));
        seek.setProgress(p);
        play.setText(playing ? "暂停" : "播放");
        if (idx != lastIdx) { lastIdx = idx; renderPlaylist(idx); }
    }
}
