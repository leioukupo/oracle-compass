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
import android.widget.AbsListView;
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

/** 优先应用管理：上方 1-8 槽位管理，下方轻量应用列表。 */
public class PriorityAppsActivity extends BaseActivity {

    private static class App {
        String label;
        String pkg;
        android.graphics.drawable.Drawable icon;
    }

    private final List<App> apps = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();
    private Adapter adapter;
    private LinearLayout slotsBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinned();
        loadApps();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.COLOR_BG);
        root.addView(new CompassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int minSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int safeWidth = (int) (minSide * 0.70f);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(shell, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("优先应用 1-8");
        title.setTextColor(Ui.COLOR_GOLD);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams((int) (minSide * 0.48f),
                Ui.dp(this, 32));
        titleLp.topMargin = Ui.dp(this, 24);
        shell.addView(title, titleLp);

        slotsBox = new LinearLayout(this);
        slotsBox.setOrientation(LinearLayout.VERTICAL);
        slotsBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotsLp = new LinearLayout.LayoutParams(safeWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slotsLp.topMargin = Ui.dp(this, 4);
        shell.addView(slotsBox, slotsLp);

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        com.magneo.compass.ui.OutlineUtil.oval(lv);
        lv.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 28));
        adapter = new Adapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> onAppTap(apps.get(pos)));
        lv.setOnItemLongClickListener((parent, view, pos, id) -> {
            showAppActions(apps.get(pos));
            return true;
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(safeWidth, 0, 1f);
        listLp.topMargin = Ui.dp(this, 8);
        listLp.bottomMargin = Ui.dp(this, 42);
        shell.addView(lv, listLp);

        setContentView(root);
        renderSlots();
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
            if (sx != sy) return sx ? 1 : -1;
            if (sx) return px - py;
            return x.label.compareToIgnoreCase(y.label);
        });
    }

    private void renderSlots() {
        if (slotsBox == null) return;
        slotsBox.removeAllViews();
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60));
            rlp.topMargin = row == 0 ? 0 : Ui.dp(this, 5);
            slotsBox.addView(r, rlp);
            for (int col = 0; col < 4; col++) {
                int slot = row * 4 + col;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.leftMargin = Ui.dp(this, 3);
                lp.rightMargin = Ui.dp(this, 3);
                r.addView(slotView(slot), lp);
            }
        }
    }

    private View slotView(final int slot) {
        App app = slot < pinned.size() ? findApp(pinned.get(slot)) : null;
        FrameLayout box = new FrameLayout(this);
        box.setBackground(slotBg(app != null));
        box.setClickable(true);
        box.setOnClickListener(v -> {
            if (slot < pinned.size()) showSlotActions(slot);
            else showReplaceDialog(pinned.size());
        });
        box.setOnLongClickListener(v -> {
            if (slot < pinned.size()) showSlotSortActions(slot);
            else showReplaceDialog(pinned.size());
            return true;
        });

        TextView badge = new TextView(this);
        badge.setText(String.valueOf(slot + 1));
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(app != null ? Ui.COLOR_BG_DEEP : Ui.COLOR_TEXT_MUTED);
        badge.setTextSize(10);
        badge.setBackground(ovalBg(app != null));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(Ui.dp(this, 18),
                Ui.dp(this, 18), Gravity.TOP | Gravity.START);
        badgeLp.leftMargin = Ui.dp(this, 5);
        badgeLp.topMargin = Ui.dp(this, 5);
        box.addView(badge, badgeLp);

        if (app != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(app.icon);
            RoundMask.circle(iv, R.drawable.bg_oval_dark);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 30),
                    Ui.dp(this, 30), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            ilp.topMargin = Ui.dp(this, 8);
            box.addView(iv, ilp);

            TextView name = new TextView(this);
            name.setText(shortName(app.label, 6));
            name.setTextColor(Ui.COLOR_TEXT);
            name.setTextSize(10);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(this, 18), Gravity.BOTTOM);
            nlp.leftMargin = Ui.dp(this, 4);
            nlp.rightMargin = Ui.dp(this, 4);
            nlp.bottomMargin = Ui.dp(this, 2);
            box.addView(name, nlp);
        } else {
            TextView empty = new TextView(this);
            empty.setText("空位");
            empty.setTextColor(Ui.COLOR_TEXT_MUTED);
            empty.setTextSize(11);
            empty.setGravity(Gravity.CENTER);
            box.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return box;
    }

    private void onAppTap(App a) {
        int idx = pinned.indexOf(a.pkg);
        if (idx >= 0) {
            pinned.remove(idx);
            saveAndRefresh(a.label + " 已取消优先");
            return;
        }
        if (pinned.size() < 8) {
            pinned.add(a.pkg);
            saveAndRefresh(a.label + " 已加入优先");
        } else {
            showReplaceTargetDialog(a);
        }
    }

    private void showAppActions(App a) {
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
        } else if (pinned.size() < 8) {
            d.text("未固定");
            d.item("加入优先位", () -> onAppTap(a));
        } else {
            d.text("优先位已满");
            d.item("替换一个优先位", () -> showReplaceTargetDialog(a));
        }
        d.item("应用详情", () -> openAppDetails(a));
        d.cancel().show();
    }

    private void showSlotActions(final int slot) {
        App a = slot < pinned.size() ? findApp(pinned.get(slot)) : null;
        if (a == null) {
            showReplaceDialog(pinned.size());
            return;
        }
        RoundDialog d = new RoundDialog(this).title("优先位 " + (slot + 1));
        d.text(a.label);
        if (slot > 0) d.item("上移", () -> movePinned(slot, -1));
        if (slot < pinned.size() - 1) d.item("下移", () -> movePinned(slot, 1));
        d.item("替换应用", () -> showReplaceDialog(slot));
        d.item("移除", () -> {
            pinned.remove(slot);
            saveAndRefresh(a.label + " 已移除");
        });
        d.item("应用详情", () -> openAppDetails(a));
        d.cancel().show();
    }

    private void showSlotSortActions(final int slot) {
        App a = slot < pinned.size() ? findApp(pinned.get(slot)) : null;
        if (a == null) {
            showReplaceDialog(pinned.size());
            return;
        }
        RoundDialog d = new RoundDialog(this).title("调整优先位 " + (slot + 1));
        d.text(a.label);
        if (slot > 0) d.item("上移", () -> movePinned(slot, -1));
        if (slot < pinned.size() - 1) d.item("下移", () -> movePinned(slot, 1));
        d.item("移除", () -> {
            pinned.remove(slot);
            saveAndRefresh(a.label + " 已移除");
        });
        d.cancel().show();
    }

    private void showReplaceDialog(final int slot) {
        int target = Math.max(0, Math.min(slot, pinned.size()));
        RoundDialog d = new RoundDialog(this).title(target < pinned.size()
                ? "替换优先位 " + (target + 1) : "选择应用");
        for (App app : apps) {
            final App a = app;
            d.item(app.label, () -> setPinnedAt(target, a));
        }
        d.cancel().show();
    }

    private void showReplaceTargetDialog(final App app) {
        RoundDialog d = new RoundDialog(this).title("替换优先位");
        d.text(app.label);
        for (int i = 0; i < Math.min(8, pinned.size()); i++) {
            final int slot = i;
            App old = findApp(pinned.get(i));
            String label = (i + 1) + " · " + (old == null ? "空位" : old.label);
            d.item(label, () -> setPinnedAt(slot, app));
        }
        d.cancel().show();
    }

    private void setPinnedAt(int slot, App app) {
        if (app == null || slot < 0 || slot >= 8) return;
        List<String> next = new ArrayList<>();
        for (String p : pinned) {
            if (!p.equals(app.pkg)) next.add(p);
        }
        if (slot < next.size()) next.set(slot, app.pkg);
        else next.add(app.pkg);
        pinned.clear();
        for (int i = 0; i < next.size() && i < 8; i++) pinned.add(next.get(i));
        saveAndRefresh(app.label + " 已设置");
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
        renderSlots();
        if (adapter != null) adapter.notifyDataSetChanged();
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void openAppDetails(App a) {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + a.pkg)));
        } catch (Exception ignored) {}
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
            row.setAlpha(sel ? 0.72f : 1f);
            int pad = Ui.dp(PriorityAppsActivity.this, 8);
            row.setPadding(pad, Ui.dp(PriorityAppsActivity.this, 5), pad, Ui.dp(PriorityAppsActivity.this, 5));
            row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(PriorityAppsActivity.this, 46)));

            TextView badge = new TextView(PriorityAppsActivity.this);
            badge.setText(sel ? String.valueOf(idx + 1) : "");
            badge.setTextColor(sel ? Ui.COLOR_BG_DEEP : Ui.COLOR_TEXT_DIM);
            badge.setTextSize(11);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ovalBg(sel));
            row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(PriorityAppsActivity.this, 24),
                    Ui.dp(PriorityAppsActivity.this, 24)));

            ImageView iv = new ImageView(PriorityAppsActivity.this);
            iv.setImageDrawable(a.icon);
            RoundMask.circle(iv, sel ? R.drawable.bg_oval_gold : R.drawable.bg_oval_dark);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(PriorityAppsActivity.this, 32),
                    Ui.dp(PriorityAppsActivity.this, 32));
            ilp.setMargins(Ui.dp(PriorityAppsActivity.this, 8), 0, 0, 0);
            row.addView(iv, ilp);

            LinearLayout texts = new LinearLayout(PriorityAppsActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            texts.setPadding(Ui.dp(PriorityAppsActivity.this, 10), 0, 0, 0);
            TextView title = new TextView(PriorityAppsActivity.this);
            title.setText(a.label);
            title.setTextColor(sel ? Ui.COLOR_GOLD_DARK : Ui.COLOR_TEXT);
            title.setTextSize(14);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView sub = new TextView(PriorityAppsActivity.this);
            sub.setText(sel ? "优先位 " + (idx + 1) : "未固定");
            sub.setTextColor(Ui.COLOR_TEXT_DIM);
            sub.setTextSize(10);
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
        g.setCornerRadius(Ui.dp(this, 8));
        g.setColor(selected ? Color.argb(120, 42, 31, 16) : Color.argb(165, 30, 24, 16));
        g.setStroke(Ui.dp(this, 1), selected ? Color.argb(120, 212, 175, 55)
                : Color.argb(95, 120, 98, 50));
        return g;
    }

    private GradientDrawable slotBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.dp(this, 8));
        g.setColor(selected ? Color.argb(190, 38, 31, 18) : Color.argb(105, 20, 16, 12));
        g.setStroke(Ui.dp(this, 1), selected ? Ui.COLOR_GOLD : Color.argb(110, 120, 98, 50));
        return g;
    }

    private GradientDrawable ovalBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(selected ? Ui.COLOR_GOLD : Color.argb(120, 38, 31, 20));
        g.setStroke(Ui.dp(this, 1), selected ? Ui.COLOR_GOLD : Ui.COLOR_GOLD_DIM);
        return g;
    }

    private App findApp(String pkg) {
        for (App a : apps) {
            if (a.pkg.equals(pkg)) return a;
        }
        return null;
    }

    private String shortName(String label, int maxUnits) {
        String s = label == null ? "" : label.trim();
        int cut = s.indexOf('(');
        if (cut < 0) cut = s.indexOf('（');
        if (cut > 1) s = s.substring(0, cut).trim();
        int units = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int add = ch <= 127 ? 1 : 2;
            if (units + add > maxUnits) {
                out.append('…');
                return out.toString();
            }
            out.append(ch);
            units += add;
        }
        return out.toString();
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
