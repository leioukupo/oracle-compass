package com.magneo.compass.netfs;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** FTP/FTPS 连接器（Apache Commons Net，被动模式，UTF-8）。 */
public class FtpFs implements NetFs {
    private final FTPClient ftp;
    private final String base;

    public FtpFs(FsManager.Conn c) throws Exception {
        ftp = new FTPClient();
        // 必须在 connect 之前设置控制编码：commons-net 在连接时按当时编码建控制流，
        // 连接后再 setControlEncoding 不生效，中文路径的 LIST/RETR 会乱码导致目录列空、文件打不开
        ftp.setControlEncoding("UTF-8");
        int port = FsManager.defaultPort(c);
        ftp.connect(c.host, port);
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftp.disconnect();
            throw new Exception("FTP 连接失败: " + reply);
        }
        ftp.login(c.user, c.pass);
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        String b = c.root == null ? "" : c.root;
        if (!b.isEmpty() && !b.startsWith("/")) b = "/" + b;
        base = b;
    }

    private String full(String path) {
        if (path == null || path.isEmpty()) return base.isEmpty() ? "/" : base;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    @Override public List<Entry> list(String path) throws Exception {
        FTPFile[] files = ftp.listFiles(full(path));
        List<Entry> out = new ArrayList<>();
        if (files == null) return out;
        for (FTPFile f : files) {
            Entry e = new Entry();
            e.name = f.getName();
            e.dir = f.isDirectory();
            e.size = f.getSize();
            if (f.getTimestamp() != null) e.mtime = f.getTimestamp().getTimeInMillis();
            if (e.name.equals(".") || e.name.equals("..")) continue;
            out.add(e);
        }
        return out;
    }

    @Override public InputStream open(String path) throws Exception {
        InputStream in = ftp.retrieveFileStream(full(path));
        if (in == null) throw new Exception("打开失败 " + ftp.getReplyString());
        return in;
    }

    @Override public void upload(String path, InputStream in, long len) throws Exception {
        OutputStream out = ftp.storeFileStream(full(path));
        if (out == null) throw new Exception("上传失败 " + ftp.getReplyString());
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        ftp.completePendingCommand();
    }

    @Override public void mkdir(String path) throws Exception {
        if (!ftp.makeDirectory(full(path))) throw new Exception("新建文件夹失败");
    }

    @Override public void rename(String from, String to) throws Exception {
        if (!ftp.rename(full(from), full(to))) throw new Exception("重命名失败");
    }

    @Override public void delete(String path) throws Exception {
        if (!ftp.deleteFile(full(path)) && !ftp.removeDirectory(full(path))) throw new Exception("删除失败");
    }

    @Override public void close() {
        try { ftp.logout(); } catch (Exception ignored) {}
        try { ftp.disconnect(); } catch (Exception ignored) {}
    }
}
