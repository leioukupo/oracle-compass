package com.magneo.compass;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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

import com.magneo.compass.ui.Ui;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用抽屉（轮盘式圆环）：8 个槽位，手指按圆环滑动时圆环跟手转动，
 * 应用循环替换、中心显示当前应用；松手自动对齐。优先应用排在最前。
 */
public class AppDrawerActivity extends BaseActivity {

    private static final String TAG = "AppDrawer";

    private static final int SLOTS = 8;
    private static final float SLOT_DEG = 45f;
    private static final float APP_STEP_DEG = 90f;
    private static final int INCOMING_SLOT = 4;
    private static final int[] INITIAL_SLOT_REL = {0, 1, 2, 3, 7, 6, 5, 4};

    private static class App {
        String label;
        String pkg;
        Drawable icon;
        Intent launch;
    }

    private static class SlotState {
        final int[] idx = new int[SLOTS];
        int nextForward;
        int nextBackward;
        final List<StepRecord> history = new ArrayList<>();
    }

    private static class StepRecord {
        final int direction;
        final int removed;
        final int added;

        StepRecord(int direction, int removed, int added) {
            this.direction = direction;
            this.removed = removed;
            this.added = added;
        }
    }

    private final List<App> all = new ArrayList<>();
    private final List<String> pinned = new ArrayList<>();
    private RingPanel ring;
    private ImageView centerIcon;
    private TextView centerLabel;
    private App currentApp;
    private String launcherStateFingerprint = "";
    private long lastLaunchAt;

