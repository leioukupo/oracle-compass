package com.magneo.compass.netfs;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/** 音乐后台播放服务（普通 Service + 部分唤醒锁，API 22 无需前台通知）。 */
public class MusicService extends Service {
    public interface Listener {
        void onState(String title, boolean playing, int pos, int dur);
    }

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
                    listener.onState(title(), mp.isPlaying(), mp.getCurrentPosition(), mp.getDuration());
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
        return START_STICKY;
    }

    public static void startPlaylist(Context c, List<String> urls) {
        c.startService(new Intent(c, MusicService.class));
        if (inst != null) inst.setPlaylist(urls);
    }

    private void setPlaylist(List<String> u) {
        urls = u == null ? new ArrayList<>() : u;
        idx = 0;
        if (!urls.isEmpty()) playItem(0);
    }

    private void playItem(int i) {
        if (i < 0 || i >= urls.size()) return;
        idx = i;
        try {
            if (mp != null) { mp.release(); mp = null; }
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(urls.get(i));
            mp.setOnPreparedListener(m -> { m.start(); if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(); });
            mp.setOnCompletionListener(m -> next());
            mp.setOnErrorListener((m, what, extra) -> { next(); return true; });
            mp.prepareAsync();
        } catch (Exception e) {
            next();
        }
    }

    public void toggle() {
        if (mp == null) { if (!urls.isEmpty()) playItem(idx); return; }
        if (mp.isPlaying()) mp.pause();
        else mp.start();
    }

    public void next() { playItem(idx + 1); }
    public void prev() { playItem(idx - 1 < 0 ? urls.size() - 1 : idx - 1); }
    public void seekTo(int ms) { if (mp != null) mp.seekTo(ms); }
    public boolean isPlaying() { return mp != null && mp.isPlaying(); }
    public void setListener(Listener l) { listener = l; }

    private String title() {
        if (urls.isEmpty() || idx >= urls.size()) return "";
        String u = urls.get(idx);
        String n = u.substring(u.lastIndexOf('/') + 1);
        return n.isEmpty() ? u : n;
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
