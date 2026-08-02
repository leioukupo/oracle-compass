package com.magneo.compass.netfs;

import jcifs.CIFSContext;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** SMB 连接器（jcifs-ng，支持 SMB1/2/3 与域）。 */
public class SmbFs implements NetFs {
    private final CIFSContext ctx;
    private final String baseUrl;

    public SmbFs(FsManager.Conn c) throws Exception {
        NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator(
                c.domain == null ? "" : c.domain, c.user, c.pass);
        ctx = new BaseContext(new jcifs.config.PropertyConfiguration(null)).withCredentials(auth);
        int port = FsManager.defaultPort(c);
        baseUrl = "smb://" + c.host + (port == 445 ? "" : ":" + port) + (c.root == null ? "" : c.root);
    }

    private String full(String path) {
        String p = path == null || path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        String url = baseUrl + p;
        return url.replace(" ", "%20");
    }

    private SmbFile sf(String path) throws Exception {
        return new SmbFile(full(path), ctx);
    }

    @Override public List<Entry> list(String path) throws Exception {
        SmbFile dir = sf(path == null || path.isEmpty() ? "/" : path);
        if (!dir.isDirectory()) throw new Exception("不是目录");
        SmbFile[] files = dir.listFiles();
        List<Entry> out = new ArrayList<>();
        if (files == null) return out;
        for (SmbFile f : files) {
            Entry e = new Entry();
            String n = f.getName();
            e.name = n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
            e.dir = f.isDirectory();
            e.size = f.isFile() ? f.length() : 0;
            e.mtime = f.getLastModified();
            out.add(e);
        }
        return out;
    }

    @Override public InputStream open(String path) throws Exception {
        return sf(path).getInputStream();
    }

    @Override public void upload(String path, InputStream in, long len) throws Exception {
        OutputStream out = sf(path).getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
    }

    @Override public void mkdir(String path) throws Exception {
        sf(path).mkdir();
    }

    @Override public void rename(String from, String to) throws Exception {
        sf(from).renameTo(sf(to));
    }

    @Override public void delete(String path) throws Exception {
        sf(path).delete();
    }

    @Override public void close() {}
}
