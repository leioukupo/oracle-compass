package com.magneo.compass.netfs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 音乐播放器（远程流/本地），罗盘风格迷你控制。 */
public class MusicPlayerActivity extends com.magneo.compass.BaseActivity implements MusicService.Listener {

    private TextView title, pos;
    private SeekBar seek;
    private Button play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.addView(new com.magneo.compass.BackButton(this));
        root.setBackgroundColor(Color.rgb(10, 10, 10));

        title = new TextView(this);
        title.setTextColor(Color.rgb(212, 175, 55));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pos = new TextView(this);
        pos.setTextColor(Color.rgb(232, 220, 192));
        pos.setGravity(Gravity.CENTER);
        root.addView(pos);

        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && MusicService.get() != null) MusicService.get().seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(seek);

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
        root.addView(row);

        setContentView(root);

        List<String> urls = getIntent().getStringArrayListExtra("urls");
        if (urls == null || urls.isEmpty()) {
            title.setText("未选择音频，请从网盘文件列表进入");
            return;
        }
        MusicService.startPlaylist(this, urls);
    }

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

    @Override public void onState(String t, boolean playing, int p, int d) {
        title.setText(t);
        pos.setText((p / 1000) + "s / " + (d / 1000) + "s");
        seek.setMax(Math.max(d, 1));
        seek.setProgress(p);
        play.setText(playing ? "暂停" : "播放");
    }
}
