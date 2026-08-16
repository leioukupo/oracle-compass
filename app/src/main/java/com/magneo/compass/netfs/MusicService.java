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

import java.util.ArrayList;
import java.util.List;

/** 音乐后台播放服务（普通 Service + 部分唤醒锁，API 22 无需前台通知）。 */
public class MusicService extends Service {
    private static final String TAG = "MusicService";

    public interface Listener {
        void onState(String title, boolean playing, int pos, int dur, int idx);
    }

    private static final String EXTRA_URLS = "com.magneo.compass.netfs.MusicService.URLS";
    private static final String EXTRA_AUTOPLAY = "com.magneo.compass.netfs.MusicService.AUTOPLAY";
    private static final String EXTRA_INDEX = "com.magneo.compass.netfs.MusicService.INDEX";

    private static MusicService inst;
    private MediaPlayer mp;
    private List<String> urls = new ArrayList<>();
    private int idx = 0;
    private Listener listener;
    private PowerManager.WakeLock wakeLock;
    private final Handler h = new Handler(Looper.getMainLooper());
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
        if (incoming != null) {
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

    private void setPlaylist(List<String> u, boolean autoplay, int startIndex) {
        urls = u == null ? new ArrayList<>() : new ArrayList<>(u);
        idx = urls.isEmpty() ? 0 : Math.max(0, Math.min(urls.size() - 1, startIndex));
        if (urls.isEmpty()) stopPlayback();
        else {
            releasePlayer();
            if (autoplay) playItem(idx, 0);
            else notifyState();
        }
    }

    private void playItem(int i) { playItem(i, 0); }

    private void playItem(int i, int attempts) {
        if (urls.isEmpty()) {
            stopPlayback();
            return;
        }
        if (attempts >= urls.size()) {
            stopPlayback();
            return;
        }
        idx = ((i % urls.size()) + urls.size()) % urls.size();
        final int nextAttempts = attempts + 1;
        try {
            if (mp != null) { mp.release(); mp = null; }
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(urls.get(idx));
            mp.setOnPreparedListener(m -> {
                m.start();
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
                notifyState();
            });
            mp.setOnCompletionListener(m -> next());
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "error what=" + what + " extra=" + extra + " idx=" + idx);
                playItem(idx + 1, nextAttempts);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "play failed idx=" + idx, e);
            playItem(idx + 1, nextAttempts);
        }
    }

    public void playAt(int i) { if (i >= 0 && i < urls.size()) playItem(i); }
    public int currentIndex() { return idx; }
    public List<String> playlist() { return new ArrayList<>(urls); }

    public void toggle() {
        if (mp == null) { if (!urls.isEmpty()) playItem(idx); return; }
        if (mp.isPlaying()) {
            mp.pause();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } else {
            mp.start();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        }
        notifyState();
    }

    public void next() { if (!urls.isEmpty()) playItem(idx + 1); }
    public void prev() { if (!urls.isEmpty()) playItem(idx - 1); }
    public void stopPlayback() {
        releasePlayer();
        notifyState();
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
        if (urls.isEmpty() || idx >= urls.size()) return "";
        String u = urls.get(idx);
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
        }
        try { listener.onState(title(), playing, pos, dur, idx); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        if (mp != null) { mp.release(); mp = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        inst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
