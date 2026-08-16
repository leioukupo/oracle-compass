package com.magneo.compass;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.magneo.compass.ui.Ui;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 优先应用管理：点选前 8 位优先显示的应用，长按调整顺序。 */
public class PriorityAppsActivity extends BaseActivity {

    private static class App {
        String label;
        String pkg;
        android.graphics.drawable.Drawable icon;
    }

    private final List<App> apps = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinned();
        loadApps();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG);
        root.addView(new CompassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        com.magneo.compass.ui.OutlineUtil.oval(lv);
        lv.post(() -> {
            int h = lv.getHeight();
            int side = (int) (lv.getWidth() * 0.12f);
            lv.setPadding(side, (int) (h * 0.16f), side, (int) (h * 0.14f));
        });
        adapter = new Adapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> togglePinned(apps.get(pos)));
        lv.setOnItemLongClickListener((parent, view, pos, id) -> {
            showActions(apps.get(pos));
            return true;
        });
        root.addView(lv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        BackButton back = new BackButton(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = Ui.dp(this, 18);
        root.addView(back, blp);

        setContentView(root);
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo == null) continue;
            App a = new App();
            a.pkg = ri.activityInfo.packageName;
            if (!seen.add(a.pkg)) continue;
            a.label = ri.loadLabel(pm).toString();
            a.icon = ri.loadIcon(pm);
            apps.add(a);
        }
        sortApps();
    }

    private void sortApps() {
        apps.sort((x, y) -> {
            int px = pinned.indexOf(x.pkg);
            int py = pinned.indexOf(y.pkg);
            boolean sx = px >= 0;
            boolean sy = py >= 0;
            if (sx && sy) return px - py;
            if (sx != sy) return sx ? -1 : 1;
            return x.label.compareToIgnoreCase(y.label);
        });
    }

    private void togglePinned(App a) {
        int idx = pinned.indexOf(a.pkg);
        if (idx >= 0) {
            pinned.remove(idx);
            saveAndRefresh(a.label + " 已取消优先");
            return;
        }
        if (pinned.size() >= 8) {
            Toast.makeText(this, "优先位已满，请先取消一个", Toast.LENGTH_SHORT).show();
            return;
        }
        pinned.add(a.pkg);
        saveAndRefresh(a.label + " 已加入优先");
    }

    private void showActions(App a) {
        int idx = pinned.indexOf(a.pkg);
        RoundDialog d = new RoundDialog(this).title(a.label);
        if (idx >= 0) {
            d.text("优先位 " + (idx + 1));
            if (idx > 0) d.item("上移", () -> movePinned(idx, -1));
            if (idx < pinned.size() - 1) d.item("下移", () -> movePinned(idx, 1));
            d.item("取消固定", () -> {
                pinned.remove(a.pkg);
                saveAndRefresh(a.label + " 已取消优先");
            });
        } else {
            d.text(pinned.size() >= 8 ? "优先位已满" : "未固定");
            d.item("设为优先", () -> togglePinned(a));
        }
        d.item("应用详情", () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + a.pkg)));
            } catch (Exception ignored) {}
        });
        d.cancel().show();
    }

    private void movePinned(int idx, int delta) {
        int ni = idx + delta;
        if (ni < 0 || ni >= pinned.size()) return;
        String p = pinned.remove(idx);
        pinned.add(ni, p);
        saveAndRefresh("顺序已更新");
    }

    private void saveAndRefresh(String msg) {
        savePinned();
        sortApps();
        if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View convert, ViewGroup parent) {
            App a = apps.get(p);
            int idx = pinned.indexOf(a.pkg);
            boolean sel = idx >= 0;

            LinearLayout row = new LinearLayout(PriorityAppsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rowBg(sel));
            int pad = Ui.dp(PriorityAppsActivity.this, 12);
            row.setPadding(pad, Ui.dp(PriorityAppsActivity.this, 8), pad, Ui.dp(PriorityAppsActivity.this, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(PriorityAppsActivity.this, 4), 0, Ui.dp(PriorityAppsActivity.this, 4));
            row.setLayoutParams(lp);

            TextView badge = new TextView(PriorityAppsActivity.this);
            badge.setText(sel ? String.valueOf(idx + 1) : "-");
            badge.setTextColor(sel ? Ui.COLOR_BG_DEEP : Ui.COLOR_TEXT_DIM);
            badge.setTextSize(12);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ovalBg(sel));
            row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(PriorityAppsActivity.this, 28),
                    Ui.dp(PriorityAppsActivity.this, 28)));

            ImageView iv = new ImageView(PriorityAppsActivity.this);
            iv.setImageDrawable(a.icon);
            RoundMask.circle(iv, sel ? R.drawable.bg_oval_gold : R.drawable.bg_oval_dark);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(PriorityAppsActivity.this, 38),
                    Ui.dp(PriorityAppsActivity.this, 38));
            ilp.setMargins(Ui.dp(PriorityAppsActivity.this, 8), 0, 0, 0);
            row.addView(iv, ilp);

            LinearLayout texts = new LinearLayout(PriorityAppsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            texts.setPadding(pad, 0, 0, 0);
            TextView title = new TextView(PriorityAppsActivity.this);
            title.setText(a.label);
            title.setTextColor(sel ? Ui.COLOR_GOLD : Ui.COLOR_TEXT);
            title.setTextSize(15);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView sub = new TextView(PriorityAppsActivity.this);
            sub.setText(sel ? "优先位 " + (idx + 1) : "未固定");
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(11);
            sub.setSingleLine(true);
            texts.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
    }

    private GradientDrawable rowBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.dp(this, 18));
        g.setColor(selected ? Color.argb(190, 58, 43, 22) : Color.argb(178, 30, 24, 16));
        g.setStroke(Ui.dp(this, 1), selected ? Ui.COLOR_GOLD : Color.argb(120, 120, 98, 50));
        return g;
    }

    private GradientDrawable ovalBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(selected ? Ui.COLOR_GOLD : Ui.COLOR_PANEL_ALT);
        g.setStroke(Ui.dp(this, 1), selected ? Ui.COLOR_GOLD : Ui.COLOR_GOLD_DIM);
        return g;
    }

    private void loadPinned() {
        pinned.clear();
        try {
            JSONArray a = new JSONArray(Prefs.get(this, Prefs.K_PINNED_APPS, "[]"));
            for (int i = 0; i < a.length() && pinned.size() < 8; i++) {
                String pkg = a.getString(i);
                if (!pinned.contains(pkg)) pinned.add(pkg);
            }
        } catch (Exception ignored) {}
    }

    private void savePinned() {
        JSONArray a = new JSONArray();
        for (String p : pinned) a.put(p);
        Prefs.put(this, Prefs.K_PINNED_APPS, a.toString());
    }
}
