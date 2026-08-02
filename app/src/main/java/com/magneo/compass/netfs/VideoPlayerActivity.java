package com.magneo.compass.netfs;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.VideoView;

/** 视频播放器（走本地流代理，圆形遮罩）。 */
public class VideoPlayerActivity extends com.magneo.compass.BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra("url");
        VideoView vv = new VideoView(this);
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
        wrap.addView(vv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        vv.setVideoURI(Uri.parse(url));
        setContentView(wrap);
        vv.setOnPreparedListener(m -> vv.start());
        vv.setOnErrorListener((m, what, extra) -> {
            android.widget.Toast.makeText(this, "视频播放失败", android.widget.Toast.LENGTH_LONG).show();
            return true;
        });
    }
}
