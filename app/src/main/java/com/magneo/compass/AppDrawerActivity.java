package com.magneo.compass;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用抽屉（轮盘式圆环）：8 个槽位，手指按圆环滑动时圆环跟手转动，
 * 应用循环替换、中心显示当前应用；松手自动对齐。优先应用排在最前。
 */
public class AppDrawerActivity extends BaseActivity {

    private static final int SLOTS = 8;
    private static final float SLOT_DEG = 45f;

    private static class App {
        String label;
        String pkg;
        Drawable icon;
        Intent launch;
    }

    private final List<App> all = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();
    private RingPanel ring;
    private ImageView centerIcon;
    private TextView centerLabel;

    private float baseRot = 0;   // 已固定的轮盘角度（对齐到 45° 倍数）
    private float dragRot = 0;   // 当前手势累计转动角（度）
    private int lastK = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinned();

        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        List<App> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            App a = new App();
            a.pkg = ri.activityInfo.packageName;
            if (!seen.add(a.pkg)) continue;
            a.label = ri.loadLabel(pm).toString();
            a.icon = ri.loadIcon(pm);
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(a.pkg, ri.activityInfo.name);
            a.launch = i;
            if (pinned.contains(a.pkg)) all.add(a); else rest.add(a);
        }
        Collections.sort(rest, Comparator.comparing(a -> a.label));
        all.addAll(rest);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        root.addView(new CompassBackground(this), 0);

        ring = new RingPanel(this);
        ring.setAngleCallback(new AngleCallback() {
            @Override public void onAngle(float a) { onDragAngle(a); }
            @Override public void onEnd(float a) { onDragEnd(a); }
        });
        root.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);
        centerIcon = new ImageView(this);
        RoundMask.circle(centerIcon, R.drawable.bg_oval_gold);
        int is = com.magneo.compass.ui.Ui.dp(this, 84);
        centerIcon.setLayoutParams(new LinearLayout.LayoutParams(is, is));
        centerLabel = new TextView(this);
        centerLabel.setTextColor(Color.rgb(212, 175, 55));
        centerLabel.setTextSize(15);
        centerLabel.setGravity(Gravity.CENTER);
        centerBox.addView(centerIcon);
        centerBox.addView(centerLabel);
        root.addView(centerBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        ring.post(this::renderRing);
    }

    private void onDragAngle(float acc) {
        dragRot = acc;
        renderRing();
    }

    private void onDragEnd(float acc) {
        dragRot = acc;
        int k = Math.round((baseRot + dragRot) / SLOT_DEG);
        baseRot = k * SLOT_DEG;
        dragRot = 0;
        renderRing();
    }

    private void renderRing() {
        ring.removeAllViews();
        int n = all.size();
        if (n == 0) {
            centerLabel.setText("没有可启动的应用");
            return;
        }
        float total = baseRot + dragRot;
        int k = (int) Math.floor(total / SLOT_DEG);
        if (k != lastK) {
            lastK = k;
            ring.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        int offset = ((k % n) + n) % n;
        float visRot = total - k * SLOT_DEG; // 0..45 的视觉残角，让圆环跟手平滑转动

        int w = ring.getWidth(), h = ring.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = com.magneo.compass.ui.RoundScreen.R(w, h);
        float r = Math.min(w, h) * 0.36f;
        // 按 RoundScreen.maxCellHalf 自适应每格 cell 尺寸：保证四角不出 R。
        // 顶/底位（i=0,4）cell 中心在竖轴上，可用半边大；对角位（i=1,3,5,7）最紧。
        // 设上下限：太小不易点，太大压中圆心；上限 dp80（icon 72 上限 + 边距），下限 dp54（仍 ≥ 触控目标）。
        int cMax = com.magneo.compass.ui.Ui.dp(this, 74);
        int cMin = com.magneo.compass.ui.Ui.dp(this, 60);
        for (int i = 0; i < SLOTS; i++) {
            App a = all.get((offset + i) % n);
            View cell = appCell(a);
            float angDeg = -90 + i * (360f / SLOTS) + visRot;
            float angle = (float) Math.toRadians(angDeg);
            float halfR = com.magneo.compass.ui.RoundScreen.maxCellHalf((int) r, angDeg, R);
            int cs = (int) Math.min(cMax, Math.max(cMin, 2 * halfR));
            int cellW = cs, cellH = cs;
            int x = (int) (cx + r * Math.cos(angle) - cellW / 2f);
            int y = (int) (cy + r * Math.sin(angle) - cellH / 2f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cellW, cellH);
            lp.leftMargin = x;
            lp.topMargin = y;
            ring.addView(cell, lp);
        }
        App cur = all.get(offset % n);
        centerIcon.setImageDrawable(cur.icon);
        RoundMask.circle(centerIcon, iconBg(cur.pkg));
        centerLabel.setText(cur.label);
    }

    private View appCell(final App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        RoundMask.circle(iv, iconBg(a.pkg));
        int s = com.magneo.compass.ui.Ui.dp(this, 48);  // 圆屏精修：环格 cell 收为正方形后，icon 留出 label 空间
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));

        TextView tv = new TextView(this);
        tv.setText(a.label + (pinned.contains(a.pkg) ? " ★" : ""));
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        cell.addView(iv);
        cell.addView(tv);

        cell.setOnClickListener(v -> {
            try { startActivity(a.launch); } catch (Exception e) {
                Toast.makeText(this, "无法启动", Toast.LENGTH_SHORT).show();
            }
        });
        cell.setOnLongClickListener(v -> {
            final boolean p = pinned.contains(a.pkg);
            new RoundDialog(this)
                    .title(a.label)
                    .item(p ? "取消优先" : "设为优先（前 8 位）", () -> {
                        if (p) pinned.remove(a.pkg); else pinned.add(a.pkg);
                        savePinned();
                        recreate();
                    })
                    .item("应用详情", () -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + a.pkg)));
                        } catch (Exception ignored) {}
                    })
                    .item("取消", null)
                    .show();
            return true;
        });
        return cell;
    }

    /** 个别深色/透明图标的图标用浅色圆盘底，否则金色深盘。 */
    private int iconBg(String pkg) {
        if ("cn.ljason.adbwireless".equals(pkg) || "com.android.gallery3d".equals(pkg)) {
            return R.drawable.bg_oval_light;
        }
        return R.drawable.bg_oval_gold;
    }

    // ---------- 优先应用（有序）存取 ----------

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

    private interface AngleCallback {
        void onAngle(float accumulatedDeg);
        void onEnd(float accumulatedDeg);
    }

    /** 圆环容器：只响应绕圆心的转动（像罗盘页），点按仍交给图标。 */
    private class RingPanel extends FrameLayout {
        private AngleCallback cb;
        private float cx, cy;
        private float lastAng, accum;
        private boolean intercepting;

        RingPanel(Context c) { super(c); }

        void setAngleCallback(AngleCallback cb) { this.cb = cb; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cx = w / 2f;
            cy = h / 2f;
        }

        private float ang(MotionEvent e) {
            return (float) Math.toDegrees(Math.atan2(e.getY() - cy, e.getX() - cx));
        }

        private float norm(float a) {
            while (a > 180) a -= 360;
            while (a < -180) a += 360;
            return a;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastAng = ang(ev);
                    accum = 0;
                    intercepting = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float a = ang(ev);
                    float da = norm(a - lastAng);
                    lastAng = a;
                    accum += da;
                    if (!intercepting && Math.abs(accum) > 8f) {
                        intercepting = true;
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float a = ang(ev);
                    accum += norm(a - lastAng);
                    lastAng = a;
                    if (cb != null) cb.onAngle(accum);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (cb != null) cb.onEnd(accum);
                    intercepting = false;
                    accum = 0;
                    return true;
            }
            return true;
        }
    }
}
