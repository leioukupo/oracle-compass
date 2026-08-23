package com.magneo.compass.netfs;

import android.content.Context;


import com.magneo.compass.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 网盘连接配置的存取与连接器工厂。密码明文存 SharedPreferences（API 22 无 Keystore AES）。 */
public class FsManager {

    public static class Conn {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String type = "FTP"; // FTP / WebDAV / SMB
        public String host = "";
        public int port = 0;
        public String user = "";
        public String pass = "";
        public String root = "";
        public String domain = ""; // SMB 域
    }

    public static List<Conn> list(Context c) {
        List<Conn> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c, Prefs.K_FS_CONNECTIONS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Conn cn = new Conn();
                cn.id = o.optString("id", UUID.randomUUID().toString());
                cn.name = o.optString("name");
                cn.type = o.optString("type", "FTP");
                cn.host = o.optString("host");
                cn.port = o.optInt("port", 0);
                cn.user = o.optString("user");
                cn.pass = o.optString("pass");
                cn.root = o.optString("root");
                cn.domain = o.optString("domain");
                out.add(cn);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void save(Context c, Conn cn) {
        List<Conn> all = list(c);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(cn.id)) { all.set(i, cn); found = true; break; }
        }
        if (!found) all.add(cn);
        write(c, all);
        Prefs.exportBackup(c);
    }

    public static void remove(Context c, String id) {
        List<Conn> all = list(c);
        all.removeIf(x -> x.id.equals(id));
        write(c, all);
        Prefs.exportBackup(c);
    }

    public static Conn byId(Context c, String id) {
        for (Conn cn : list(c)) if (cn.id.equals(id)) return cn;
        return null;
    }

    private static void write(Context c, List<Conn> all) {
        JSONArray a = new JSONArray();
        for (Conn cn : all) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", cn.id); o.put("name", cn.name); o.put("type", cn.type);
                o.put("host", cn.host); o.put("port", cn.port); o.put("user", cn.user);
                o.put("pass", cn.pass); o.put("root", cn.root); o.put("domain", cn.domain);
                a.put(o);
            } catch (Exception ignored) {}
        }
        Prefs.put(c, Prefs.K_FS_CONNECTIONS, a.toString());
    }

    public static NetFs connect(Context c, Conn cn) throws Exception {
        switch (cn.type) {
            case "WebDAV": return new WebDavFs(c, cn);
            case "SMB": return new SmbFs(cn);
            case "NFS": return new NfsFs(cn);
            default: return new FtpFs(cn);
        }
    }

    public static int defaultPort(Conn cn) {
        if (cn.port > 0) return cn.port;
        if (cn.type.equals("WebDAV")) return 443;
        if (cn.type.equals("SMB")) return 445;
        if (cn.type.equals("NFS")) return 2049;
        return 21;
    }

}
