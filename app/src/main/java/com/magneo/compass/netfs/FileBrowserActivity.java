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

import com.magneo.compass.Prefs;
import com.magneo.compass.ui.Ui;

/** 网盘/文件舱：远程（FTP/WebDAV/SMB/NFS）文件浏览 + 本地互传 + 打开查看器/播放器。 */
public class FileBrowserActivity extends com.magneo.compass.BaseActivity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private List<FsManager.Conn> conns = new ArrayList<>();
    private FsManager.Conn cur;
    private NetFs activeFs;
    private String activeConnId = "";
    private String path = "";
    private List<NetFs.Entry> entries = new ArrayList<>();
    private ListView list;
    private LinearLayout headerCard;
    private LinearLayout bottomTools;
    private LinearLayout emptyPanel;
    private TextView breadcrumb, connLabel;
    private boolean loading = false;
    private int loadSerial = 0;

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
        int listSize = (int) (minSide * 0.86f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(listSize, listSize, Gravity.CENTER);
        root.addView(list, llp);
        list.post(() -> {
            int sidePad = (int) (list.getWidth() * 0.10f);
            int topPad = (int) (list.getHeight() * 0.09f);
            int bottomPad = (int) (list.getHeight() * 0.23f);
            list.setPadding(sidePad, topPad, sidePad, bottomPad);
        });
        list.setVerticalScrollBarEnabled(false);

        headerCard = new LinearLayout(this);
        headerCard.setOrientation(LinearLayout.VERTICAL);
        headerCard.setGravity(Gravity.CENTER);
        headerCard.setBackgroundColor(Color.TRANSPARENT);
        headerCard.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        connLabel = new TextView(this);
        connLabel.setTextColor(Ui.COLOR_GOLD);
        connLabel.setTextSize(15);
        connLabel.setGravity(Gravity.CENTER);
        connLabel.setSingleLine(true);
        connLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        headerCard.addView(connLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        breadcrumb = new TextView(this);
        breadcrumb.setTextColor(Ui.COLOR_TEXT_DIM);
        breadcrumb.setTextSize(12);
        breadcrumb.setGravity(Gravity.CENTER);
        breadcrumb.setSingleLine(true);
        breadcrumb.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams blpCard = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blpCard.topMargin = Ui.dp(this, 3);
        headerCard.addView(breadcrumb, blpCard);
        headerCard.setOnClickListener(v -> showConnMenu());
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams((int) (listSize * 0.58f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(headerCard, hlp);

        emptyPanel = new LinearLayout(this);
        emptyPanel.setOrientation(LinearLayout.VERTICAL);
        emptyPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyPanel.setBackgroundResource(com.magneo.compass.R.drawable.bg_dialog_oval);
        emptyPanel.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 20));
        TextView emptyTitle = new TextView(this);
        emptyTitle.setText("未添加网盘连接");
        emptyTitle.setTextColor(Ui.COLOR_GOLD);
        emptyTitle.setTextSize(18);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyPanel.addView(emptyTitle, emptyLp(0));
        TextView emptySub = new TextView(this);
        emptySub.setText("支持 FTP / WebDAV / SMB / NFS");
        emptySub.setTextColor(Ui.COLOR_TEXT_DIM);
        emptySub.setTextSize(13);
        emptySub.setGravity(Gravity.CENTER);
        emptyPanel.addView(emptySub, emptyLp(6));
        LinearLayout emptyActions = new LinearLayout(this);
        emptyActions.setOrientation(LinearLayout.HORIZONTAL);
        emptyActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addLp.rightMargin = Ui.dp(this, 10);
        emptyActions.addView(emptyPrimaryButton("添加连接", v -> addConn()), addLp);
        emptyActions.addView(iconButton("⋯", v -> showMoreMenu()));
        emptyPanel.addView(emptyActions, emptyLp(16));
        emptyPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams((int) (listSize * 0.54f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(emptyPanel, elp);

        bottomTools = new LinearLayout(this);
        bottomTools.setOrientation(LinearLayout.HORIZONTAL);
        bottomTools.setGravity(Gravity.CENTER);
        bottomTools.addView(iconButton("⋯", v -> showMoreMenu()));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        alp.setMargins(0, 0, 0, Ui.dp(this, 30));
        root.addView(bottomTools, alp);
        headerCard.setVisibility(View.GONE);
        bottomTools.setVisibility(View.GONE);

        setContentView(root);
        StreamProxy.ensure(this);
        conns = FsManager.list(this);
        if (conns.isEmpty()) {
            renderEmptyState();
        } else {
            cur = conns.get(0);
            refresh();
        }
    }

    private void goUp() {
        if (loading) return;
        if (!path.isEmpty()) navigateTo(parent(path));
        else finish();
    }

    @Override
    protected void onDestroy() {
        closeActiveFs();
        exec.shutdownNow();
        super.onDestroy();
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

    private Button navButton(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        Ui.styleIconButton(b);
        int size = Ui.dp(this, 42);
        b.setTextSize(s.length() > 1 ? 13 : 17);
        b.setMinWidth(size);
        b.setMinHeight(size);
        b.setMinimumWidth(size);
        b.setMinimumHeight(size);
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        b.setOnClickListener(l);
        return b;
    }

    private Button iconButton(String s, android.view.View.OnClickListener l) {
        return navButton(s, l);
    }

    private Button emptyPrimaryButton(String s, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        Ui.stylePillButton(b);
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams emptyLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, topDp), 0, 0);
        return lp;
    }

    private void refresh() {
        loadPath(path);
    }

    private void navigateTo(String targetPath) {
        if (loading || targetPath == null) return;
        loadPath(targetPath);
    }

    private void loadPath(String targetPath) {
        if (cur == null) return;
        loading = true;
        list.setEnabled(false);
        emptyPanel.setVisibility(View.GONE);
        headerCard.setVisibility(View.VISIBLE);
        bottomTools.setVisibility(View.VISIBLE);
        connLabel.setText(cur.name + "（" + cur.type + "）");
        breadcrumb.setText("/" + targetPath + " · 加载中…");
        final FsManager.Conn target = cur;
        final int serial = ++loadSerial;
        exec.execute(() -> {
            try {
                NetFs fs = ensureFs(target);
                List<NetFs.Entry> list = fs.list(targetPath);
                Collections.sort(list, (a, b) -> {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                });
                runOnUiThread(() -> {
                    if (!isCurrentLoad(serial, target)) return;
                    path = targetPath;
                    entries = list;
                    loading = false;
                    this.list.setEnabled(true);
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentLoad(serial, target)) return;
                    loading = false;
                    list.setEnabled(true);
                    handleLoadError(target, targetPath, e);
                });
            }
        });
    }

    private boolean isCurrentLoad(int serial, FsManager.Conn target) {
        return serial == loadSerial && cur != null && target != null && cur.id.equals(target.id);
    }

    private void handleLoadError(FsManager.Conn target, String targetPath, Exception e) {
        String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
        breadcrumb.setText("/" + path);
        if (cur != null && target != null && !cur.id.equals(target.id)) return;
        if (isSslError(e) && !Prefs.getB(this, Prefs.K_IGNORE_SSL, false)) {
            new com.magneo.compass.RoundDialog(this)
                    .title("证书校验失败")
                    .text("这个网盘看起来用了设备不信任的证书。可以在这里直接开启忽略证书，然后重新加载。")
                    .item("开启并重试", () -> {
                        Prefs.putB(this, Prefs.K_IGNORE_SSL, true);
                        reloadConnection();
                    })
                    .cancel()
                    .show();
            return;
        }
        if (target != null && "WebDAV".equals(target.type) && msg.contains("404")) {
            Toast.makeText(this, "WebDAV 404：检查 root、账号密码或服务端路径", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "列目录失败: " + msg, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isSslError(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof javax.net.ssl.SSLHandshakeException
                    || cur instanceof javax.net.ssl.SSLPeerUnverifiedException) return true;
            String m = cur.getMessage();
            if (m != null) {
                String s = m.toLowerCase();
                if (s.contains("trust anchor") || s.contains("certificate") || s.contains("ssl")) return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private void reloadConnection() {
        closeActiveFs();
        refresh();
    }

    private NetFs ensureFs(FsManager.Conn target) throws Exception {
        if (target == null) throw new Exception("没有可用连接");
        if (activeFs != null && target.id.equals(activeConnId)) return activeFs;
        closeActiveFs();
        activeFs = FsManager.connect(this, target);
        activeConnId = target.id;
        return activeFs;
    }

    private void closeActiveFs() {
        if (activeFs != null) {
            try { activeFs.close(); } catch (Exception ignored) {}
            activeFs = null;
        }
        activeConnId = "";
    }

    private void renderEmptyState() {
        closeActiveFs();
        cur = null;
        path = "";
        loading = false;
        loadSerial++;
        entries = new ArrayList<>();
        list.setEnabled(true);
        headerCard.setVisibility(View.GONE);
        bottomTools.setVisibility(View.GONE);
        emptyPanel.setVisibility(View.VISIBLE);
        list.setAdapter(null);
        list.setOnItemClickListener(null);
        list.setOnItemLongClickListener(null);
    }

    private void render() {
        updateHeader(false);
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
            if (loading) return;
            if (!path.isEmpty() && pos == 0) {
                navigateTo(parent(path));
                return;
            }
            int idx = path.isEmpty() ? pos : pos - 1;
            if (idx < 0 || idx >= entries.size()) return;
            NetFs.Entry e = entries.get(idx);
            if (e.dir) {
                navigateTo(join(path, e.name));
            } else {
                openFile(e);
            }
        });
        list.setOnItemLongClickListener((AdapterView<?> p, android.view.View v, int pos, long id) -> {
            if (loading) return true;
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

    private void updateHeader(boolean isLoading) {
        if (cur != null) connLabel.setText(cur.name + "（" + cur.type + "）");
        String shown = path == null || path.isEmpty() ? "/" : "/" + path;
        breadcrumb.setText(isLoading ? shown + " · 加载中…" : shown);
    }

    private String join(String p, String name) { return p.isEmpty() ? name : p + "/" + name; }
    private String parent(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private boolean isImageName(String n) {
        n = n == null ? "" : n.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    private boolean isAudioName(String n) {
        n = n == null ? "" : n.toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac")
                || n.endsWith(".aac") || n.endsWith(".m4a") || n.endsWith(".ogg");
    }

    private boolean isVideoName(String n) {
        n = n == null ? "" : n.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv")
                || n.endsWith(".avi") || n.endsWith(".webm");
    }

    private boolean isTextName(String n) {
        n = n == null ? "" : n.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")
                || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".html")
                || n.endsWith(".csv");
    }

    private void openFile(NetFs.Entry e) {
        String remotePath = join(path, e.name);
        String url = StreamProxy.urlFor(cur.id, remotePath);
        String n = e.name.toLowerCase();
        Intent i = null;
        if (isImageName(n)) {
            ArrayList<String> urls = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            int index = 0;
            for (NetFs.Entry x : entries) {
                if (!x.dir && isImageName(x.name)) {
                    if (x.name.equals(e.name)) index = urls.size();
                    urls.add(StreamProxy.urlFor(cur.id, join(path, x.name)));
                    names.add(x.name);
                }
            }
            i = new Intent(this, ImageViewerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", e.name)
                    .putExtra("name", e.name)
                    .putExtra("size", e.size)
                    .putStringArrayListExtra("urls", urls)
                    .putStringArrayListExtra("names", names)
                    .putExtra("index", index);
        } else if (isAudioName(n)) {
            List<String> urls = new ArrayList<>();
            for (NetFs.Entry x : entries) {
                if (!x.dir && isAudioName(x.name)) {
                    urls.add(StreamProxy.urlFor(cur.id, join(path, x.name)));
                }
            }
            i = new Intent(this, MusicPlayerActivity.class).putStringArrayListExtra("urls", (ArrayList<String>) urls);
        } else if (isVideoName(n)) {
            String mime = guessMime(n);
            url = StreamProxy.urlFor(cur.id, remotePath, e.size, mime);
            i = new Intent(this, VideoPlayerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", e.name)
                    .putExtra("name", e.name)
                    .putExtra("size", e.size)
                    .putExtra("connId", cur.id)
                    .putExtra("path", remotePath)
                    .putExtra("mime", mime);
        } else if (isTextName(n)) {
            i = new Intent(this, TextViewerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", e.name)
                    .putExtra("name", e.name)
                    .putExtra("size", e.size);
        }
        if (i != null) startActivity(i);
        else downloadTo(e);
    }

    private void downloadTo(NetFs.Entry e) {
        String remote = join(path, e.name);
        File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), e.name);
        exec.execute(() -> {
            try {
                NetFs fs = ensureFs(cur);
                InputStream in = fs.open(remote);
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close();
                in.close();
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
                    NetFs fs = ensureFs(cur);
                    fs.upload(join(dir, f.getName()), new java.io.FileInputStream(f), f.length());
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
                NetFs fs = ensureFs(cur);
                fs.mkdir(join(dir, et.getText().toString().trim()));
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
                NetFs fs = ensureFs(cur);
                fs.rename(join(path, e.name), join(path, et.getText().toString().trim()));
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
                NetFs fs = ensureFs(cur);
                fs.delete(join(path, e.name));
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
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setTextColor(Color.rgb(232, 220, 192));
            b.setTextSize(12);
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
        final EditText port = new EditText(this); port.setHint("端口（留空=默认）");
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
            String hostText = host.getText().toString().trim();
            if (hostText.isEmpty()) {
                Toast.makeText(this, "请填写主机/IP", Toast.LENGTH_SHORT).show();
                return;
            }
            FsManager.Conn c = existing == null ? new FsManager.Conn() : existing;   // 编辑时保留 id
            c.type = sel[0];
            c.host = hostText;
            c.port = 0;
            try { c.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) {}
            c.user = user[0].getText().toString().trim();
            c.pass = pass[0].getText().toString().trim();
            c.root = root.getText().toString().trim();
            c.domain = domain[0].getText().toString().trim();
            c.name = c.host + " (" + c.type + ")";   // 自动命名
            FsManager.save(this, c);
            conns = FsManager.list(this);
            closeActiveFs();
            cur = c;
            path = "";
            refresh();
        });
        rd.cancel().show();
    }

    private void switchConn() {
        if (conns.isEmpty()) {
            renderEmptyState();
            return;
        }
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("选择连接");
        for (int i = 0; i < conns.size(); i++) {
            final int idx = i;
            rd.item(conns.get(i).name, () -> {
                closeActiveFs();
                cur = conns.get(idx);
                path = "";
                refresh();
            });
        }
        rd.cancel().show();
    }

    private void showConnMenu() {
        conns = FsManager.list(this);
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("网盘连接");
        if (cur != null) d.text(cur.name + "（" + cur.type + "）");
        else d.text("当前没有连接");
        d.item(Prefs.getB(this, Prefs.K_IGNORE_SSL, false) ? "证书：已忽略" : "证书：默认校验", this::toggleIgnoreSsl);
        d.item("添加连接", this::addConn);
        if (!conns.isEmpty()) {
            d.item("切换连接", this::switchConn);
            d.item("管理 / 编辑连接", this::manageConn);
        }
        d.item("本地下载", this::browseLocal);
        d.cancel().show();
    }

    private void showMoreMenu() {
        com.magneo.compass.RoundDialog d = new com.magneo.compass.RoundDialog(this).title("更多");
        d.item("连接", this::showConnMenu);
        d.item("本地下载", this::browseLocal);
        d.item("刷新", this::refresh);
        d.item("返回上一级", this::goUp);
        d.cancel().show();
    }

    private void manageConn() {
        if (conns.isEmpty()) {
            renderEmptyState();
            return;
        }
        com.magneo.compass.RoundDialog rd = new com.magneo.compass.RoundDialog(this).title("管理连接");
        for (int i = 0; i < conns.size(); i++) {
            final int idx = i;
            rd.item(conns.get(i).name, () -> {
                com.magneo.compass.RoundDialog ops = new com.magneo.compass.RoundDialog(this).title(conns.get(idx).name);
                ops.item("编辑", () -> editConn(conns.get(idx)));
                ops.item("删除", () -> {
                    FsManager.remove(this, conns.get(idx).id);
                    conns = FsManager.list(this);
                    closeActiveFs();
                    cur = conns.isEmpty() ? null : conns.get(0);
                    path = "";
                    if (cur != null) refresh();
                    else renderEmptyState();
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

    private void toggleIgnoreSsl() {
        boolean on = Prefs.getB(this, Prefs.K_IGNORE_SSL, false);
        if (on) {
            Prefs.putB(this, Prefs.K_IGNORE_SSL, false);
            Toast.makeText(this, "已恢复证书校验", Toast.LENGTH_SHORT).show();
            reloadConnection();
            return;
        }
        new com.magneo.compass.RoundDialog(this)
                .title("兼容 CA")
                .text("开启后会忽略远程网盘的证书错误，适合自签名证书或老旧服务。")
                .item("开启忽略证书", () -> {
                    Prefs.putB(this, Prefs.K_IGNORE_SSL, true);
                    Toast.makeText(this, "已开启，下次加载生效", Toast.LENGTH_SHORT).show();
                    reloadConnection();
                })
                .cancel()
                .show();
    }

    private void openLocal(File f) {
        String url = "file://" + f.getAbsolutePath();
        String n = f.getName().toLowerCase();
        Intent i = null;
        if (isImageName(n))
            i = new Intent(this, ImageViewerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", f.getName())
                    .putExtra("name", f.getName())
                    .putExtra("size", f.length());
        else if (isAudioName(n))
            i = new Intent(this, MusicPlayerActivity.class).putStringArrayListExtra("urls",
                    new ArrayList<>(Collections.singletonList(url)));
        else if (isVideoName(n))
            i = new Intent(this, VideoPlayerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", f.getName())
                    .putExtra("name", f.getName())
                    .putExtra("size", f.length());
        else if (isTextName(n))
            i = new Intent(this, TextViewerActivity.class)
                    .putExtra("url", url)
                    .putExtra("title", f.getName())
                    .putExtra("name", f.getName())
                    .putExtra("size", f.length());
        else Toast.makeText(this, "无法预览，可用浏览器/上传使用", Toast.LENGTH_SHORT).show();
        if (i != null) startActivity(i);
    }

    private String guessMime(String n) {
        if (n == null) return "video/*";
        n = n.toLowerCase();
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        return "video/*";
    }

    /** 单行安全文本：长文件名中间省略，避免在圆屏底部横穿出安全区。 */
    private static class MarqueeText extends TextView {
        MarqueeText(android.content.Context c) {
            super(c);
            setSingleLine(true);
            setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        }
    }
}
