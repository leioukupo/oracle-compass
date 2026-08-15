package com.magneo.compass;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
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

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** 优先应用管理：点选前 8 位优先显示的应用（顺序即显示顺序）。 */
public class PriorityAppsActivity extends BaseActivity {

    private static class App {
        String label;
        String pkg;
        android.graphics.drawable.Drawable icon;
    }

    private final List<App> apps = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinned();

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
        apps.sort((x, y) -> {
            boolean px = pinned.contains(x.pkg), py = pinned.contains(y.pkg);
            if (px != py) return px ? -1 : 1;
            return x.label.compareToIgnoreCase(y.label);
        });

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.rgb(10, 10, 10));
        lv.setDivider(null);
        com.magneo.compass.ui.OutlineUtil.oval(lv);
        lv.post(() -> {
            int h = lv.getHeight();
            int side = (int) (lv.getWidth() * 0.12f);
            lv.setPadding(side, (int) (h * 0.15f), side, (int) (h * 0.15f));
        });
        lv.setAdapter(new Adapter());
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            App a = apps.get(pos);
            boolean p = pinned.contains(a.pkg);
            if (p) pinned.remove(a.pkg);
            else pinned.add(a.pkg);
            savePinned();
            Toast.makeText(this, a.label + (p ? " 已取消优先" : " 已设为优先"), Toast.LENGTH_SHORT).show();
            recreate();
        });
        FrameLayout bgRoot = new FrameLayout(this);
        bgRoot.addView(new CompassBackground(this));
        bgRoot.addView(lv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(bgRoot);
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View convert, ViewGroup parent) {
            App a = apps.get(p);
            LinearLayout row = new LinearLayout(PriorityAppsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_pill_dark);
            int pad = com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 14);
            row.setPadding(pad, com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 10), pad, com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 4), 0, com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 4));
            row.setLayoutParams(lp);

            ImageView iv = new ImageView(PriorityAppsActivity.this);
            iv.setImageDrawable(a.icon);
            RoundMask.circle(iv, R.drawable.bg_oval_dark);
            row.addView(iv, new LinearLayout.LayoutParams(com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 40), com.magneo.compass.ui.Ui.dp(PriorityAppsActivity.this, 40)));

            TextView tv = new TextView(PriorityAppsActivity.this);
            tv.setText(a.label + (pinned.contains(a.pkg) ? " ★" : ""));
            tv.setTextColor(Color.rgb(232, 220, 192));
            tv.setTextSize(16);
            tv.setSingleLine(true);
            tv.setPadding(pad, 0, 0, 0);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
    }

    private void loadPinned() {
        pinned.clear();
        try {
            JSONArray a = new JSONArray(Prefs.get(this, Prefs.K_PINNED_APPS, "[]"));
            for (int i = 0; i < a.length(); i++) pinned.add(a.getString(i));
        } catch (Exception ignored) {}
    }

    private void savePinned() {
        JSONArray a = new JSONArray();
        for (String p : pinned) a.put(p);
        Prefs.put(this, Prefs.K_PINNED_APPS, a.toString());
    }
}
