package com.magneo.compass.netfs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐播放器（圆屏 Music 式）：大圆封面 + 圆进度环 + 居中播放键 + 顶居中 ⋯（歌单/上一首/下一首）。
 * 没有 linear SeekBar / 三按钮横排 / 长行歌单，遵循系统 com.android.music 范式。
 */
public class MusicPlayerActivity extends com.magneo.compass.BaseActivity implements MusicService.Listener {

    private PieceView piece;            // 中央自绘三件套
    private Button overflow;            // 顶居中 ⋯
    private final ExecutorService artExec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // 顶带：BackButton 居中（圆屏在顶带窄，仅留一个居中主键）
        root.addView(new com.magneo.compass.BackButton(this));

        // 中带：三件套自绘 view 占满中带
        piece = new PieceView(this);
        piece.setOnClickListener(v -> { if (MusicService.get() != null) MusicService.get().toggle(); });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(piece, plp);

        // 底带：单居中 ⋯ 圆键，弹歌单/上一首/下一首
        overflow = new Button(this);
        overflow.setText("⋯");
        overflow.setTextColor(Color.rgb(232, 220, 192));
        overflow.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        int tb = com.magneo.compass.ui.Ui.dp(this, 44);
        LinearLayout obar = new LinearLayout(this);
        obar.setGravity(Gravity.CENTER_HORIZONTAL);
        obar.addView(overflow, new LinearLayout.LayoutParams(tb, tb));
        root.addView(obar);

        overflow.setOnClickListener(v -> {
            com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("歌单");
            MusicService svc = MusicService.get();
            if (svc == null) { d.text("未加载歌单").cancel().show(); return; }
            List<String> urls = svc.playlist();
            int cur = svc.currentIndex();
            for (int i = 0; i < urls.size(); i++) {
                final int idx = i;
                String n = urls.get(i);
                n = n.substring(n.lastIndexOf('/') + 1);
                if (n.isEmpty()) n = urls.get(i);
                d.item((i == cur ? "♪ " : "   ") + n, () -> {
                    if (MusicService.get() != null) MusicService.get().playAt(idx);
                });
            }
            d.item("上一首", () -> { if (MusicService.get() != null) MusicService.get().prev(); });
            d.item("下一首", () -> { if (MusicService.get() != null) MusicService.get().next(); });
            d.cancel().show();
        });

        setContentView(root);

