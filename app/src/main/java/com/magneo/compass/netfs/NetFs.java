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
    void upload(String path, InputStream in, long len) throws Exception;
    void mkdir(String path) throws Exception;
    void rename(String from, String to) throws Exception;
    void delete(String path) throws Exception;
    void close();
}
