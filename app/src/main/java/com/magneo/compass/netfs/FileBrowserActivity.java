package com.magneo.compass.netfs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网盘/文件舱：远程（FTP/WebDAV/SMB）文件浏览 + 本地互传 + 打开查看器/播放器。 */
public class FileBrowserActivity extends com.magneo.compass.BaseActivity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private List<FsManager.Conn> conns = new ArrayList<>();
    private FsManager.Conn cur;
    private String path = "";
    private List<NetFs.Entry> entries = new ArrayList<>();
    private ListView list;
    private TextView breadcrumb, connLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 10, 10));

        connLabel = new TextView(this);
        connLabel.setTextColor(Color.rgb(212, 175, 55));
        connLabel.setTextSize(18);
        connLabel.setGravity(Gravity.CENTER);
        root.addView(connLabel);

        root.addView(new com.magneo.compass.BackButton(this));

        LinearLayout row = new LinearLayout(this);
        row.addView(btn("切换", v -> switchConn()));
        row.addView(btn("添加", v -> addConn()));
        row.addView(btn("管理", v -> manageConn()));
        row.addView(btn("本地", v -> browseLocal()));
        root.addView(row);

        breadcrumb = new TextView(this);
        breadcrumb.setTextColor(Color.rgb(232, 220, 192));
        breadcrumb.setPadding(12, 8, 12, 8);
        root.addView(breadcrumb);

        list = new ListView(this);
        list.setBackgroundColor(Color.rgb(10, 10, 10));
        list.setDivider(null);
        list.setClipToOutline(true);
        list.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list.post(() -> {
            int pad = (int) (list.getHeight() * 0.18f);
            list.setPadding(0, pad, 0, pad);
        });

        setContentView(root);
        StreamProxy.ensure(this);
        conns = FsManager.list(this);
        if (conns.isEmpty()) {
            Toast.makeText(this, "请先添加一个 FTP/WebDAV/SMB 连接", Toast.LENGTH_LONG).show();
        } else {
            cur = conns.get(0);
            refresh();
        }
    }

    private Button btn(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.rgb(232, 220, 192));
        b.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        if (cur == null) return;
        connLabel.setText(cur.name + "（" + cur.type + "）");
        breadcrumb.setText("/" + path);
        exec.execute(() -> {
            try {
                NetFs fs = FsManager.connect(cur);
                List<NetFs.Entry> list = fs.list(path);
                fs.close();
                Collections.sort(list, (a, b) -> {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                });
                runOnUiThread(() -> { entries = list; render(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "列目录失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void render() {
        List<String> names = new ArrayList<>();
        if (!path.isEmpty()) names.add("..");
        for (NetFs.Entry e : entries) names.add((e.dir ? "📁 " : "📄 ") + e.name + (e.dir ? "/" : ""));
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((AdapterView<?> p, android.view.View v, int pos, long id) -> {
            if (!path.isEmpty() && pos == 0) {
                path = parent(path);
                refresh();
                return;
            }
            int idx = path.isEmpty() ? pos : pos - 1;
            if (idx < 0 || idx >= entries.size()) return;
            NetFs.Entry e = entries.get(idx);
            if (e.dir) {
                path = join(path, e.name);
                refresh();
            } else {
                openFile(e);
            }
        });
        list.setOnItemLongClickListener((AdapterView<?> p, android.view.View v, int pos, long id) -> {
            int idx = path.isEmpty() ? pos : pos - 1;
            if (idx < 0 || idx >= entries.size()) return false;
            NetFs.Entry e = entries.get(idx);
            String[] ops = e.dir
                    ? new String[]{"上传文件到此目录", "新建文件夹", "重命名", "删除"}
                    : new String[]{"下载到本地", "打开", "重命名", "删除"};
            com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title(e.name);
            if (e.dir) {
                rd.item("上传文件到此目录", () -> uploadTo(join(path, e.name)));
                rd.item("新建文件夹", () -> mkdir(join(path, e.name)));
            } else {
                rd.item("下载到本地", () -> downloadTo(e));
                rd.item("打开", () -> openFile(e));
            }
            rd.item("重命名", () -> rename(e));
            rd.item("删除", () -> del(e));
            rd.cancel().show();
            return true;
        });
    }

    private String join(String p, String name) { return p.isEmpty() ? name : p + "/" + name; }
    private String parent(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private void openFile(NetFs.Entry e) {
        String url = StreamProxy.urlFor(cur.id, join(path, e.name));
        String n = e.name.toLowerCase();
        Intent i = null;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp")) {
            i = new Intent(this, ImageViewerActivity.class).putExtra("url", url);
        } else if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".aac") || n.endsWith(".m4a") || n.endsWith(".ogg")) {
            List<String> urls = new ArrayList<>();
            for (NetFs.Entry x : entries) {
                String xn = x.name.toLowerCase();
                if (!x.dir && (xn.endsWith(".mp3") || xn.endsWith(".wav") || xn.endsWith(".flac")
                        || xn.endsWith(".aac") || xn.endsWith(".m4a") || xn.endsWith(".ogg"))) {
                    urls.add(StreamProxy.urlFor(cur.id, join(path, x.name)));
                }
            }
            i = new Intent(this, MusicPlayerActivity.class).putStringArrayListExtra("urls", (ArrayList<String>) urls);
        } else if (n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".webm")) {
            i = new Intent(this, VideoPlayerActivity.class).putExtra("url", url);
        } else if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".html") || n.endsWith(".csv")) {
            i = new Intent(this, TextViewerActivity.class).putExtra("url", url);
        }
        if (i != null) startActivity(i);
        else downloadTo(e);
    }

    private void downloadTo(NetFs.Entry e) {
        String remote = join(path, e.name);
        File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), e.name);
        exec.execute(() -> {
            try {
                NetFs fs = FsManager.connect(cur);
                InputStream in = fs.open(remote);
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close(); fs.close();
                runOnUiThread(() -> Toast.makeText(this, "已下载到 " + out.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "下载失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void uploadTo(String dir) {
        final EditText et = new EditText(this);
        et.setHint("本地文件路径，如 /sdcard/Download/a.mp3");
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("上传到 " + dir).field(et);
        rd.item("上传", () -> {
            String local = et.getText().toString().trim();
            exec.execute(() -> {
                try {
                    File f = new File(local);
                    NetFs fs = FsManager.connect(cur);
                    fs.upload(join(dir, f.getName()), new java.io.FileInputStream(f), f.length());
                    fs.close();
                    runOnUiThread(() -> Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "上传失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        rd.cancel().show();
    }

    private void mkdir(String dir) {
        final EditText et = new EditText(this);
        et.setHint("文件夹名");
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("新建文件夹于 /" + path).field(et);
        rd.item("创建", () -> exec.execute(() -> {
            try {
                NetFs fs = FsManager.connect(cur);
                fs.mkdir(join(dir, et.getText().toString().trim()));
                fs.close();
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        rd.cancel().show();
    }

    private void rename(NetFs.Entry e) {
        final EditText et = new EditText(this);
        et.setText(e.name);
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("重命名").field(et);
        rd.item("确定", () -> exec.execute(() -> {
            try {
                NetFs fs = FsManager.connect(cur);
                fs.rename(join(path, e.name), join(path, et.getText().toString().trim()));
                fs.close();
                runOnUiThread(this::refresh);
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        rd.cancel().show();
    }

    private void del(NetFs.Entry e) {
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("删除 " + e.name + "？");
        rd.item("删除", () -> exec.execute(() -> {
            try {
                NetFs fs = FsManager.connect(cur);
                fs.delete(join(path, e.name));
                fs.close();
                runOnUiThread(this::refresh);
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        rd.cancel().show();
    }

    private void addConn() {
        final EditText type = new EditText(this); type.setText("FTP"); type.setHint("类型：FTP / WebDAV / SMB");
        final EditText name = new EditText(this); name.setHint("名称");
        final EditText host = new EditText(this); host.setHint("主机/IP");
        final EditText port = new EditText(this); port.setHint("端口（可留空）");
        final EditText user = new EditText(this); user.setHint("用户名");
        final EditText pass = new EditText(this); pass.setHint("密码");
        final EditText root = new EditText(this); root.setHint("根路径（如 /music）");
        final EditText domain = new EditText(this); domain.setHint("SMB 域（可留空）");
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("添加连接")
                .field(type).field(name).field(host).field(port).field(user).field(pass).field(root).field(domain);
        rd.item("保存", () -> {
            FsManager.Conn c = new FsManager.Conn();
            c.name = name.getText().toString().trim();
            c.type = type.getText().toString().trim();
            c.host = host.getText().toString().trim();
            try { c.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) {}
            c.user = user.getText().toString().trim();
            c.pass = pass.getText().toString().trim();
            c.root = root.getText().toString().trim();
            c.domain = domain.getText().toString().trim();
            FsManager.save(this, c);
            conns = FsManager.list(this);
            cur = c;
            path = "";
            refresh();
        });
        rd.cancel().show();
    }

    private void switchConn() {
        if (conns.isEmpty()) { Toast.makeText(this, "暂无连接", Toast.LENGTH_SHORT).show(); return; }
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("选择连接");
        for (int i = 0; i < conns.size(); i++) {
            final int idx = i;
            rd.item(conns.get(i).name, () -> { cur = conns.get(idx); path = ""; refresh(); });
        }
        rd.cancel().show();
    }

    private void manageConn() {
        if (conns.isEmpty()) { Toast.makeText(this, "暂无连接", Toast.LENGTH_SHORT).show(); return; }
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("管理连接（点击删除）");
        for (int i = 0; i < conns.size(); i++) {
            final int idx = i;
            rd.item(conns.get(i).name, () -> {
                FsManager.remove(this, conns.get(idx).id);
                conns = FsManager.list(this);
                cur = conns.isEmpty() ? null : conns.get(0);
                path = "";
                if (cur != null) refresh();
            });
        }
        rd.cancel().show();
    }

    private void browseLocal() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) { Toast.makeText(this, "本地下载目录为空", Toast.LENGTH_SHORT).show(); return; }
        List<File> fs = new ArrayList<>();
        for (File f : files) if (f.isFile()) fs.add(f);
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("本地 /sdcard/Download");
        for (int i = 0; i < fs.size(); i++) {
            final File f = fs.get(i);
            rd.item(f.getName(), () -> openLocal(f));
        }
        rd.cancel().show();
    }

    private void openLocal(File f) {
        String url = "file://" + f.getAbsolutePath();
        String n = f.getName().toLowerCase();
        Intent i = null;
        if (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp"))
            i = new Intent(this, ImageViewerActivity.class).putExtra("url", url);
        else if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".m4a") || n.endsWith(".ogg"))
            i = new Intent(this, MusicPlayerActivity.class).putStringArrayListExtra("urls",
                    new ArrayList<>(Collections.singletonList(url)));
        else if (n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".avi"))
            i = new Intent(this, VideoPlayerActivity.class).putExtra("url", url);
        else if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".xml"))
            i = new Intent(this, TextViewerActivity.class).putExtra("url", url);
        else Toast.makeText(this, "无法预览，可用浏览器/上传使用", Toast.LENGTH_SHORT).show();
        if (i != null) startActivity(i);
    }
}