        List<String> urls = getIntent().getStringArrayListExtra("urls");
        if (urls == null || urls.isEmpty()) {
            // 无外部传入歌单，扫描本地音频文件
            urls = scanLocalAudio();
            if (urls.isEmpty()) {
                Toast.makeText(this, "未找到本地音频文件，请从网盘文件列表进入", Toast.LENGTH_LONG).show();
                return;
            }
        }
        MusicService.startPlaylist(this, urls);
    }

    /** 扫描 /sdcard 下常见目录的音频文件 */
    private List<String> scanLocalAudio() {
        List<String> result = new ArrayList<>();
        String[] dirs = {"/sdcard/Music", "/sdcard/Download", "/sdcard"};
        String[] exts = {".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac"};
        for (String dir : dirs) {
            File d = new File(dir);
            if (!d.isDirectory()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase();
                for (String ext : exts) {
                    if (name.endsWith(ext)) {
                        result.add(f.getAbsolutePath());
                        break;
                    }
                }
            }
        }
        Collections.sort(result);
        return result;
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
        artExec.shutdownNow();
        if (MusicService.get() != null) MusicService.get().setListener(null);
        super.onDestroy();
    }

    @Override public void onState(String t, boolean playing, int p, int d, int idx) {
        piece.update(t, playing, p, d);
        piece.postInvalidate();
        // 标题变了就尝试抓封面（适用于 http(s) url 流；非 http 不抓）
        if (!t.equals(piece.lastTitleForArt)) {
            piece.lastTitleForArt = t;
            tryFetchArt(t, idx);
        }
    }

    // 尝试从同目录抓 cover.jpg / folder.jpg / <曲名去扩展>.jpg 给封面，找不到保持金色默认
    private void tryFetchArt(final String title, final int idx) {
        MusicService svc = MusicService.get();
        if (svc == null) return;
        List<String> urls = svc.playlist();
        if (idx < 0 || idx >= urls.size()) return;
        String cur = urls.get(idx);
        String dir = cur.substring(0, cur.lastIndexOf('/') + 1);
        String tn = title;
        int dot = tn.lastIndexOf('.');
        if (dot > 0) tn = tn.substring(0, dot);
        String[] candidates = {dir + "cover.jpg", dir + "folder.jpg", dir + tn + ".jpg"};
        artExec.execute(() -> {
            for (String cand : candidates) {
                try {
                    Bitmap b = fetchBitmap(cand);
                    if (b != null) {
                        runOnUiThread(() -> piece.setArt(b));
                        return;
                    }
                } catch (Exception ignored) {}
            }
            runOnUiThread(() -> piece.setArt(null));
        });
    }

    private Bitmap fetchBitmap(String u) throws Exception {
        if (!u.startsWith("http")) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(4000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return BitmapFactory.decodeByteArray(bo.toByteArray(), 0, bo.size());
        } finally { c.disconnect(); }
    }

    /** Canvas 自绘三件套：大圆封面（或金色罗盘纹）+ 圆进度环 + 中央播放/暂停键。 */
    private static class PieceView extends View {
        private String title = "";
        private boolean playing = false;
        private int pos = 0, dur = 1;
        private Bitmap art;
        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String lastTitleForArt = "";

        PieceView(Context c) {
            super(c);
            pStroke.setStyle(Paint.Style.STROKE);
            pText.setColor(Color.rgb(232, 220, 192));
        }

        void update(String t, boolean pl, int p, int d) {
            title = t == null ? "" : t;
            playing = pl;
            pos = p; dur = Math.max(d, 1);
        }

        void setArt(Bitmap b) {
            if (b != null && !b.isRecycled()) art = b;
            else art = null;
            postInvalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float R = com.magneo.compass.ui.RoundScreen.R(w, h);
            float s = com.magneo.compass.ui.RoundScreen.scale800(w, h);

            // 中带在圆内，留 top/back 与 bottom/overflow 余地；最大不超过 R*0.78
            float coverR = R * 0.62f;

            // —— 大圆封面（无图则金色罗盘纹）
            if (art != null && !art.isRecycled()) {
                RectF dst = new RectF(cx - coverR, cy - coverR, cx + coverR, cy + coverR);
                pFill.setColor(Color.rgb(14, 14, 14));
                c.drawCircle(cx, cy, coverR, pFill);
                c.drawBitmap(art, null, dst, null);
            } else {
                // 金色罗盘圆底
                pFill.setColor(Color.rgb(28, 24, 16));
                c.drawCircle(cx, cy, coverR, pFill);
                pStroke.setColor(Color.rgb(212, 175, 55));
                pStroke.setStrokeWidth(3f * s);
                c.drawCircle(cx, cy, coverR, pStroke);
                // 中央金线八向蛛网纹
                pStroke.setStrokeWidth(1.5f * s);
                pStroke.setColor(Color.rgb(150, 122, 42));
                for (int i = 0; i < 8; i++) {
                    float a = (float) Math.toRadians(i * 45);
                    c.drawLine(cx, cy,
                            cx + (float) Math.cos(a) * coverR * 0.85f,
                            cy + (float) Math.sin(a) * coverR * 0.85f, pStroke);
                }
                c.drawCircle(cx, cy, coverR * 0.5f, pStroke);
            }

            // —— 圆进度环（封面外圈，外径 = coverR*1.18）
            float ringR = coverR * 1.18f;
            pStroke.setStyle(Paint.Style.STROKE);
            // 轨道
            pStroke.setColor(Color.rgb(40, 36, 26));
            pStroke.setStrokeWidth(6f * s);
            c.drawCircle(cx, cy, ringR, pStroke);
            // 进度弧
            float sweep = 360f * (pos / (float) dur);
            RectF arc = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            pStroke.setColor(Color.rgb(212, 175, 55));
            pStroke.setStrokeWidth(6f * s);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(arc, -90f, sweep, false, pStroke);
            pStroke.setStrokeCap(Paint.Cap.BUTT);

            // —— 中央居中播放/暂停键（直径 coverR*0.45，圆心与封面同心）
            float btnR = coverR * 0.45f;
            pFill.setColor(Color.argb(180, 14, 14, 14));
            c.drawCircle(cx, cy, btnR, pFill);
            pStroke.setColor(Color.rgb(212, 175, 55));
            pStroke.setStrokeWidth(2.5f * s);
            c.drawCircle(cx, cy, btnR, pStroke);
            // 三角/双竖条用 Path / drawRect
            float tri = btnR * 0.42f;
            if (playing) {
                // 双竖条
                float bw = btnR * 0.18f, bh = btnR * 0.7f;
                pFill.setColor(Color.rgb(212, 175, 55));
                c.drawRect(cx - btnR * 0.36f, cy - bh / 2f, cx - btnR * 0.36f + bw, cy + bh / 2f, pFill);
                c.drawRect(cx + btnR * 0.36f - bw, cy - bh / 2f, cx + btnR * 0.36f, cy + bh / 2f, pFill);
            } else {
                // 三角（向右）
                android.graphics.Path triP = new android.graphics.Path();
                float left = cx - tri * 0.6f, top = cy - tri, bottom = cy + tri, right = cx + tri * 0.9f;
                triP.moveTo(left, top);
                triP.lineTo(left, bottom);
                triP.lineTo(right, cy);
                triP.close();
                pFill.setColor(Color.rgb(212, 175, 55));
                c.drawPath(triP, pFill);
            }

            // —— 曲名：封面下方、进度环下方
            pText.setColor(Color.rgb(232, 220, 192));
            pText.setTextSize(17f * s);
            String t = title.isEmpty() ? "未在播放" : title;
            float maxW = R * 1.6f;
            if (pText.measureText(t) > maxW) {
                // 截断 + …
                int n = t.length();
                while (n > 1 && pText.measureText(t.substring(0, n) + "…") > maxW) n--;
                t = t.substring(0, n) + "…";
            }
            float y = cy + coverR + 36f * s;
            c.drawText(t, cx - pText.measureText(t) / 2f, y, pText);

            // —— mm:ss / mm:ss 在曲名下方
            pText.setTextSize(14f * s);
            pText.setColor(Color.rgb(150, 142, 122));
            String time = fmt(pos) + " / " + fmt(dur);
            c.drawText(time, cx - pText.measureText(time) / 2f, y + 22f * s, pText);
        }

        private static String fmt(int ms) {
            int s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }
    }
}