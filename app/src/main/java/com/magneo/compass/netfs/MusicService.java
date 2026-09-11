package com.magneo.compass.netfs;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 音乐后台播放服务（普通 Service + 部分唤醒锁，API 22 无需前台通知）。 */
public class MusicService extends Service {
    private static final String TAG = "MusicService";

    public interface Listener {
        void onState(String title, boolean playing, int pos, int dur, int idx);
        default void onTrackError(String title, String message, int idx) {}
    }

    private static final String EXTRA_URLS = "com.magneo.compass.netfs.MusicService.URLS";
    private static final String EXTRA_TRACKS = "com.magneo.compass.netfs.MusicService.TRACKS";
    private static final String EXTRA_AUTOPLAY = "com.magneo.compass.netfs.MusicService.AUTOPLAY";
    private static final String EXTRA_INDEX = "com.magneo.compass.netfs.MusicService.INDEX";

    private static MusicService inst;
    private MediaPlayer mp;
    private List<String> urls = new ArrayList<>();
    private List<MusicTrack> tracks = new ArrayList<>();
    private int idx = 0;
    private Listener listener;
    private PowerManager.WakeLock wakeLock;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService resolveExec = Executors.newSingleThreadExecutor();
    private int playbackGeneration = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (listener != null && mp != null) {
                try {
                    listener.onState(title(), mp.isPlaying(), mp.getCurrentPosition(), mp.getDuration(), idx);
                } catch (Exception ignored) {}
            }
            h.postDelayed(this, 500);
        }
    };

    public static MusicService get() { return inst; }

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bagua:music");
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        ArrayList<String> incoming = i == null ? null : i.getStringArrayListExtra(EXTRA_URLS);
        ArrayList<MusicTrack> incomingTracks = i == null ? null
                : i.getParcelableArrayListExtra(EXTRA_TRACKS);
        if (incomingTracks != null) {
            boolean autoplay = i.getBooleanExtra(EXTRA_AUTOPLAY, false);
            int startIndex = i.getIntExtra(EXTRA_INDEX, 0);
            setTrackPlaylist(incomingTracks, autoplay, startIndex);
        } else if (incoming != null) {
            boolean autoplay = i.getBooleanExtra(EXTRA_AUTOPLAY, false);
            int startIndex = i.getIntExtra(EXTRA_INDEX, 0);
            setPlaylist(incoming, autoplay, startIndex);
        }
        return START_STICKY;
    }

    public static void startPlaylist(Context c, List<String> urls) {
        startPlaylist(c, urls, false);
    }

    public static void startPlaylist(Context c, List<String> urls, boolean autoplay) {
        startPlaylist(c, urls, autoplay, 0);
    }

    public static void startPlaylist(Context c, List<String> urls, boolean autoplay, int startIndex) {
        Intent i = new Intent(c, MusicService.class);
        i.putStringArrayListExtra(EXTRA_URLS, new ArrayList<>(urls == null
                ? new ArrayList<String>() : urls));
        i.putExtra(EXTRA_AUTOPLAY, autoplay);
        i.putExtra(EXTRA_INDEX, startIndex);
        c.startService(i);
    }

    public static void startTracks(Context c, List<MusicTrack> tracks) {
        startTracks(c, tracks, false, 0);
    }

    public static void startTracks(Context c, List<MusicTrack> tracks, boolean autoplay,
                                   int startIndex) {
        Intent i = new Intent(c, MusicService.class);
        i.putParcelableArrayListExtra(EXTRA_TRACKS, new ArrayList<>(tracks == null
                ? new ArrayList<MusicTrack>() : tracks));
        i.putExtra(EXTRA_AUTOPLAY, autoplay);
        i.putExtra(EXTRA_INDEX, startIndex);
        c.startService(i);
    }

    private void setPlaylist(List<String> u, boolean autoplay, int startIndex) {
        ArrayList<MusicTrack> converted = new ArrayList<>();
        if (u != null) {
            for (String value : u) {
                converted.add(MusicTrack.local(value, titleFromUrl(value)));
            }
        }
        setTrackPlaylist(converted, autoplay, startIndex);
    }

    private void setTrackPlaylist(List<MusicTrack> incoming, boolean autoplay, int startIndex) {
        playbackGeneration++;
        resolving = false;
        releasePlayer();
        tracks = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
        syncUrls();
        idx = tracks.isEmpty() ? 0 : Math.max(0, Math.min(tracks.size() - 1, startIndex));
        if (tracks.isEmpty()) notifyState();
        else if (autoplay) playItem(idx, 0, false);
        else notifyState();
    }

    private boolean resolving;

    private void playItem(int i) { playItem(i, 0, false); }

    private void playItem(int i, int attempts) { playItem(i, attempts, false); }

    private void playItem(int i, int attempts, boolean forceResolve) {
        playbackGeneration++;
        int generation = playbackGeneration;
        playItemForGeneration(i, attempts, forceResolve, generation);
    }

    private void playItemForGeneration(int i, int attempts, boolean forceResolve,
                                        int generation) {
        if (generation != playbackGeneration) return;
        if (tracks.isEmpty()) {
            stopPlayback();
            return;
        }
        if (attempts >= tracks.size()) {
            stopPlayback();
            return;
        }
        idx = ((i % tracks.size()) + tracks.size()) % tracks.size();
        final int nextAttempts = attempts + 1;
        final MusicTrack track = tracks.get(idx);
        releasePlayer();
        if (track.isNetease() && (forceResolve || track.url.trim().isEmpty())) {
            resolving = true;
            notifyState();
            final int targetIndex = idx;
            resolveExec.execute(() -> {
                String resolved = "";
                String error = "";
                try {
                    resolved = new NeteaseMusicApi(getApplicationContext())
                            .resolveSongUrl(track.neteaseId);
                } catch (Exception e) {
                    error = e.getMessage() == null ? "解析播放地址失败" : e.getMessage();
                }
                final String result = resolved == null ? "" : resolved.trim();
                final String failure = error;
                h.post(() -> {
                    if (generation != playbackGeneration || targetIndex != idx) return;
                    resolving = false;
                    if (result.isEmpty()) {
                        notifyTrackError(track, failure.isEmpty()
                                ? "歌曲暂无可用播放地址" : failure);
                        playItem(targetIndex + 1, nextAttempts, false);
                        return;
                    }
                    MusicTrack updated = track.withUrl(result);
                    tracks.set(targetIndex, updated);
                    syncUrls();
                    prepareMedia(updated, targetIndex, nextAttempts - 1,
                            generation, forceResolve);
                });
            });
            return;
        }
        prepareMedia(track, idx, attempts, generation, forceResolve);
    }

    private void prepareMedia(final MusicTrack track, final int targetIndex,
                              final int attempts, final int generation,
                              final boolean forceResolve) {
        try {
            if (mp != null) { mp.release(); mp = null; }
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (track.isNetease()) {
                HashMap<String, String> headers = new HashMap<>();
                headers.put("User-Agent", NeteaseMusicApi.USER_AGENT);
                headers.put("Referer", "http://music.163.com/");
                mp.setDataSource(this, Uri.parse(track.url), headers);
            } else {
                mp.setDataSource(track.url);
            }
            mp.setOnPreparedListener(m -> {
                if (generation != playbackGeneration || targetIndex != idx || m != mp) {
                    try { m.release(); } catch (Exception ignored) {}
                    return;
                }
                m.start();
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
                notifyState();
            });
            mp.setOnCompletionListener(m -> {
                if (generation == playbackGeneration && targetIndex == idx && m == mp) next();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (generation != playbackGeneration || targetIndex != idx || m != mp) return true;
                Log.w(TAG, "error what=" + what + " extra=" + extra + " idx=" + idx);
                if (track.isNetease() && !forceResolve) {
                    tracks.set(targetIndex, track.withUrl(""));
                    syncUrls();
                    playItem(targetIndex, attempts, true);
                } else {
                    playItem(targetIndex + 1, attempts + 1, false);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "play failed idx=" + idx, e);
            if (track.isNetease() && !forceResolve) {
                tracks.set(targetIndex, track.withUrl(""));
                syncUrls();
                playItem(targetIndex, attempts, true);
            } else {
                playItem(targetIndex + 1, attempts + 1, false);
            }
        }
    }

    public void playAt(int i) { if (i >= 0 && i < tracks.size()) playItem(i); }
    public int currentIndex() { return idx; }
    public List<String> playlist() { return new ArrayList<>(urls); }
    public List<MusicTrack> tracks() { return new ArrayList<>(tracks); }
    public MusicTrack currentTrack() {
        return idx >= 0 && idx < tracks.size() ? tracks.get(idx) : null;
    }

    public int audioSessionId() {
        if (mp == null) return 0;
        try { return mp.getAudioSessionId(); } catch (Exception ignored) { return 0; }
    }

    public void toggle() {
        if (mp == null) { if (!tracks.isEmpty()) playItem(idx); return; }
        if (mp.isPlaying()) {
            mp.pause();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } else {
            mp.start();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        }
        notifyState();
    }

    public void next() { if (!tracks.isEmpty()) playItem(idx + 1); }
    public void prev() { if (!tracks.isEmpty()) playItem(idx - 1); }
    public void stopPlayback() {
        playbackGeneration++;
        resolving = false;
        releasePlayer();
        notifyState();
    }

    public void clearPlaylist() {
        setTrackPlaylist(new ArrayList<MusicTrack>(), false, 0);
    }

    private void releasePlayer() {
        try {
            if (mp != null) {
                mp.release();
                mp = null;
            }
        } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    public void seekTo(int ms) { if (mp != null) { mp.seekTo(ms); notifyState(); } }
    public void seekRelative(int deltaMs) {
        if (mp == null) return;
        try {
            int dur = Math.max(1, mp.getDuration());
            int pos = mp.getCurrentPosition() + deltaMs;
            if (dur > 1) pos = Math.max(0, Math.min(dur - 1000, pos));
            else pos = Math.max(0, pos);
            mp.seekTo(pos);
            notifyState();
        } catch (Exception ignored) {}
    }
    public boolean isPlaying() { return mp != null && mp.isPlaying(); }
    public void setListener(Listener l) {
        listener = l;
        notifyState();
    }

    private String title() {
        MusicTrack current = currentTrack();
        if (current != null && !current.displayTitle().trim().isEmpty()) {
            return current.displayTitle();
        }
        if (urls.isEmpty() || idx >= urls.size()) return "";
        return titleFromUrl(urls.get(idx));
    }

    private static String titleFromUrl(String value) {
        String u = value == null ? "" : value;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        String n = u.substring(u.lastIndexOf('/') + 1);
        n = Uri.decode(n);
        if (!n.isEmpty()) {
            int dot = n.lastIndexOf('.');
            if (dot > 0) n = n.substring(0, dot);
        }
        return n.isEmpty() ? u : n;
    }

    private void notifyState() {
        if (listener == null) return;
        int pos = 0;
        int dur = 1;
        boolean playing = false;
        if (mp != null) {
            try { pos = mp.getCurrentPosition(); } catch (Exception ignored) {}
            try { dur = Math.max(1, mp.getDuration()); } catch (Exception ignored) {}
            try { playing = mp.isPlaying(); } catch (Exception ignored) {}
        } else {
            MusicTrack current = currentTrack();
            if (current != null && current.durationMs > 0L) {
                dur = (int) Math.min(Integer.MAX_VALUE, current.durationMs);
            }
        }
        try { listener.onState(title(), playing, pos, dur, idx); } catch (Exception ignored) {}
    }

    private void notifyTrackError(MusicTrack track, String message) {
        if (listener == null || track == null) return;
        try {
            listener.onTrackError(track.displayTitle(),
                    message == null || message.trim().isEmpty() ? "播放失败" : message,
                    idx);
        } catch (Exception ignored) {}
    }

    private void syncUrls() {
        urls = new ArrayList<>();
        for (MusicTrack track : tracks) urls.add(track == null ? "" : track.url);
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        playbackGeneration++;
        resolveExec.shutdownNow();
        if (mp != null) { mp.release(); mp = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        inst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
