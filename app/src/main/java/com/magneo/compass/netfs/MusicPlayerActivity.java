package com.magneo.compass.netfs;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.Prefs;
import com.magneo.compass.RoundDialog;
import com.magneo.compass.ui.RoundScreen;
import com.magneo.compass.ui.Ui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 音乐播放器：罗盘唱片机，大圆唱片 + 进度环 + 圆屏安全区歌单入口。 */
public class MusicPlayerActivity extends com.magneo.compass.BaseActivity implements MusicService.Listener {

    private static final int MAX_LOCAL_TRACKS = 200;
    private static final int MAX_DIALOG_TRACKS = 48;
    private static final long LIKELY_SONG_SIZE = 512L * 1024L;
    private static final long LIKELY_SONG_MS = 15_000L;
    private static final int SIDE_SEEK_MS = 15_000;

    private PieceView piece;
    private ArrayList<String> currentUrls = new ArrayList<>();
    private ArrayList<MusicTrack> currentTracks = new ArrayList<>();
    private boolean playingState = false;
    private final ExecutorService artExec = Executors.newSingleThreadExecutor();
    private final ExecutorService cloudExec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int cloudRequestSerial = 0;
    private int qrLoginSerial = 0;
    private TextView qrStatusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG_DEEP);
        root.addView(new com.magneo.compass.CompassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        piece = new PieceView(this);
        root.addView(piece, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Button prev = sideButton("‹");
        prev.setOnClickListener(v -> skipTrack(-1));
        prev.setOnLongClickListener(v -> {
            seekSide(-1);
            return true;
        });
        FrameLayout.LayoutParams prevLp = new FrameLayout.LayoutParams(Ui.dp(this, 54),
                Ui.dp(this, 54), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        prevLp.leftMargin = Ui.dp(this, 34);
        root.addView(prev, prevLp);

        Button next = sideButton("›");
        next.setOnClickListener(v -> skipTrack(1));
        next.setOnLongClickListener(v -> {
            seekSide(1);
            return true;
        });
        FrameLayout.LayoutParams nextLp = new FrameLayout.LayoutParams(Ui.dp(this, 54),
                Ui.dp(this, 54), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        nextLp.rightMargin = Ui.dp(this, 34);
        root.addView(next, nextLp);

        Button back = chromeButton("返回");
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(this, 78),
                Ui.dp(this, 38), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = Ui.dp(this, 18);
        root.addView(back, blp);

        Button menu = chromeButton("歌单");
        menu.setOnClickListener(v -> showPlaylistMenu());
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(this, 86),
                Ui.dp(this, 42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mlp.bottomMargin = Ui.dp(this, 18);
        root.addView(menu, mlp);

        setContentView(root);

        ArrayList<String> urls = getIntent().getStringArrayListExtra("urls");
        if (urls == null || urls.isEmpty()) {
            MusicService svc = MusicService.get();
            if (svc != null && !svc.tracks().isEmpty()) restoreServicePlaylist(svc);
            else if (Prefs.MUSIC_SOURCE_NETEASE.equals(Prefs.musicSource(this))) {
                loadCloudHome(false);
            } else {
                reloadLocalPlaylist(false);
            }
        } else {
            startPlaylist(urls);
        }
    }

    private Button chromeButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Ui.COLOR_TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundResource(com.magneo.compass.R.drawable.bg_pill_dark);
        return b;
    }

    private Button sideButton(String s) {
        Button b = chromeButton(s);
        b.setTextSize(30);
        b.setTextColor(Ui.COLOR_GOLD);
        b.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, Ui.dp(this, 3));
        return b;
    }

    private void startPlaylist(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            setEmptyState("未找到本地音乐");
            return;
        }
        currentUrls = new ArrayList<>(urls);
        currentTracks = new ArrayList<>();
        for (String url : urls) currentTracks.add(MusicTrack.local(url, displayName(url)));
        Prefs.put(this, Prefs.K_MUSIC_SOURCE, Prefs.MUSIC_SOURCE_LOCAL);
        piece.setTrackCount(urls.size());
        piece.setEmptyText("");
        piece.update(trackTitle(currentTracks.get(0)), false, 0,
                (int) Math.max(1L, currentTracks.get(0).durationMs), 0);
        MusicService.startTracks(this, currentTracks);
        piece.postDelayed(() -> {
            if (MusicService.get() != null) MusicService.get().setListener(this);
        }, 250);
    }

    private void restoreServicePlaylist(MusicService svc) {
        currentTracks = new ArrayList<>(svc.tracks());
        if (currentTracks.isEmpty()) {
            currentUrls = new ArrayList<>(svc.playlist());
            for (String url : currentUrls) currentTracks.add(MusicTrack.local(url, displayName(url)));
        } else {
            currentUrls = new ArrayList<>();
            for (MusicTrack track : currentTracks) currentUrls.add(track.url);
        }
        boolean cloud = !currentTracks.isEmpty() && currentTracks.get(0).isNetease();
        Prefs.put(this, Prefs.K_MUSIC_SOURCE,
                cloud ? Prefs.MUSIC_SOURCE_NETEASE : Prefs.MUSIC_SOURCE_LOCAL);
        piece.setTrackCount(currentTracks.size());
        piece.setEmptyText("");
        MusicTrack current = svc.currentTrack();
        if (current != null) piece.update(trackTitle(current), svc.isPlaying(), 0,
                (int) Math.max(1L, current.durationMs), svc.currentIndex());
        svc.setListener(this);
    }

    private void reloadLocalPlaylist(boolean toast) {
        List<String> urls = scanLocalAudio();
        if (urls.isEmpty()) {
            setEmptyState("未找到本地音乐");
            if (toast) Toast.makeText(this, "未找到本地音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        if (toast) Toast.makeText(this, "找到 " + urls.size() + " 首", Toast.LENGTH_SHORT).show();
        startPlaylist(urls);
    }

    private void setEmptyState(String text) {
        currentUrls.clear();
        currentTracks.clear();
        piece.setTrackCount(0);
        piece.setEmptyText(text);
        piece.update("", false, 0, 1, 0);
    }

    private void togglePlaybackFromUi() {
        MusicService svc = MusicService.get();
        if (playingState && svc != null) {
            svc.toggle();
            return;
        }
        if (currentTracks.isEmpty()) showPlaylistMenu();
        else playTrackFromUi(piece.currentIndex);
    }

    private void playTrackFromUi(int idx) {
        MusicService svc = MusicService.get();
        List<MusicTrack> serviceTracks = svc == null ? new ArrayList<>() : svc.tracks();
        List<MusicTrack> tracks = serviceTracks.isEmpty() ? currentTracks : serviceTracks;
        if (tracks.isEmpty()) {
            showPlaylistMenu();
            return;
        }
        int startIndex = Math.max(0, Math.min(tracks.size() - 1, idx));
        playingState = true;
        MusicTrack track = tracks.get(startIndex);
        piece.update(trackTitle(track), track.artist, true, 0,
                (int) Math.max(1L, track.durationMs), startIndex);
        piece.postInvalidate();
        MusicService.startTracks(this, tracks, true, startIndex);
        piece.postDelayed(() -> {
            if (MusicService.get() != null) MusicService.get().setListener(this);
        }, 250);
    }

    private void seekSide(int dir) {
        MusicService svc = MusicService.get();
        if (svc != null) {
            svc.seekRelative(dir * SIDE_SEEK_MS);
        } else if (currentTracks.isEmpty()) {
            showPlaylistMenu();
        }
    }

    private void skipTrack(int dir) {
        MusicService svc = MusicService.get();
        List<MusicTrack> serviceTracks = svc == null ? new ArrayList<>() : svc.tracks();
        List<MusicTrack> tracks = serviceTracks.isEmpty() ? currentTracks : serviceTracks;
        if (tracks.isEmpty()) {
            showPlaylistMenu();
            return;
        }
        if (tracks.size() < 2) return;

        int cur = svc == null ? piece.currentIndex : svc.currentIndex();
        int next = ((cur + dir) % tracks.size() + tracks.size()) % tracks.size();
        if (serviceTracks.isEmpty()) currentTracks = new ArrayList<>(tracks);
        if (playingState && svc != null) {
            if (dir < 0) svc.prev();
            else svc.next();
        } else {
            piece.update(trackTitle(tracks.get(next)), false, 0,
                    (int) Math.max(1L, tracks.get(next).durationMs), next);
            piece.postInvalidate();
            MusicService.startTracks(this, tracks, false, next);
            piece.postDelayed(() -> {
                if (MusicService.get() != null) MusicService.get().setListener(this);
            }, 250);
        }
    }

    private void showPlaylistMenu() {
        MusicService svc = MusicService.get();
        List<MusicTrack> serviceTracks = svc == null ? new ArrayList<>() : svc.tracks();
        List<MusicTrack> tracks = serviceTracks.isEmpty() ? currentTracks : serviceTracks;
        RoundDialog d = new RoundDialog(this).title("音乐");
        String source = tracks.isEmpty()
                ? Prefs.musicSource(this)
                : (tracks.get(0).isNetease() ? Prefs.MUSIC_SOURCE_NETEASE : Prefs.MUSIC_SOURCE_LOCAL);
        d.text(Prefs.MUSIC_SOURCE_NETEASE.equals(source) ? "网易云音乐" : "本地 / 网盘音乐");

        if (!tracks.isEmpty()) {
            int cur = svc == null ? piece.currentIndex : svc.currentIndex();
            d.text((cur + 1) + " / " + tracks.size());
            int n = Math.min(tracks.size(), MAX_DIALOG_TRACKS);
            for (int i = 0; i < n; i++) {
                final int trackIndex = i;
                MusicTrack track = tracks.get(i);
                String label = compact((i == cur ? "♪ " : "") + trackTitle(track), 22);
                d.item(label, () -> playTrackFromUi(trackIndex));
            }
            if (tracks.size() > MAX_DIALOG_TRACKS) d.text("仅显示前 " + MAX_DIALOG_TRACKS + " 首");
            if (tracks.size() > 1) {
                d.item("上一首", () -> { if (MusicService.get() != null) MusicService.get().prev(); });
                d.item("下一首", () -> { if (MusicService.get() != null) MusicService.get().next(); });
            }
            d.item("停止播放", () -> { if (MusicService.get() != null) MusicService.get().stopPlayback(); });
        }

        d.item(Prefs.MUSIC_SOURCE_NETEASE.equals(source) ? "切换到本地 / 网盘" : "切换到网易云",
                () -> {
                    if (Prefs.MUSIC_SOURCE_NETEASE.equals(source)) {
                        switchToLocal();
                    } else {
                        switchToCloud();
                    }
                });
        if (Prefs.MUSIC_SOURCE_NETEASE.equals(source)) {
            d.item("网易云功能", this::showCloudMenu);
        } else {
            d.item("重新扫描本地", () -> reloadLocalPlaylist(true));
            d.item("打开网盘", () -> startActivity(new Intent(this, FileBrowserActivity.class)));
        }
        d.cancel().show();
    }

    private void showCloudMenu() {
        if (!new NeteaseMusicApi(this).isConfigured()) {
            new RoundDialog(this)
                    .title("网易云音乐")
                    .text("未配置网易云 Enhanced API 地址")
                    .item("配置服务", this::showCloudSettings)
                    .item("切换到本地 / 网盘", this::switchToLocal)
                    .cancel()
                    .show();
            return;
        }

        final boolean cookieSet = !Prefs.neteaseCookie(this).isEmpty();
        final boolean loggedIn = cookieSet
                && !Prefs.get(this, Prefs.K_NETEASE_UID, "").trim().isEmpty();
        RoundDialog d = new RoundDialog(this).title("网易云音乐");
        String nickname = Prefs.get(this, Prefs.K_NETEASE_NICKNAME, "").trim();
        d.text(loggedIn ? "已登录" + (nickname.isEmpty() ? "" : " · " + compact(nickname, 14))
                : cookieSet ? "已保存 Cookie · 登录状态未校验" : "未登录 · 匿名访问");
        d.item("搜索歌曲", this::searchCloudSongs);
        d.item("搜索歌单", this::searchCloudPlaylists);
        d.item("推荐歌单", this::loadPersonalizedPlaylists);
        if (loggedIn) {
            d.item("每日推荐", this::loadRecommendedSongs);
            d.item("我的歌单", this::loadMyPlaylists);
            d.item("刷新登录状态", this::refreshCloudLogin);
            d.item("退出登录", this::logoutCloud);
        } else {
            d.item("二维码登录", this::startQrLogin);
            if (cookieSet) {
                d.item("刷新登录状态", this::refreshCloudLogin);
                d.item("退出登录", this::logoutCloud);
            }
        }
        d.item("网易云设置", this::showCloudSettings);
        d.item("切换到本地 / 网盘", this::switchToLocal);
        d.cancel().show();
    }

    private void switchToLocal() {
        Prefs.put(this, Prefs.K_MUSIC_SOURCE, Prefs.MUSIC_SOURCE_LOCAL);
        MusicService svc = MusicService.get();
        if (svc != null) svc.clearPlaylist();
        reloadLocalPlaylist(false);
    }

    private void switchToCloud() {
        Prefs.put(this, Prefs.K_MUSIC_SOURCE, Prefs.MUSIC_SOURCE_NETEASE);
        MusicService svc = MusicService.get();
        if (svc != null) svc.clearPlaylist();
        loadCloudHome(true);
    }

    private void showCloudSettings() {
        EditText url = new EditText(this);
        url.setHint("http://你的服务器:3000");
        url.setText(Prefs.neteaseApiUrl(this));
        EditText cookie = new EditText(this);
        cookie.setHint("可选 Cookie，留空保持当前");
        cookie.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Spinner quality = new Spinner(this);
        String[] values = {"standard", "higher", "exhigh", "lossless", "hires"};
        String[] labels = {"标准", "较高", "极高", "无损", "Hi-Res"};
        quality.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels));
        quality.setPrompt("播放音质");
        String currentQuality = Prefs.neteaseQuality(this);
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentQuality)) {
                selected = i;
                break;
            }
        }
        quality.setSelection(selected);

        RoundDialog d = new RoundDialog(this).title("网易云设置")
                .text("填写 NeteaseCloudMusicApi Enhanced 地址；Cookie 只用于网易云请求。");
        d.field(url);
        d.field(cookie);
        d.view(quality);
        d.item("保存", () -> {
            Prefs.put(this, Prefs.K_NETEASE_API_URL,
                    NeteaseMusicApi.normalizeBaseUrl(url.getText().toString()));
            String cookieValue = cookie.getText().toString().trim();
            if (!cookieValue.isEmpty()) {
                Prefs.put(this, Prefs.K_NETEASE_COOKIE, cookieValue);
            }
            Prefs.put(this, Prefs.K_NETEASE_QUALITY, values[Math.max(0,
                    Math.min(values.length - 1, quality.getSelectedItemPosition()))]);
            Toast.makeText(this, "网易云设置已保存", Toast.LENGTH_SHORT).show();
            showCloudMenu();
        });
        d.cancel().show();
    }

    private void searchCloudSongs() {
        EditText input = new EditText(this);
        input.setHint("歌曲名、歌手或关键词");
        RoundDialog d = new RoundDialog(this)
                .title("搜索歌曲")
                .field(input)
                .item("搜索", () -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) {
                        Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runCloudRequest("正在搜索歌曲…", () -> new NeteaseMusicApi(this)
                                    .searchSongs(q, 30),
                            songs -> showSongResults("歌曲搜索 · " + compact(q, 12), songs));
                })
                .cancel();
        d.show();
    }

    private void searchCloudPlaylists() {
        EditText input = new EditText(this);
        input.setHint("歌单名或关键词");
        RoundDialog d = new RoundDialog(this)
                .title("搜索歌单")
                .field(input)
                .item("搜索", () -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) {
                        Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runCloudRequest("正在搜索歌单…", () -> new NeteaseMusicApi(this)
                                    .searchPlaylists(q, 20),
                            playlists -> showPlaylistResults("歌单搜索 · " + compact(q, 12), playlists));
                })
                .cancel();
        d.show();
    }

    private void loadCloudHome(boolean fromSwitch) {
        if (!new NeteaseMusicApi(this).isConfigured()) {
            if (fromSwitch) showCloudSettings();
            else setEmptyState("未配置网易云 Enhanced API");
            return;
        }
        if (fromSwitch) {
            showCloudMenu();
        } else {
            loadPersonalizedPlaylists();
        }
    }

    private void loadPersonalizedPlaylists() {
        runCloudRequest("正在加载推荐歌单…",
                () -> new NeteaseMusicApi(this).personalizedPlaylists(20),
                playlists -> showPlaylistResults("推荐歌单", playlists));
    }

    private void loadRecommendedSongs() {
        runCloudRequest("正在加载每日推荐…",
                () -> new NeteaseMusicApi(this).recommendSongs(30),
                songs -> showSongResults("每日推荐", songs));
    }

    private void loadMyPlaylists() {
        runCloudRequest("正在加载我的歌单…",
                () -> new NeteaseMusicApi(this).userPlaylists(),
                playlists -> showPlaylistResults("我的歌单", playlists));
    }

    private void showSongResults(String title, List<MusicTrack> songs) {
        if (songs == null || songs.isEmpty()) {
            Toast.makeText(this, "没有找到歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<MusicTrack> queue = new ArrayList<>(songs);
        RoundDialog d = new RoundDialog(this).title(title)
                .text("共 " + queue.size() + " 首，点击曲目开始播放");
        int n = Math.min(queue.size(), MAX_DIALOG_TRACKS);
        for (int i = 0; i < n; i++) {
            final int index = i;
            MusicTrack track = queue.get(i);
            String label = trackTitle(track);
            if (!track.artist.trim().isEmpty()) label += " · " + track.artist;
            d.item(compact(label, 26), () -> {
                Prefs.put(this, Prefs.K_MUSIC_SOURCE, Prefs.MUSIC_SOURCE_NETEASE);
                startCloudTracks(queue, true, index);
            });
        }
        if (queue.size() > n) d.text("仅显示前 " + n + " 首");
        d.cancel().show();
    }

    private void showPlaylistResults(String title, List<NeteaseMusicApi.Playlist> playlists) {
        if (playlists == null || playlists.isEmpty()) {
            Toast.makeText(this, "没有找到歌单", Toast.LENGTH_SHORT).show();
            return;
        }
        RoundDialog d = new RoundDialog(this).title(title)
                .text("选择歌单查看曲目");
        int n = Math.min(playlists.size(), 20);
        for (int i = 0; i < n; i++) {
            final NeteaseMusicApi.Playlist playlist = playlists.get(i);
            String count = playlist.trackCount > 0 ? " · " + playlist.trackCount + " 首" : "";
            d.item(compact(playlist.name + count, 25),
                    () -> loadPlaylistTracks(playlist));
        }
        if (playlists.size() > n) d.text("仅显示前 " + n + " 个");
        d.cancel().show();
    }

    private void loadPlaylistTracks(NeteaseMusicApi.Playlist playlist) {
        runCloudRequest("正在加载歌单…",
                () -> new NeteaseMusicApi(this).playlistTracks(playlist.id),
                songs -> {
                    if (songs == null || songs.isEmpty()) {
                        Toast.makeText(this, "歌单没有可用歌曲", Toast.LENGTH_SHORT).show();
                    } else {
                        showSongResults(compact(playlist.name, 16), songs);
                    }
                });
    }

    private interface CloudCall<T> {
        T run() throws Exception;
    }

    private interface CloudResult<T> {
        void onResult(T value);
    }

    private <T> void runCloudRequest(String loading, CloudCall<T> call,
                                     CloudResult<T> result) {
        final int serial = ++cloudRequestSerial;
        Toast.makeText(this, loading, Toast.LENGTH_SHORT).show();
        cloudExec.execute(() -> {
            try {
                T value = call.run();
                ui.post(() -> {
                    if (serial != cloudRequestSerial || isFinishing()) return;
                    result.onResult(value);
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? "网易云请求失败" : e.getMessage();
                ui.post(() -> {
                    if (serial != cloudRequestSerial || isFinishing()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startCloudTracks(List<MusicTrack> songs, boolean autoplay, int startIndex) {
        if (songs == null || songs.isEmpty()) {
            setEmptyState("没有可播放歌曲");
            return;
        }
        currentTracks = new ArrayList<>(songs);
        currentUrls.clear();
        for (MusicTrack track : currentTracks) currentUrls.add(track.url);
        Prefs.put(this, Prefs.K_MUSIC_SOURCE, Prefs.MUSIC_SOURCE_NETEASE);
        int index = Math.max(0, Math.min(currentTracks.size() - 1, startIndex));
        piece.setTrackCount(currentTracks.size());
        piece.setEmptyText("");
        MusicTrack selected = currentTracks.get(index);
        piece.update(trackTitle(selected), selected.artist, autoplay, 0,
                (int) Math.max(1L, currentTracks.get(index).durationMs), index);
        MusicService.startTracks(this, currentTracks, autoplay, index);
        piece.postDelayed(() -> {
            if (MusicService.get() != null) MusicService.get().setListener(this);
        }, 250);
    }

    private void startQrLogin() {
        if (!new NeteaseMusicApi(this).isConfigured()) {
            showCloudSettings();
            return;
        }
        final int serial = ++qrLoginSerial;
        Toast.makeText(this, "正在生成登录二维码…", Toast.LENGTH_SHORT).show();
        cloudExec.execute(() -> {
            try {
                NeteaseMusicApi.QrCode qr = new NeteaseMusicApi(this).createQrCode();
                ui.post(() -> {
                    if (serial != qrLoginSerial || isFinishing()) return;
                    showQrLoginDialog(qr, serial);
                });
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "二维码生成失败" : e.getMessage();
                ui.post(() -> {
                    if (serial != qrLoginSerial || isFinishing()) return;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showQrLoginDialog(NeteaseMusicApi.QrCode qr, int serial) {
        byte[] bytes = decodeImageData(qr.imageData);
        Bitmap bitmap = bytes == null ? null
                : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            Toast.makeText(this, "二维码图片无效", Toast.LENGTH_LONG).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setBackgroundResource(com.magneo.compass.R.drawable.bg_rect_gold);
        image.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 190), Ui.dp(this, 190));
        image.setLayoutParams(imageLp);

        TextView status = subtleText("请使用网易云音乐扫码，等待确认");
        status.setGravity(Gravity.CENTER);
        qrStatusView = status;
        RoundDialog d = new RoundDialog(this)
                .title("网易云登录")
                .view(image)
                .view(status)
                .onDismiss(() -> {
                    if (serial == qrLoginSerial) qrLoginSerial++;
                    qrStatusView = null;
                })
                .item("取消", () -> {
                });
        d.show();
        pollQrLogin(qr, serial, d);
    }

    private void pollQrLogin(NeteaseMusicApi.QrCode qr, int serial, RoundDialog dialog) {
        if (serial != qrLoginSerial || isFinishing()) return;
        cloudExec.execute(() -> {
            NeteaseMusicApi.QrStatus status;
            try {
                status = new NeteaseMusicApi(this).checkQrCode(qr.key);
            } catch (Exception e) {
                status = new NeteaseMusicApi.QrStatus(-1,
                        e.getMessage() == null ? "检查登录状态失败" : e.getMessage(), "");
            }
            NeteaseMusicApi.QrStatus result = status;
            ui.post(() -> {
                if (serial != qrLoginSerial || isFinishing()) return;
                if (result.code == 803) {
                    finishQrLogin(dialog, result.cookie);
                } else if (result.code == 800) {
                    if (qrStatusView != null) qrStatusView.setText("二维码已过期，请重新生成");
                } else {
                    if (qrStatusView != null) {
                        qrStatusView.setText(result.code == 802 ? "已扫码，请在手机上确认"
                                : result.code == 801 ? "等待扫码…" : result.message);
                    }
                    ui.postDelayed(() -> pollQrLogin(qr, serial, dialog), 1800L);
                }
            });
        });
    }

    private void finishQrLogin(RoundDialog dialog, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            Toast.makeText(this, "登录成功但未取得 Cookie", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        final int loginSerial = qrLoginSerial;
        cloudExec.execute(() -> {
            try {
                NeteaseMusicApi api = new NeteaseMusicApi(this, Prefs.neteaseApiUrl(this), cookie);
                NeteaseMusicApi.LoginStatus status = api.loginStatus(cookie);
                Prefs.put(this, Prefs.K_NETEASE_COOKIE, cookie);
                Prefs.put(this, Prefs.K_NETEASE_UID, status.uid > 0
                        ? String.valueOf(status.uid) : "");
                Prefs.put(this, Prefs.K_NETEASE_NICKNAME, status.nickname);
                ui.post(() -> {
                    if (loginSerial != qrLoginSerial || isFinishing()) return;
                    dialog.dismiss();
                    qrLoginSerial++;
                    qrStatusView = null;
                    Toast.makeText(this, status.nickname.isEmpty() ? "网易云登录成功"
                            : "已登录 · " + status.nickname, Toast.LENGTH_SHORT).show();
                    showCloudMenu();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (loginSerial != qrLoginSerial || isFinishing()) return;
                    if (qrStatusView != null) qrStatusView.setText("登录状态读取失败，请稍后重试");
                    Toast.makeText(this, e.getMessage() == null ? "登录状态读取失败"
                            : e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void refreshCloudLogin() {
        runCloudRequest("正在刷新登录状态…",
                () -> new NeteaseMusicApi(this).loginStatus(),
                status -> {
                    if (status.loggedIn) {
                        Prefs.put(this, Prefs.K_NETEASE_UID,
                                status.uid > 0 ? String.valueOf(status.uid) : "");
                        Prefs.put(this, Prefs.K_NETEASE_NICKNAME, status.nickname);
                        Toast.makeText(this, "登录状态有效", Toast.LENGTH_SHORT).show();
                    } else {
                        Prefs.put(this, Prefs.K_NETEASE_UID, "");
                        Prefs.put(this, Prefs.K_NETEASE_NICKNAME, "");
                        Toast.makeText(this, "登录状态已失效", Toast.LENGTH_SHORT).show();
                    }
                    showCloudMenu();
                });
    }

    private void logoutCloud() {
        qrLoginSerial++;
        Prefs.put(this, Prefs.K_NETEASE_COOKIE, "");
        Prefs.put(this, Prefs.K_NETEASE_UID, "");
        Prefs.put(this, Prefs.K_NETEASE_NICKNAME, "");
        Toast.makeText(this, "已退出网易云登录", Toast.LENGTH_SHORT).show();
        showCloudMenu();
    }

    private byte[] decodeImageData(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String raw = value.trim();
        int comma = raw.indexOf(',');
        if (comma >= 0) raw = raw.substring(comma + 1);
        try {
            return Base64.decode(raw, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private TextView subtleText(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        Ui.styleSubtle(tv);
        tv.setTextSize(12);
        return tv;
    }

    /** 优先媒体库；为空时扫描常见目录，避免只扫 /sdcard 根目录漏掉 bluetooth。 */
    private List<String> scanLocalAudio() {
        ArrayList<Track> tracks = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        scanMediaStore(tracks, seen);
        if (tracks.isEmpty()) {
            String base = "/storage/emulated/0";
            String[] dirs = {
                    base + "/Music", base + "/Download", base + "/bluetooth", base + "/Bluetooth", base + "/msc",
                    "/sdcard/Music", "/sdcard/Download", "/sdcard/bluetooth", "/sdcard/Bluetooth", "/sdcard/msc"
            };
            for (String dir : dirs) {
                scanDir(new File(dir), 3, tracks, seen);
                if (tracks.size() >= MAX_LOCAL_TRACKS) break;
            }
        }
        Collections.sort(tracks, (a, b) -> {
            if (a.likelySong != b.likelySong) return a.likelySong ? -1 : 1;
            return a.title.compareToIgnoreCase(b.title);
        });
        boolean hasLikelySong = false;
        for (Track t : tracks) {
            if (t.likelySong) {
                hasLikelySong = true;
                break;
            }
        }
        ArrayList<String> urls = new ArrayList<>();
        for (Track t : tracks) {
            if (!hasLikelySong || t.likelySong) urls.add(t.path);
        }
        return urls;
    }

    private void scanMediaStore(List<Track> tracks, Set<String> seen) {
        String[] projection = {
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.MediaColumns.SIZE
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, MediaStore.Audio.Media.IS_MUSIC + "!=0", null, null);
            if (c == null) return;
            int dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA);
            int titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int durationIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            while (c.moveToNext() && tracks.size() < MAX_LOCAL_TRACKS) {
                String path = dataIdx >= 0 ? c.getString(dataIdx) : "";
                String title = titleIdx >= 0 ? c.getString(titleIdx) : "";
                long duration = durationIdx >= 0 ? c.getLong(durationIdx) : 0;
                long size = sizeIdx >= 0 ? c.getLong(sizeIdx) : 0;
                addTrack(tracks, seen, path, title, duration, size);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private void scanDir(File dir, int depth, List<Track> tracks, Set<String> seen) {
        if (dir == null || depth < 0 || tracks.size() >= MAX_LOCAL_TRACKS || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        ArrayList<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, files);
        Collections.sort(ordered, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : ordered) {
            if (tracks.size() >= MAX_LOCAL_TRACKS) return;
            if (f.isFile()) {
                addTrack(tracks, seen, f.getAbsolutePath(), stripExt(f.getName()), 0, f.length());
            }
        }
        for (File f : ordered) {
            if (tracks.size() >= MAX_LOCAL_TRACKS) return;
            if (f.isDirectory()) scanDir(f, depth - 1, tracks, seen);
        }
    }

    private void addTrack(List<Track> tracks, Set<String> seen, String path, String title,
                          long durationMs, long size) {
        if (path == null || path.trim().isEmpty()) return;
        path = path.trim();
        if (!supportedAudio(path)) return;
        if (!seen.add(path)) return;
        if (size <= 0) {
            try { size = new File(path).length(); } catch (Exception ignored) {}
        }
        String shown = title == null || title.trim().isEmpty() ? displayName(path) : title.trim();
        boolean likely = durationMs >= LIKELY_SONG_MS || size >= LIKELY_SONG_SIZE;
        tracks.add(new Track(path, shown, likely));
    }

    private boolean supportedAudio(String s) {
        String n = s == null ? "" : s.toLowerCase(Locale.US);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".aac");
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
        cloudRequestSerial++;
        qrLoginSerial++;
        qrStatusView = null;
        artExec.shutdownNow();
        cloudExec.shutdownNow();
        if (MusicService.get() != null) MusicService.get().setListener(null);
        super.onDestroy();
    }

    @Override public void onState(String t, boolean playing, int p, int d, int idx) {
        playingState = playing;
        MusicService svc = MusicService.get();
        MusicTrack current = svc == null ? null : svc.currentTrack();
        String shown = current == null ? displayName(t) : trackTitle(current);
        piece.update(shown, current == null ? "" : current.artist, playing, p, d, idx);
        piece.postInvalidate();
        String artKey = current != null && current.isNetease()
                ? "netease:" + current.neteaseId : t;
        if (!artKey.equals(piece.lastTitleForArt)) {
            piece.lastTitleForArt = artKey;
            tryFetchArt(current, t, idx);
        }
    }

    @Override public void onTrackError(String title, String message, int idx) {
        if (message == null || message.trim().isEmpty()) message = "播放失败";
        Toast.makeText(this, compact(title, 14) + " · " + message,
                Toast.LENGTH_LONG).show();
    }

    private void tryFetchArt(final MusicTrack track, final String title, final int idx) {
        MusicService svc = MusicService.get();
        if (svc == null) return;
        List<MusicTrack> serviceTracks = svc.tracks();
        if (idx < 0 || idx >= serviceTracks.size()) return;
        MusicTrack current = track == null ? serviceTracks.get(idx) : track;
        if (current.isNetease() && !current.coverUrl.trim().isEmpty()) {
            final String key = "netease:" + current.neteaseId;
            final String cover = current.coverUrl;
            artExec.execute(() -> {
                try {
                    Bitmap b = fetchBitmap(cover);
                    runOnUiThread(() -> {
                        if (key.equals(piece.lastTitleForArt)) piece.setArt(b);
                    });
                    return;
                } catch (Exception ignored) {}
            });
            return;
        }
        String cur = current.url;
        if (cur == null || cur.trim().isEmpty()) return;
        String dir = parentUrl(cur);
        String tn = stripExt(displayName(title));
        String[] candidates = {dir + "cover.jpg", dir + "folder.jpg", dir + tn + ".jpg"};
        artExec.execute(() -> {
            for (String cand : candidates) {
                try {
                    Bitmap b = fetchBitmap(cand);
                    if (b != null) {
                        runOnUiThread(() -> {
                            if (title.equals(piece.title)) piece.setArt(b);
                        });
                        return;
                    }
                } catch (Exception ignored) {}
            }
            runOnUiThread(() -> {
                if (title.equals(piece.title)) piece.setArt(null);
            });
        });
    }

    private Bitmap fetchBitmap(String u) throws Exception {
        if (u == null || u.isEmpty()) return null;
        if (u.startsWith("http")) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return BitmapFactory.decodeByteArray(bo.toByteArray(), 0, bo.size());
            } finally {
                c.disconnect();
            }
        }
        String path = u.startsWith("file://") ? Uri.parse(u).getPath() : u;
        File f = new File(path == null ? "" : Uri.decode(path));
        return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    private String parentUrl(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        String raw = q >= 0 ? u.substring(0, q) : u;
        int slash = raw.lastIndexOf('/');
        return slash >= 0 ? raw.substring(0, slash + 1) : "";
    }

    private String displayName(String u) {
        if (u == null || u.trim().isEmpty()) return "";
        String s = u.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        s = Uri.decode(s);
        return stripExt(s.isEmpty() ? u : s);
    }

    private String stripExt(String s) {
        if (s == null) return "";
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            String ext = s.substring(dot).toLowerCase(Locale.US);
            if (".mp3".equals(ext) || ".flac".equals(ext) || ".wav".equals(ext)
                    || ".ogg".equals(ext) || ".m4a".equals(ext) || ".aac".equals(ext)) {
                return s.substring(0, dot);
            }
        }
        return s;
    }

    private String compact(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 6)) + "..." + s.substring(s.length() - 3);
    }

    private String trackTitle(MusicTrack track) {
        if (track == null) return "";
        String title = track.displayTitle();
        return title == null || title.trim().isEmpty() ? "未命名歌曲" : title;
    }

    private static class Track {
        final String path;
        final String title;
        final boolean likelySong;
        Track(String path, String title, boolean likelySong) {
            this.path = path;
            this.title = title == null ? "" : title;
            this.likelySong = likelySong;
        }
    }

    /** 全屏自绘唱片机：背景、唱片、进度和信息区都在圆屏安全区内。 */
    private class PieceView extends View {
        private String title = "";
        private String artist = "";
        private String emptyText = "";
        private boolean playing = false;
        private int pos = 0;
        private int dur = 1;
        private int currentIndex = 0;
        private int trackCount = 0;
        private Bitmap art;
        private String lastTitleForArt = "";
        private float downX, downY;
        private boolean downInDeck = false;

        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tmpPath = new Path();

        PieceView(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
            pStroke.setStyle(Paint.Style.STROKE);
            pText.setSubpixelText(true);
        }

        void update(String t, boolean pl, int p, int d, int idx) {
            update(t, "", pl, p, d, idx);
        }

        void update(String t, String a, boolean pl, int p, int d, int idx) {
            title = t == null ? "" : t;
            artist = a == null ? "" : a;
            playing = pl;
            pos = Math.max(0, p);
            dur = Math.max(d, 1);
            currentIndex = Math.max(0, idx);
        }

        void setTrackCount(int count) {
            trackCount = Math.max(0, count);
        }

        void setEmptyText(String text) {
            emptyText = text == null ? "" : text;
            postInvalidate();
        }

        void setArt(Bitmap b) {
            if (b != null && !b.isRecycled()) art = b;
            else art = null;
            postInvalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            return handleDeckSwipe(ev);
        }

        boolean handleDeckSwipe(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    downInDeck = pointInDeck(downX, downY);
                    return downInDeck;
                case MotionEvent.ACTION_UP:
                    if (!downInDeck) return false;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    float s = RoundScreen.scale800(getWidth(), getHeight());
                    if (Math.abs(dx) > 86f * s && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        MusicService svc = MusicService.get();
                        if (svc != null) {
                            if (dx < 0) svc.next();
                            else svc.prev();
                        }
                        return true;
                    }
                    if (pointInCenter(downX, downY) && pointInCenter(ev.getX(), ev.getY())) {
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    downInDeck = false;
                    return false;
            }
            return downInDeck;
        }

        @Override public boolean performClick() {
            super.performClick();
            togglePlaybackFromUi();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float R = RoundScreen.R(w, h);
            float s = RoundScreen.scale800(w, h);
            float discR = Math.min(R * 0.43f, h * 0.22f);
            float ringR = discR + 24f * s;

            drawDeckBackground(c, w, h, cx, cy, R, s);
            drawRecord(c, cx, cy, discR, s);
            drawProgress(c, cx, cy, ringR, s);
            drawPlayButton(c, cx, cy, discR * 0.29f, s);
            drawInfo(c, cx, cy + ringR + 39f * s, R, s);
        }

        private void drawDeckBackground(Canvas c, float w, float h, float cx, float cy, float R, float s) {
            pFill.setStyle(Paint.Style.FILL);
            pFill.setShader(new RadialGradient(cx, cy, R * 0.96f,
                    new int[] {
                            Color.rgb(54, 40, 23),
                            Color.rgb(26, 19, 12),
                            Color.rgb(9, 7, 6)
                    },
                    new float[] {0f, 0.58f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R * 0.96f, pFill);
            pFill.setShader(null);

            pFill.setColor(Color.argb(86, 112, 34, 24));
            c.drawCircle(cx, cy, R * 0.90f, pFill);
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeWidth(16f * s);
            pStroke.setColor(Color.argb(28, 212, 175, 55));
            c.drawCircle(cx, cy, R * 0.78f, pStroke);
            pStroke.setStrokeWidth(12f * s);
            pStroke.setColor(Color.argb(28, 70, 210, 214));
            c.drawCircle(cx, cy, R * 0.58f, pStroke);

            pStroke.setStrokeWidth(1.4f * s);
            int[] colors = {
                    Color.argb(150, 212, 175, 55),
                    Color.argb(112, 145, 116, 48),
                    Color.argb(82, 120, 98, 50),
                    Color.argb(55, 70, 210, 214)
            };
            float[] rs = {R * 0.86f, R * 0.72f, R * 0.60f, R * 0.47f};
            for (int i = 0; i < rs.length; i++) {
                pStroke.setColor(colors[i]);
                c.drawCircle(cx, cy, rs[i], pStroke);
            }
            pStroke.setStrokeWidth(1.05f * s);
            for (int i = 0; i < 64; i++) {
                float a = (float) Math.toRadians(i * 360f / 64f - 90f);
                boolean major = i % 8 == 0;
                float r1 = R * (major ? 0.74f : 0.79f);
                float r2 = R * 0.85f;
                pStroke.setColor(major ? Color.argb(130, 212, 175, 55)
                        : Color.argb(58, 145, 116, 48));
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, pStroke);
            }
            pStroke.setStrokeWidth(2.2f * s);
            pStroke.setColor(Color.argb(115, 74, 46, 24));
            c.drawCircle(cx, cy, R * 0.50f, pStroke);
        }

        private void drawRecord(Canvas c, float cx, float cy, float r, float s) {
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(Color.rgb(29, 24, 16));
            c.drawCircle(cx, cy, r, pFill);

            if (art != null && !art.isRecycled()) {
                int save = c.save();
                tmpPath.reset();
                tmpPath.addCircle(cx, cy, r, Path.Direction.CW);
                c.clipPath(tmpPath);
                RectF dst = new RectF(cx - r, cy - r, cx + r, cy + r);
                c.drawBitmap(art, null, dst, null);
                pFill.setColor(Color.argb(72, 11, 8, 6));
                c.drawCircle(cx, cy, r, pFill);
                c.restoreToCount(save);
            } else {
                pStroke.setStyle(Paint.Style.STROKE);
                pStroke.setStrokeWidth(1.1f * s);
                for (int i = 1; i <= 7; i++) {
                    float rr = r * (0.18f + i * 0.1f);
                    pStroke.setColor(i % 2 == 0 ? Color.argb(105, 145, 116, 48)
                            : Color.argb(68, 120, 98, 50));
                    c.drawCircle(cx, cy, rr, pStroke);
                }
                pStroke.setColor(Color.argb(95, 212, 175, 55));
                pStroke.setStrokeWidth(1.25f * s);
                for (int i = 0; i < 16; i++) {
                    float a = (float) Math.toRadians(i * 22.5f);
                    c.drawLine(cx + (float) Math.cos(a) * r * 0.28f,
                            cy + (float) Math.sin(a) * r * 0.28f,
                            cx + (float) Math.cos(a) * r * 0.88f,
                            cy + (float) Math.sin(a) * r * 0.88f, pStroke);
                }
            }

            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeWidth(2.6f * s);
            pStroke.setColor(Ui.COLOR_GOLD);
            c.drawCircle(cx, cy, r, pStroke);
            pStroke.setStrokeWidth(1f * s);
            pStroke.setColor(Color.argb(110, 70, 210, 214));
            c.drawCircle(cx, cy, r * 0.43f, pStroke);
        }

        private void drawProgress(Canvas c, float cx, float cy, float ringR, float s) {
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            pStroke.setStrokeWidth(7f * s);
            pStroke.setColor(Color.rgb(74, 46, 24));
            c.drawCircle(cx, cy, ringR, pStroke);
            if (trackCount > 0 && dur > 1) {
                float sweep = Math.max(0f, Math.min(360f, 360f * (pos / (float) dur)));
                RectF arc = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
                pStroke.setColor(Ui.COLOR_GOLD);
                c.drawArc(arc, -90f, sweep, false, pStroke);
            }
            pStroke.setStrokeCap(Paint.Cap.BUTT);
        }

        private boolean pointInDeck(float x, float y) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float R = RoundScreen.R(w, h);
            float s = RoundScreen.scale800(w, h);
            float discR = Math.min(R * 0.43f, h * 0.22f);
            float ringR = discR + 42f * s;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy <= ringR * ringR;
        }

        private boolean pointInCenter(float x, float y) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float R = RoundScreen.R(w, h);
            float s = RoundScreen.scale800(w, h);
            float discR = Math.min(R * 0.43f, h * 0.22f);
            float hitR = discR * 0.46f + 8f * s;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy <= hitR * hitR;
        }

        private void drawPlayButton(Canvas c, float cx, float cy, float btnR, float s) {
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(Color.argb(205, 11, 8, 6));
            c.drawCircle(cx, cy, btnR, pFill);
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeWidth(2.2f * s);
            pStroke.setColor(Ui.COLOR_GOLD);
            c.drawCircle(cx, cy, btnR, pStroke);

            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(Ui.COLOR_GOLD);
            if (playing) {
                float barW = btnR * 0.16f;
                float barH = btnR * 0.78f;
                float gap = btnR * 0.16f;
                float top = cy - barH / 2f;
                float bottom = cy + barH / 2f;
                c.drawRoundRect(new RectF(cx - gap - barW, top, cx - gap, bottom),
                        3f * s, 3f * s, pFill);
                c.drawRoundRect(new RectF(cx + gap, top, cx + gap + barW, bottom),
                        3f * s, 3f * s, pFill);
            } else {
                tmpPath.reset();
                tmpPath.moveTo(cx - btnR * 0.24f, cy - btnR * 0.42f);
                tmpPath.lineTo(cx - btnR * 0.24f, cy + btnR * 0.42f);
                tmpPath.lineTo(cx + btnR * 0.48f, cy);
                tmpPath.close();
                c.drawPath(tmpPath, pFill);
            }
        }

        private void drawInfo(Canvas c, float cx, float y, float R, float s) {
            String main = !emptyText.isEmpty() ? emptyText : (title.isEmpty() ? "未在播放" : title);
            pText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pText.setTextSize(22f * s);
            pText.setColor(Ui.COLOR_TEXT);
            drawCenteredEllipsized(c, main, cx, y, R * 1.54f, pText);

            pText.setTypeface(android.graphics.Typeface.DEFAULT);
            pText.setTextSize(14f * s);
            pText.setColor(Ui.COLOR_TEXT_DIM);
            String sub;
            if (!emptyText.isEmpty()) {
                sub = "请在歌单菜单选择来源或配置服务";
            } else if (trackCount > 0 && !playing && pos == 0 && dur <= 1) {
                sub = (artist.isEmpty() ? "" : compact(artist, 16) + "  ·  ")
                        + (currentIndex + 1) + "/" + trackCount + "  ·  点击唱片播放";
            } else if (trackCount > 0) {
                sub = (artist.isEmpty() ? "" : compact(artist, 16) + "  ·  ")
                        + (currentIndex + 1) + "/" + trackCount + "  ·  "
                        + fmt(pos) + " / " + fmt(dur);
            } else {
                sub = "00:00 / 00:00";
            }
            drawCenteredEllipsized(c, sub, cx, y + 25f * s, R * 1.35f, pText);
        }

        private void drawCenteredEllipsized(Canvas c, String text, float cx, float y, float maxW, Paint p) {
            String t = text == null ? "" : text;
            if (p.measureText(t) > maxW) {
                int n = t.length();
                while (n > 1 && p.measureText(t.substring(0, n) + "...") > maxW) n--;
                t = t.substring(0, n) + "...";
            }
            c.drawText(t, cx - p.measureText(t) / 2f, y, p);
        }

        private String fmt(int ms) {
            int sec = Math.max(0, ms / 1000);
            return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
        }
    }
}
