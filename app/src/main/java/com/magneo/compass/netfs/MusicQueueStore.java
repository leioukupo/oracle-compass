package com.magneo.compass.netfs;

import android.content.Context;

import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists the last music queue so the music page can be reopened after process death. */
final class MusicQueueStore {
    private MusicQueueStore() {}

    static final class Snapshot {
        final ArrayList<MusicTrack> tracks;
        final int index;

        Snapshot(ArrayList<MusicTrack> tracks, int index) {
            this.tracks = tracks;
            this.index = index;
        }
    }

    static void save(Context context, List<MusicTrack> tracks, int index) {
        if (tracks == null || tracks.isEmpty()) {
            clear(context);
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("index", Math.max(0, Math.min(tracks.size() - 1, index)));
            JSONArray items = new JSONArray();
            for (MusicTrack track : tracks) {
                if (track == null) continue;
                items.put(new JSONObject()
                        .put("source", track.source)
                        .put("url", track.url)
                        .put("neteaseId", track.neteaseId)
                        .put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("coverUrl", track.coverUrl)
                        .put("durationMs", track.durationMs));
            }
            root.put("tracks", items);
            Prefs.put(context, Prefs.K_MUSIC_QUEUE, root.toString());
        } catch (Exception ignored) {}
    }

    static Snapshot load(Context context) {
        String raw = Prefs.get(context, Prefs.K_MUSIC_QUEUE, "");
        if (raw == null || raw.trim().isEmpty()) return new Snapshot(new ArrayList<>(), 0);
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray items = root.optJSONArray("tracks");
            ArrayList<MusicTrack> tracks = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    tracks.add(new MusicTrack(
                            item.optString("source", MusicTrack.SOURCE_LOCAL),
                            item.optString("url", ""),
                            item.optLong("neteaseId", 0L),
                            item.optString("title", ""),
                            item.optString("artist", ""),
                            item.optString("album", ""),
                            item.optString("coverUrl", ""),
                            item.optLong("durationMs", 0L)));
                }
            }
            int index = root.optInt("index", 0);
            if (!tracks.isEmpty()) index = Math.max(0, Math.min(tracks.size() - 1, index));
            return new Snapshot(tracks, index);
        } catch (Exception ignored) {
            return new Snapshot(new ArrayList<>(), 0);
        }
    }

    static void clear(Context context) {
        Prefs.put(context, Prefs.K_MUSIC_QUEUE, "");
    }
}