    private final int[] slotIdx = new int[SLOTS];
    private int nextForward = 0;
    private int nextBackward = 0;
    private int lastPreviewStep = 0;
    private final List<StepRecord> stepHistory = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadPinned();
        refreshLauncherApps(true);

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
        centerBox.setClickable(true);
        centerBox.setOnClickListener(v -> launchApp(currentApp));
        centerBox.setOnLongClickListener(v -> {
            if (currentApp != null) showAppActions(currentApp);
            return true;
        });
        centerIcon = new ImageView(this);
        RoundMask.circle(centerIcon, R.drawable.bg_oval_gold);
        int is = Ui.dp(this, 84);
        centerIcon.setLayoutParams(new LinearLayout.LayoutParams(is, is));
        centerLabel = new TextView(this);
        centerLabel.setTextColor(Ui.COLOR_GOLD);
        centerLabel.setTextSize(15);
        centerLabel.setGravity(Gravity.CENTER);
        centerLabel.setSingleLine(true);
        centerLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 4);
        centerBox.addView(centerIcon);
        centerBox.addView(centerLabel, clp);
        int minSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        root.addView(centerBox, new FrameLayout.LayoutParams((int) (minSide * 0.34f),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
        ring.post(this::renderRing);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The drawer can remain alive while another app is installed. Re-query when
        // it becomes visible again instead of relying on the old onCreate snapshot.
        refreshLauncherApps(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refreshLauncherApps(false);
    }

    /**
     * Rebuilds the stable launcher list only when packages/components or pinned order changed.
     * A label/icon update alone does not reset the carousel state.
     */
    private void refreshLauncherApps(boolean force) {
        loadPinned();
        List<App> found = scanLauncherApps();
        String fingerprint = launcherStateFingerprint(found);
        if (!force && fingerprint.equals(launcherStateFingerprint)) return;

        all.clear();
        appendPinnedApps(found);
        appendOtherApps(found);
        launcherStateFingerprint = fingerprint;
        initSlots();

        String first = firstPackages(all, 12);
        Log.i(TAG, "launcher scan count=" + all.size() + " pinned=" + pinned.size()
                + " first=" + first);
        DebugLog.append(this, "apps.launcher_scan", "count=" + all.size()
                + " pinned=" + pinned.size() + " first=" + first);

        if (ring != null) ring.post(this::renderRing);
    }

    private List<App> scanLauncherApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<>(pm.queryIntentActivities(main, 0));
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo left, ResolveInfo right) {
                String lp = left.activityInfo == null ? "" : left.activityInfo.packageName;
                String rp = right.activityInfo == null ? "" : right.activityInfo.packageName;
                int pkg = lp.compareTo(rp);
                if (pkg != 0) return pkg;
                String ln = left.activityInfo == null ? "" : left.activityInfo.name;
                String rn = right.activityInfo == null ? "" : right.activityInfo.name;
                return ln.compareTo(rn);
            }
        });

        List<App> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : list) {
            if (ri == null || ri.activityInfo == null) continue;
            if (!ri.activityInfo.enabled || ri.activityInfo.applicationInfo == null
                    || !ri.activityInfo.applicationInfo.enabled) continue;
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;

            App a = new App();
            a.pkg = pkg;
            a.label = safeLabel(ri, pm, pkg);
            a.icon = ri.loadIcon(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(pkg, ri.activityInfo.name);
            a.launch = launch;
            found.add(a);
        }
        return found;
    }

    private String safeLabel(ResolveInfo ri, PackageManager pm, String fallback) {
        CharSequence label = ri.loadLabel(pm);
        return label == null || label.length() == 0 ? fallback : label.toString();
    }

    private void appendPinnedApps(List<App> found) {
        for (String pkg : pinned) {
            for (App a : found) {
                if (a.pkg.equals(pkg)) {
                    all.add(a);
                    break;
                }
            }
        }
    }

    private void appendOtherApps(List<App> found) {
        List<App> rest = new ArrayList<>();
        for (App a : found) {
            if (!pinned.contains(a.pkg)) rest.add(a);
        }
        Collections.sort(rest, new Comparator<App>() {
            @Override public int compare(App left, App right) {
                int label = left.label.toLowerCase(Locale.US)
                        .compareTo(right.label.toLowerCase(Locale.US));
                return label != 0 ? label : left.pkg.compareTo(right.pkg);
            }
        });
        all.addAll(rest);
    }

    private String launcherStateFingerprint(List<App> found) {
        List<String> components = new ArrayList<>();
        for (App a : found) {
            String component = a.pkg + "/" + (a.launch == null ? "" : a.launch.getComponent().getClassName());
            components.add(component);
        }
        Collections.sort(components);
        StringBuilder out = new StringBuilder();
        for (String component : components) out.append(component).append(';');
        out.append("|pinned:");
        for (String pkg : pinned) out.append(pkg).append(';');
        return out.toString();
    }

    private String firstPackages(List<App> apps, int limit) {
        StringBuilder out = new StringBuilder();
        int count = Math.min(limit, apps.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append(apps.get(i).pkg);
        }
        return out.toString();
    }

    private void onDragAngle(float acc) {
        int step = dragStep(acc);
        if (step != lastPreviewStep) {
            lastPreviewStep = step;
            if (step != 0) ring.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        SlotState preview = previewState(step);
        renderRing(dragVisualRot(acc, step), 0, preview.idx);
    }

    private void onDragEnd(float acc) {
        commitSteps(dragStep(acc));
        lastPreviewStep = 0;
        renderRing();
    }

    private void renderRing() {
        renderRing(0, 0, slotIdx);
    }

    private void renderRing(float visRot, int selectedSlot, int[] slots) {
        ring.removeAllViews();
        int n = all.size();
        if (n == 0) {
            currentApp = null;
            centerIcon.setImageDrawable(null);
            centerLabel.setText("没有可启动的应用");
            return;
        }
        int count = Math.min(SLOTS, n);

        int w = ring.getWidth(), h = ring.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = com.magneo.compass.ui.RoundScreen.R(w, h);
        float r = Math.min(w, h) * 0.36f;
        // 按 RoundScreen.maxCellHalf 自适应每格 cell 尺寸：保证四角不出 R。
        // 顶/底位（i=0,4）cell 中心在竖轴上，可用半边大；对角位（i=1,3,5,7）最紧。
        // 设上下限：太小不易点，太大压中圆心；上限 dp80（icon 72 上限 + 边距），下限 dp54（仍 ≥ 触控目标）。
        for (int i = 0; i < count; i++) {
            float angDeg = -90 + i * (360f / SLOTS) + visRot;
            float angle = (float) Math.toRadians(angDeg);
            float halfR = com.magneo.compass.ui.RoundScreen.maxCellHalf((int) r, angDeg, R);
            int cMax = Ui.dp(this, 74);
            int cMin = Ui.dp(this, 62);
            int cs = (int) Math.min(cMax, Math.max(cMin, 2 * halfR));
            App a = appAt(slots, i, n);
            if (a == null) continue;
            View cell = appCell(a);
            int cellW = cs, cellH = cs;
            int x = (int) (cx + r * Math.cos(angle) - cellW / 2f);
            int y = (int) (cy + r * Math.sin(angle) - cellH / 2f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cellW, cellH);
            lp.leftMargin = x;
            lp.topMargin = y;
            ring.addView(cell, lp);
        }
        ring.invalidate();
        int curSlot = validSlot(selectedSlot, count, slots);
        App cur = appAt(slots, curSlot, n);
        if (cur == null) {
            currentApp = null;
            centerIcon.setImageDrawable(null);
            centerLabel.setText("没有可启动的应用");
            return;
        }
        currentApp = cur;
        centerIcon.setImageDrawable(cur.icon);
        RoundMask.circle(centerIcon, iconBg(cur.pkg));
        centerLabel.setText(cur.label);
    }

    private void initSlots() {
        int n = all.size();
        stepHistory.clear();
        for (int i = 0; i < SLOTS; i++) slotIdx[i] = -1;
        if (n == 0) return;
        int count = Math.min(SLOTS, n);
        for (int i = 0; i < count; i++) {
            int rel = n >= SLOTS ? INITIAL_SLOT_REL[i] : i;
            slotIdx[i] = rel;
        }
        nextForward = wrapIndex(count, n);
        nextBackward = wrapIndex(-1, n);
    }

    private void commitSteps(int step) {
        if (step == 0 || all.isEmpty()) return;
        String before = slotSummary(slotIdx);
        SlotState next = previewState(step);
        System.arraycopy(next.idx, 0, slotIdx, 0, SLOTS);
        nextForward = next.nextForward;
        nextBackward = next.nextBackward;
        stepHistory.clear();
        for (StepRecord record : next.history) {
            stepHistory.add(new StepRecord(record.direction, record.removed, record.added));
        }
        String after = slotSummary(slotIdx);
        if (!before.equals(after)) {
            String message = "step=" + step + " before=" + before + " after=" + after
                    + " nextForward=" + nextForward + " nextBackward=" + nextBackward;
            Log.d(TAG, "carousel " + message);
            DebugLog.append(this, "apps.carousel_step", message);
        }
    }

    private SlotState previewState(int step) {
        SlotState st = new SlotState();
        System.arraycopy(slotIdx, 0, st.idx, 0, SLOTS);
        st.nextForward = nextForward;
        st.nextBackward = nextBackward;
        for (StepRecord record : stepHistory) {
            st.history.add(new StepRecord(record.direction, record.removed, record.added));
        }
        int n = all.size();
        if (n == 0 || step == 0) return st;
        int count = Math.min(SLOTS, n);
        int times = Math.min(Math.abs(step), n);
        for (int i = 0; i < times; i++) {
            if (step > 0) moveClockwise(st, count, n);
            else moveCounterClockwise(st, count, n);
        }
        return st;
    }

    /** Advances the ring clockwise, or undoes the most recent counter-clockwise step. */
    private void moveClockwise(SlotState st, int count, int n) {
        if (n <= SLOTS || count <= INCOMING_SLOT) {
            rotateRight(st.idx, count);
            return;
        }

        StepRecord last = lastRecord(st);
        if (last != null && last.direction < 0) {
            rotateRight(st.idx, count);
            // A reverse step inserted at 6 o'clock; after undoing the rotation it
            // moves to the following slot and is replaced by what it removed.
            st.idx[INCOMING_SLOT + 1] = last.removed;
            st.nextBackward = last.added;
            st.history.remove(st.history.size() - 1);
            return;
        }

        rotateRight(st.idx, count);
        int removed = st.idx[INCOMING_SLOT];
        st.idx[INCOMING_SLOT] = -1;
        int incoming = takeForward(st, n);
        int added = incoming >= 0 ? incoming : removed;
        st.idx[INCOMING_SLOT] = added;
        st.history.add(new StepRecord(1, removed, added));
    }

    /** Reverses the ring counter-clockwise, or undoes the most recent clockwise step. */
    private void moveCounterClockwise(SlotState st, int count, int n) {
        if (n <= SLOTS || count <= INCOMING_SLOT) {
            rotateLeft(st.idx, count);
            return;
        }

        StepRecord last = lastRecord(st);
        if (last != null && last.direction > 0) {
            rotateLeft(st.idx, count);
            // The clockwise newcomer lands one slot before 6 o'clock after
            // undoing the rotation, so restore the removed item there.
            st.idx[INCOMING_SLOT - 1] = last.removed;
            st.nextForward = last.added;
            st.history.remove(st.history.size() - 1);
            return;
        }

        rotateLeft(st.idx, count);
        int removed = st.idx[INCOMING_SLOT];
        st.idx[INCOMING_SLOT] = -1;
        int incoming = takeBackward(st, n);
        int added = incoming >= 0 ? incoming : removed;
        st.idx[INCOMING_SLOT] = added;
        st.history.add(new StepRecord(-1, removed, added));
    }

    private StepRecord lastRecord(SlotState st) {
        return st.history.isEmpty() ? null : st.history.get(st.history.size() - 1);
    }

    private void rotateRight(int[] slots, int count) {
        if (count <= 1) return;
        int last = slots[count - 1];
        for (int i = count - 1; i > 0; i--) slots[i] = slots[i - 1];
        slots[0] = last;
    }

    private void rotateLeft(int[] slots, int count) {
        if (count <= 1) return;
        int first = slots[0];
        for (int i = 0; i < count - 1; i++) slots[i] = slots[i + 1];
        slots[count - 1] = first;
    }

    private int dragStep(float deg) {
        if (deg >= APP_STEP_DEG) return (int) Math.floor(deg / APP_STEP_DEG);
        if (deg <= -APP_STEP_DEG) return (int) Math.ceil(deg / APP_STEP_DEG);
        return 0;
    }

    private float dragVisualRot(float deg, int step) {
        return deg / APP_STEP_DEG * SLOT_DEG - step * SLOT_DEG;
    }

    private int validSlot(int slot, int count, int[] slots) {
        if (slot >= 0 && slot < count && slots[slot] >= 0 && slots[slot] < all.size()) return slot;
        for (int i = 0; i < count; i++) {
            if (slots[i] >= 0 && slots[i] < all.size()) return i;
        }
        return -1;
    }

    private App appAt(int[] slots, int slot, int n) {
        if (slots == null || slot < 0 || slot >= slots.length || n <= 0) return null;
        int idx = slots[slot];
        if (idx < 0 || idx >= n || idx >= all.size()) return null;
        return all.get(idx);
    }

    private String slotSummary(int[] slots) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) out.append(',');
            int idx = slots[i];
            out.append(idx >= 0 && idx < all.size() ? all.get(idx).pkg : "-");
        }
        return out.toString();
    }

    private int takeForward(SlotState st, int n) {
        int idx = wrapIndex(st.nextForward, n);
        for (int guard = 0; guard < n; guard++) {
            if (!slotContains(st.idx, idx)) {
                st.nextForward = wrapIndex(idx + 1, n);
                return idx;
            }
            idx = wrapIndex(idx + 1, n);
        }
        return -1;
    }

    private int takeBackward(SlotState st, int n) {
        int idx = wrapIndex(st.nextBackward, n);
        for (int guard = 0; guard < n; guard++) {
            if (!slotContains(st.idx, idx)) {
                st.nextBackward = wrapIndex(idx - 1, n);
                return idx;
            }
            idx = wrapIndex(idx - 1, n);
        }
        return -1;
    }

    private boolean slotContains(int[] slots, int idx) {
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == idx) return true;
        }
        return false;
    }

    private int wrapIndex(int i, int n) {
        if (n <= 0) return -1;
        return ((i % n) + n) % n;
    }

    private View appCell(final App a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        RoundMask.circle(iv, iconBg(a.pkg));
        int s = Ui.dp(this, 48);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));

        TextView tv = new TextView(this);
        tv.setText(a.label + (pinned.contains(a.pkg) ? " ★" : ""));
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(iv);
        cell.addView(tv);

        cell.setOnClickListener(v -> launchApp(a));
        cell.setOnLongClickListener(v -> {
            showAppActions(a);
            return true;
        });
        return cell;
    }

    private void launchApp(App a) {
        if (a == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunchAt < 750L) {
            Log.d(TAG, "launch throttled pkg=" + a.pkg);
            DebugLog.append(this, "apps.launch_throttled", "pkg=" + a.pkg);
            return;
        }
        lastLaunchAt = now;
        try {
            // Some Android 5.1 system apps enter a buggy ROM path when launched
            // with ACTION_MAIN+CATEGORY_LAUNCHER and later receive a key event.
            // Keep the resolved component, but launch it as a plain explicit intent.
            Intent launch = new Intent();
            if (a.launch == null || a.launch.getComponent() == null) {
                throw new IllegalStateException("missing launcher component");
            }
            launch.setComponent(a.launch.getComponent());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
        } catch (Exception e) {
            Log.w(TAG, "launch failed pkg=" + a.pkg, e);
            DebugLog.append(this, "apps.launch_failed", "pkg=" + a.pkg
                    + " error=" + e.getClass().getSimpleName());
            Toast.makeText(this, "无法启动", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppActions(App a) {
        if (a == null) return;
        final boolean p = pinned.contains(a.pkg);
        new RoundDialog(this)
                .title(a.label)
                .item(p ? "取消优先" : "设为优先（前 8 位）", () -> {
                    if (p) {
                        pinned.remove(a.pkg);
                    } else if (pinned.size() >= 8) {
                        Toast.makeText(this, "优先位已满，请先取消一个", Toast.LENGTH_SHORT).show();
                        return;
                    } else {
                        pinned.add(a.pkg);
                    }
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

    private interface AngleCallback {
        void onAngle(float accumulatedDeg);
        void onEnd(float accumulatedDeg);
    }

    /** 圆环容器：只响应绕圆心的转动（像罗盘页），点按仍交给图标。 */
    private class RingPanel extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private AngleCallback cb;
        private float cx, cy;
        private float lastAng, accum;
        private boolean intercepting;

        RingPanel(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        void setAngleCallback(AngleCallback cb) { this.cb = cb; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            cx = w / 2f;
            cy = h / 2f;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawLauncherGuides(canvas);
        }

        private void drawLauncherGuides(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float min = Math.min(w, h);
            float ccx = w / 2f;
            float ccy = h / 2f;
            float r = min * 0.36f;
            drawSlotGuide(canvas, ccx, ccy, r, -90, true);
        }

        private void drawSlotGuide(Canvas canvas, float ccx, float ccy, float r, float deg, boolean selected) {
            float a = (float) Math.toRadians(deg);
            float x = ccx + r * (float) Math.cos(a);
            float y = ccy + r * (float) Math.sin(a) - Ui.dpF(AppDrawerActivity.this, 8);
            float outer = Ui.dpF(AppDrawerActivity.this, 31);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dpF(AppDrawerActivity.this, 2));
            paint.setColor(Color.argb(175, 212, 175, 55));
            canvas.drawCircle(x, y, outer, paint);
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
