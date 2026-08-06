package com.magneo.compass.netfs;

import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry;
import com.emc.ecs.nfsclient.nfs.NfsGetAttributes;
import com.emc.ecs.nfsclient.nfs.NfsReaddirplusResponse;
import com.emc.ecs.nfsclient.nfs.NfsType;
import com.emc.ecs.nfsclient.nfs.io.Nfs3File;
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream;
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream;
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3;
import com.emc.ecs.nfsclient.rpc.CredentialUnix;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** NFS 连接器（EMC nfs-client，NFSv3，经 portmap 自动挂载）。
 *  NFS 按 IP 白名单授权（AUTH_SYS），无用户名/密码；root 字段填导出路径，如 /volume1/media。 */
public class NfsFs implements NetFs {
    private final Nfs3 client;

    public NfsFs(FsManager.Conn c) throws Exception {
        String export = c.root == null || c.root.trim().isEmpty() ? "/" : c.root.trim();
        CredentialUnix cred = new CredentialUnix(0, 0, null);   // uid/gid=root
        client = new Nfs3(c.host, export, cred, 5);
    }

    private Nfs3File file(String path) throws Exception {
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!p.startsWith("/")) p = "/" + p;
        return client.newFile(p);
    }

    @Override public List<Entry> list(String path) throws Exception {
        List<Entry> out = new ArrayList<>();
        Nfs3File dir = file(path);
        long cookie = 0, cookieverf = 0;
        while (true) {
            NfsReaddirplusResponse r = dir.readdirplus(cookie, cookieverf, 8192, 16384);
            List<NfsDirectoryPlusEntry> entries = r.getEntries();
            if (entries == null || entries.isEmpty()) break;
            for (NfsDirectoryPlusEntry e : entries) {
                String name = e.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                Entry en = new Entry();
                en.name = name;
                NfsGetAttributes a = e.getAttributes();
                if (a != null) {
                    en.dir = a.getType() == NfsType.NFS_DIR;
                    en.size = a.getSize();
                    en.mtime = a.getMtime() == null ? 0 : a.getMtime().getTimeInMillis();
                }
                out.add(en);
            }
            if (r.isEof()) break;
            cookie = r.getCookie();
            cookieverf = r.getCookieverf();
        }
        return out;
    }

    @Override public InputStream open(String path) throws Exception {
        return new NfsFileInputStream(file(path));
    }

    @Override public void upload(String path, InputStream in, long len) throws Exception {
        OutputStream fo = new NfsFileOutputStream(file(path));
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        } finally {
            fo.close();
        }
    }

    @Override public void mkdir(String path) throws Exception {
        file(path).mkdir();
    }

    @Override public void rename(String from, String to) throws Exception {
        file(from).renameTo(file(to));
    }

    @Override public void delete(String path) throws Exception {
        file(path).delete();
    }

    @Override public void close() {
        // EMC 客户端由 RpcWrapper 内部管理连接，无显式 close
    }
}
