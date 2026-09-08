package com.magneo.compass.netfs;

import java.io.InputStream;
import java.util.List;

/** 统一网络文件系统接口（FTP / WebDAV / SMB 都实现它）。 */
public interface NetFs {
    class Entry {
        public String name;
        public boolean dir;
        public long size;
        public long mtime;
    }

    List<Entry> list(String path) throws Exception;
    InputStream open(String path) throws Exception;

    /**
     * Open a file at a byte offset for media range requests. Implementations
     * with native range support should override this; the fallback preserves
     * compatibility with the older sequential-only backends.
     */
    default InputStream openAt(String path, long offset) throws Exception {
        InputStream in = open(path);
        long left = Math.max(0L, offset);
        byte[] scratch = new byte[8192];
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                int n = in.read(scratch, 0, (int) Math.min(scratch.length, left));
                if (n < 0) break;
                skipped = n;
            }
            left -= skipped;
        }
        return in;
    }
    void upload(String path, InputStream in, long len) throws Exception;
    void mkdir(String path) throws Exception;
    void rename(String from, String to) throws Exception;
    void delete(String path) throws Exception;
    void close();
}
