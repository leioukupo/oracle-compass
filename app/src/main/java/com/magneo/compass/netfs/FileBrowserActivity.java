package com.magneo.compass.netfs;


import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
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

/** 网盘/文件舱：远程（FTP/WebDAV/SMB/NFS）文件浏览 + 本地互传 + 打开查看器/播放器。 */
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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new com.magneo.compass.CompassBackground(this), 0);

        // 全屏 oval 列表
        list = new ListView(this);
        com.magneo.compass.ui.OutlineUtil.oval(list);
        list.setBackgroundResource(com.magneo.compass.R.drawable.bg_dialog_oval);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x226b5a2e));
        int minSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int listSize = (int) (minSide * 0.92f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(listSize, listSize, Gravity.CENTER);
        root.addView(list, llp);
        list.post(() -> {
            int pad = (int) (list.getHeight() * 0.08f);
            list.setPadding(0, pad, 0, pad);
        });

        // 浮动按钮层
        int fb = com.magneo.compass.ui.Ui.dp(this, 40);

        // 左上：返回/上级
        Button backBtn = new Button(this);
        backBtn.setText("◀");
        backBtn.setTextColor(Color.rgb(232, 220, 192));
        backBtn.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        backBtn.setLayoutParams(new FrameLayout.LayoutParams(fb, fb));
        backBtn.setOnClickListener(v -> goUp());
        root.addView(backBtn);

        // 右上：切换连接
        Button switchBtn = new Button(this);
        switchBtn.setText("⇄");
        switchBtn.setTextColor(Color.rgb(232, 220, 192));
        switchBtn.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(fb, fb, Gravity.TOP | Gravity.END);
        switchBtn.setLayoutParams(slp);
        switchBtn.setOnClickListener(v -> switchConn());
        root.addView(switchBtn);

        // 右下：⋯ 更多
        Button moreBtn = new Button(this);
        moreBtn.setText("⋯");
        moreBtn.setTextColor(Color.rgb(232, 220, 192));
        moreBtn.setBackgroundResource(com.magneo.compass.R.drawable.bg_oval_dark);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(fb, fb, Gravity.BOTTOM | Gravity.END);
        int margin = com.magneo.compass.ui.Ui.dp(this, 8);
        mlp.setMargins(0, 0, margin, margin);
        moreBtn.setLayoutParams(mlp);
        moreBtn.setOnClickListener(v -> {
            com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("网盘");
            d.item("添加连接", this::addConn);
            d.item("管理连接", this::manageConn);
            d.item("本地存储", this::browseLocal);
            d.cancel().show();
        });
        root.addView(moreBtn);

        // 左下：路径面包屑（浮动）
        breadcrumb = new TextView(this);
        breadcrumb.setTextColor(Color.rgb(232, 220, 192));
        breadcrumb.setTextSize(12);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        blp.setMargins(margin, 0, 0, margin);
        breadcrumb.setLayoutParams(blp);
        root.addView(breadcrumb);

        // 顶中：连接名（浮动）
        connLabel = new TextView(this);
        connLabel.setTextColor(Color.rgb(212, 175, 55));
        connLabel.setTextSize(14);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.setMargins(0, margin, 0, 0);
        connLabel.setLayoutParams(clp);
        root.addView(connLabel);

        setContentView(root);
        StreamProxy.ensure(this);
        conns = FsManager.list(this);
        if (conns.isEmpty()) {
            Toast.makeText(this, "请先添加一个 FTP/WebDAV/SMB/NFS 连接", Toast.LENGTH_LONG).show();
        } else {
            cur = conns.get(0);
            refresh();
        }
    }

    private void goUp() {
        if (!path.isEmpty()) { path = parent(path); refresh(); }
        else finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            goUp();   // 返回键：先回上一级目录，根目录才退出
            return true;
        }
        return super.dispatchKeyEvent(event);
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
                NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
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
        if (!path.isEmpty()) names.add("◂ 返回上级");
        for (NetFs.Entry e : entries) names.add((e.dir ? "▸ " : "• ") + e.name + (e.dir ? "/" : ""));
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return names.size(); }
            @Override public Object getItem(int p) { return names.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup g) {
                MarqueeText tv = cv instanceof MarqueeText ? (MarqueeText) cv : new MarqueeText(FileBrowserActivity.this);
                tv.setText(names.get(p));
                tv.setTextColor(Color.rgb(232, 220, 192));
                tv.setTextSize(16);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(com.magneo.compass.ui.Ui.dp(FileBrowserActivity.this, 10), com.magneo.compass.ui.Ui.dp(FileBrowserActivity.this, 7), com.magneo.compass.ui.Ui.dp(FileBrowserActivity.this, 10), com.magneo.compass.ui.Ui.dp(FileBrowserActivity.this, 7));
                return tv;
            }
        });
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
            i = new Intent(this, VideoPlayerActivity.class).putExtra("url", url)
                    .putExtra("connId", cur.id).putExtra("path", join(path, e.name));
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
                NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
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
                    NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
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
                NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
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
                NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
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
                NetFs fs = FsManager.connect(FileBrowserActivity.this, cur);
                fs.delete(join(path, e.name));
                fs.close();
                runOnUiThread(this::refresh);
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        rd.cancel().show();
    }

    private void addConn() { editConn(null); }

    private void editConn(FsManager.Conn existing) {
        final String[] types = {"FTP", "WebDAV", "SMB", "NFS"};
        final String[] sel = {existing == null ? "FTP" : existing.type};
        final LinearLayout typeRow = new LinearLayout(this);
        final Button[] typeBtns = new Button[types.length];
        final EditText[] user = new EditText[1], pass = new EditText[1];
        final TextView[] nfsHint = new TextView[1];
        final EditText[] domain = new EditText[1];
        for (int i = 0; i < types.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(types[i]);
            b.setTextColor(Color.rgb(232, 220, 192));
            b.setTextSize(13);
            b.setBackgroundResource(sel[0].equals(types[i]) ? com.magneo.compass.R.drawable.bg_oval_gold
                    : com.magneo.compass.R.drawable.bg_oval_dark);
            b.setOnClickListener(v -> {
                sel[0] = types[idx];
                for (int j = 0; j < typeBtns.length; j++) {
                    typeBtns[j].setBackgroundResource(sel[0].equals(types[j])
                            ? com.magneo.compass.R.drawable.bg_oval_gold
                            : com.magneo.compass.R.drawable.bg_oval_dark);
                }
                boolean nfs = sel[0].equals("NFS");
                user[0].setVisibility(nfs ? View.GONE : View.VISIBLE);
                pass[0].setVisibility(nfs ? View.GONE : View.VISIBLE);
                nfsHint[0].setVisibility(nfs ? View.VISIBLE : View.GONE);
                domain[0].setVisibility(sel[0].equals("SMB") ? View.VISIBLE : View.GONE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(com.magneo.compass.ui.Ui.dp(this, 2), 0, com.magneo.compass.ui.Ui.dp(this, 2), 0);
            typeRow.addView(b, lp);
            typeBtns[i] = b;
        }

        final EditText host = new EditText(this); host.setHint("主机/IP");
        final EditText port = new EditText(this); port.setHint("端口（留空=FTP21/WebDAV443/SMB445/NFS2049）");
        user[0] = new EditText(this); user[0].setHint("用户名（留空=匿名）");
        pass[0] = new EditText(this); pass[0].setHint("密码");
        pass[0].setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText root = new EditText(this); root.setHint("根路径/导出路径（如 /music）");
        domain[0] = new EditText(this); domain[0].setHint("SMB 域（可留空）");
        nfsHint[0] = new TextView(this);
        nfsHint[0].setText("NFS 无需账号密码，按 NAS 的 IP 白名单授权");
        nfsHint[0].setTextColor(Color.rgb(120, 114, 98));
        nfsHint[0].setTextSize(12);
        nfsHint[0].setGravity(Gravity.CENTER);

        if (existing != null) {
            host.setText(existing.host);
            if (existing.port > 0) port.setText(String.valueOf(existing.port));
            user[0].setText(existing.user);
            pass[0].setText(existing.pass);
            root.setText(existing.root);
            domain[0].setText(existing.domain);
            for (int j = 0; j < types.length; j++) {
                if (types[j].equals(existing.type)) typeBtns[j].performClick();
            }
        } else {
            domain[0].setVisibility(View.GONE);
            nfsHint[0].setVisibility(View.GONE);
        }

        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this)
                .title(existing == null ? "添加连接" : "编辑连接")
                .view(typeRow).field(host).field(port).field(user[0]).field(pass[0]).field(root)
                .view(nfsHint[0]).field(domain[0]);
        rd.item("保存", () -> {
            FsManager.Conn c = existing == null ? new FsManager.Conn() : existing;   // 编辑时保留 id
            c.type = sel[0];
            c.host = host.getText().toString().trim();
            try { c.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) {}
            c.user = user[0].getText().toString().trim();
            c.pass = pass[0].getText().toString().trim();
            c.root = root.getText().toString().trim();
            c.domain = domain[0].getText().toString().trim();
            c.name = c.host + " (" + c.type + ")";   // 自动命名
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
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("管理连接");
        for (int i = 0; i < conns.size(); i++) {
            final int idx = i;
            rd.item(conns.get(i).name, () -> {
                com.magneo.compass.RoundDialog ops = new com.magneo.compass.RoundDialog(this).title(conns.get(idx).name);
                ops.item("编辑", () -> editConn(conns.get(idx)));
                ops.item("删除", () -> {
                    FsManager.remove(this, conns.get(idx).id);
                    conns = FsManager.list(this);
                    cur = conns.isEmpty() ? null : conns.get(0);
                    path = "";
                    if (cur != null) refresh();
                });
                ops.cancel().show();
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

    /** 单行跑马灯文本：isFocused() 恒真让超长文本自动滚动，且不抢占列表项点击。 */
    private static class MarqueeText extends TextView {
        MarqueeText(android.content.Context c) {
            super(c);
            setSingleLine(true);
            setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            setMarqueeRepeatLimit(-1);
        }
        @Override public boolean isFocused() { return true; }
    }
}
