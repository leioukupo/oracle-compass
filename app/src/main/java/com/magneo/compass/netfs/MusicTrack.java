package com.magneo.compass.netfs;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 播放队列中的统一曲目模型。本地/网盘曲目直接带可播放 URL，网易云曲目
 * 初始只带歌曲 ID，MusicService 在真正播放前解析 URL。
 */
public final class MusicTrack implements Parcelable {
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_NETEASE = "netease";

    public final String source;
    public final String url;
    public final long neteaseId;
    public final String title;
    public final String artist;
    public final String album;
    public final String coverUrl;
    public final long durationMs;

    public MusicTrack(String source, String url, long neteaseId, String title,
                      String artist, String album, String coverUrl, long durationMs) {
        this.source = source == null ? SOURCE_LOCAL : source;
        this.url = url == null ? "" : url;
        this.neteaseId = neteaseId;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.durationMs = Math.max(0L, durationMs);
    }

    public static MusicTrack local(String url, String title) {
        return new MusicTrack(SOURCE_LOCAL, url, 0L, title, "", "", "", 0L);
    }

    public boolean isNetease() {
        return SOURCE_NETEASE.equals(source) && neteaseId > 0L;
    }

    public MusicTrack withUrl(String resolvedUrl) {
        return new MusicTrack(source, resolvedUrl, neteaseId, title, artist, album,
                coverUrl, durationMs);
    }

    public String displayTitle() {
        if (!title.trim().isEmpty()) return title;
        return url;
    }

    protected MusicTrack(Parcel in) {
        source = in.readString();
        url = in.readString();
        neteaseId = in.readLong();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        coverUrl = in.readString();
        durationMs = in.readLong();
    }

    public static final Creator<MusicTrack> CREATOR = new Creator<MusicTrack>() {
        @Override public MusicTrack createFromParcel(Parcel in) {
            return new MusicTrack(in);
        }

        @Override public MusicTrack[] newArray(int size) {
            return new MusicTrack[size];
        }
    };

    @Override public int describeContents() {
        return 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(source);
        dest.writeString(url);
        dest.writeLong(neteaseId);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(coverUrl);
        dest.writeLong(durationMs);
    }
}
