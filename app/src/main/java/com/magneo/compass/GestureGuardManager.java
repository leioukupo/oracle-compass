package com.magneo.compass;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Root-backed, deliberately narrow guardian for Gesture's accessibility service.
 *
 * <p>The app only changes this package's entry in enabled_accessibility_services; all
 * other accessibility services are retained verbatim.  The matching Magisk guard uses
 * the same state directory and lock, so neither side races a settings rewrite.</p>
 */
public final class GestureGuardManager {
    public static final String TARGET_PACKAGE = "com.omarea.gesture";
    private static final String BIND_ACCESSIBILITY_SERVICE =
            "android.permission.BIND_ACCESSIBILITY_SERVICE";
    private static final String STATE_DIR = "/data/adb/oracle-compass";
    private static final String COMPONENT_FILE = STATE_DIR + "/gesture-component";
    private static final String APP_HEARTBEAT_FILE = STATE_DIR + "/gesture-app-heartbeat";
    private static final String MODULE_HEARTBEAT_FILE = STATE_DIR + "/gesture-module-heartbeat";
    private static final String REPAIR_FILE = STATE_DIR + "/gesture-last-repair";
    private static final String ERROR_FILE = STATE_DIR + "/gesture-last-error";
    private static final Object LOCK = new Object();
    private static final long STATUS_CACHE_MS = 4000L;

    private static volatile Snapshot cached = Snapshot.initial();
    private static volatile long cachedAt;
    private static int consecutiveUnbound;

    private GestureGuardManager() {}

    /** Periodic check. Configuration is repaired immediately; a bind reset waits for two misses. */
    public static Snapshot ensure(Context context) {
        return check(context, false, false);
    }

    /** Web-triggered repair. This may perform the one controlled unbind/rebind immediately. */
    public static Snapshot repair(Context context) {
        return check(context, true, true);
    }

    /** Persists only guard policy; disabling it never disables Gesture itself. */
    public static Snapshot setGuardEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        Prefs.putB(app, Prefs.K_GESTURE_GUARD_ENABLED, enabled);
        synchronized (LOCK) {
            String disabledFile = STATE_DIR + "/gesture-guard-disabled";
            String stopFile = STATE_DIR + "/gesture-guard-stop";
            String body = enabled ? "rm -f " + disabledFile + " " + stopFile
                    : "touch " + disabledFile + " " + stopFile + "; chmod 600 "
                    + disabledFile + " " + stopFile;
            CommandResult result = root(lockedShell(body));
            if (!result.ok) return remember(inspect(app, discover(app), enabled)
                    .withDetail("守护开关写入失败：" + compact(result.output)));
        }
        if (enabled) {
            GestureGuardService.start(app);
            return repair(app);
        }
        GestureGuardService.stop(app);
        return remember(inspect(app, discover(app), false)
                .withDetail("守护已停止；未关闭 Gesture 无障碍"));
    }

    /** Returns a fresh-enough read-only snapshot for the Web console. */
    public static Snapshot status(Context context) {
        boolean wanted = Prefs.gestureGuardEnabled(context);
        if (SystemClock.elapsedRealtime() - cachedAt < STATUS_CACHE_MS
                && cached.guardEnabled == wanted) return cached;
        return check(context, false, false);
    }

    private static Snapshot check(Context context, boolean explicitRepair, boolean forceRebind) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            ComponentResult component = discover(app);
            boolean enabled = Prefs.gestureGuardEnabled(app);
            Snapshot state = inspect(app, component, enabled);
            if (!enabled || !state.installed || component.component == null) {
                consecutiveUnbound = 0;
                return remember(state);
            }
            if (state.healthy()) {
                consecutiveUnbound = 0;
                touchHeartbeat(component.component);
                return remember(inspect(app, component, true));
            }

            // An enabled setting missing from the list is safe to restore on the first pass.
            boolean settingsWrong = !state.packageEnabled || !state.configured
                    || !state.accessibilityEnabled;
            if (settingsWrong || explicitRepair) {
                Snapshot after = applyRepair(app, component, forceRebind);
                if (after.bound) consecutiveUnbound = 0;
                else consecutiveUnbound++;
                return remember(after);
            }

            // A configured but unbound service gets one quiet confirmation before reset.
            consecutiveUnbound++;
            if (consecutiveUnbound >= 2) {
                Snapshot after = applyRepair(app, component, true);
                consecutiveUnbound = after.bound ? 0 : 0; // reset: retry only after two new misses
                return remember(after);
            }
            return remember(state.withDetail("服务已配置，等待下一次确认绑定状态"));
        }
    }

    private static Snapshot applyRepair(Context context, ComponentResult component, boolean rebind) {
        String c = component.component;
        if (c == null) return inspect(context, component, true);
        String quotedComponent = shell(c);
        String quotedPackage = shell(TARGET_PACKAGE);
        String command = lockedShell(
                "set -e; id 2>/dev/null | grep -q 'uid=0'; command -v settings >/dev/null 2>&1; "
                        + "pm enable " + quotedPackage + " >/dev/null 2>&1 || true; "
                        + "old=\"$(settings get secure enabled_accessibility_services 2>/dev/null)\"; "
                        + "new=\"\"; oldifs=\"$IFS\"; IFS=':'; "
                        + "for item in $old; do case \"$item\" in ''|" + TARGET_PACKAGE
                        + "/*) ;; *) if [ -n \"$new\" ]; then new=\"$new:$item\"; "
                        + "else new=\"$item\"; fi ;; esac; done; IFS=\"$oldifs\"; "
                        + "base=\"$new\"; if [ -n \"$base\" ]; then new=\"$base:" + c
                        + "\"; else new=\"" + c + "\"; fi; "
                        + (rebind
                        ? "settings put secure enabled_accessibility_services \"$base\" >/dev/null 2>&1; sleep 0.35; "
                        : "")
                        + "settings put secure enabled_accessibility_services \"$new\"; "
                        + "settings put secure accessibility_enabled 1; "
                        + "echo " + quotedComponent + " > " + COMPONENT_FILE + ".tmp; "
                        + "chmod 600 " + COMPONENT_FILE + ".tmp; mv " + COMPONENT_FILE + ".tmp "
                        + COMPONENT_FILE + "; "
                        + "now=\"$(cat /proc/uptime 2>/dev/null)\"; "
                        + "echo \"$now\" > " + APP_HEARTBEAT_FILE + ".tmp; "
                        + "chmod 600 " + APP_HEARTBEAT_FILE + ".tmp; mv " + APP_HEARTBEAT_FILE + ".tmp "
                        + APP_HEARTBEAT_FILE + "; "
                        + "echo \"$now " + (rebind ? "rebind" : "settings")
                        + "\" > " + REPAIR_FILE + ".tmp; chmod 600 " + REPAIR_FILE
                        + ".tmp; mv " + REPAIR_FILE + ".tmp " + REPAIR_FILE + "; "
                        + "rm -f " + ERROR_FILE + "; echo OK=1");
        CommandResult result = root(command);
        if (!result.ok) {
            writeError(compact(result.output));
            return inspect(context, component, true).withDetail("修复失败：" + compact(result.output));
        }
        // Give AccessibilityManager a short chance to bind before the next scheduled check.
        try { Thread.sleep(rebind ? 650L : 300L); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Snapshot after = inspect(context, component, true);
        return after.withDetail(after.bound ? (rebind ? "已受控重新绑定" : "已修复无障碍配置")
                : "已写入配置，等待系统绑定");
    }

    private static Snapshot inspect(Context context, ComponentResult component, boolean guardEnabled) {
        boolean installed = component.installed;
        if (!installed) {
            return new Snapshot(false, guardEnabled, false, false, false, false, false,
                    "", "Gesture 未安装", "", "", "");
        }
        if (component.component == null) {
            return new Snapshot(true, guardEnabled, component.packageEnabled, false, false, false,
                    false, "", component.problem, "", "", "");
        }
        String c = component.component;
        String longComponent = c.startsWith(TARGET_PACKAGE + "/.")
                ? TARGET_PACKAGE + "/" + TARGET_PACKAGE + "."
                + c.substring(TARGET_PACKAGE.length() + 2) : c;
        CommandResult result = root("set -e; id 2>/dev/null | grep -q 'uid=0'; command -v settings >/dev/null 2>&1; "
                + "command -v dumpsys >/dev/null 2>&1; echo ORACLE_ENABLED=\"$(settings get secure "
                + "enabled_accessibility_services 2>/dev/null)\"; "
                + "echo ORACLE_ACCESS=\"$(settings get secure accessibility_enabled 2>/dev/null)\"; "
                + "if dumpsys accessibility 2>/dev/null | grep -F " + shell(c)
                + " >/dev/null || dumpsys accessibility 2>/dev/null | grep -F " + shell(longComponent)
                + " >/dev/null; then echo ORACLE_BOUND=1; else echo ORACLE_BOUND=0; fi; "
                + "if dumpsys activity services " + shell(TARGET_PACKAGE)
                + " 2>/dev/null | grep -F " + shell(TARGET_PACKAGE)
                + " >/dev/null; then echo ORACLE_SERVICE=1; else echo ORACLE_SERVICE=0; fi; "
                + "if pidof " + shell(TARGET_PACKAGE) + " >/dev/null 2>&1 || ps 2>/dev/null | grep -F "
                + shell(TARGET_PACKAGE) + " | grep -v grep >/dev/null; then echo ORACLE_PROCESS=1; "
                + "else echo ORACLE_PROCESS=0; fi; "
                + "echo ORACLE_HEARTBEAT=\"$(cat " + MODULE_HEARTBEAT_FILE + " 2>/dev/null)\"; "
                + "echo ORACLE_REPAIR=\"$(cat " + REPAIR_FILE + " 2>/dev/null)\"; "
                + "echo ORACLE_ERROR=\"$(cat " + ERROR_FILE + " 2>/dev/null)\"");
        if (!result.ok) {
            return new Snapshot(true, guardEnabled, component.packageEnabled, false, false, false,
                    false, c, "Root 不可用：" + compact(result.output), "", "", "");
        }
        String services = value(result.output, "ORACLE_ENABLED=");
        boolean configured = containsComponent(services, c);
        boolean access = "1".equals(value(result.output, "ORACLE_ACCESS="));
        boolean accessibilityBound = "1".equals(value(result.output, "ORACLE_BOUND="));
        boolean process = "1".equals(value(result.output, "ORACLE_PROCESS="));
        boolean activityService = "1".equals(value(result.output, "ORACLE_SERVICE="));
        // Android 5.1's dumpsys accessibility lists only the service label (not its
        // component). The activity-service record is the authoritative component-level
        // binding there; require the accessibility master switch and a live process too.
        boolean bound = (accessibilityBound || activityService) && process && access;
        String detail = bound ? "AccessibilityService 已绑定"
                : (configured ? "AccessibilityService 尚未绑定" : "Gesture 未写入无障碍列表");
        return new Snapshot(true, guardEnabled, component.packageEnabled, configured, access, bound,
                process, c, detail, value(result.output, "ORACLE_HEARTBEAT="),
                value(result.output, "ORACLE_REPAIR="), value(result.output, "ORACLE_ERROR="));
    }

    private static void touchHeartbeat(String component) {
        root(lockedShell("now=\"$(cat /proc/uptime 2>/dev/null)\"; "
                + "echo " + shell(component) + " > " + COMPONENT_FILE + ".tmp; chmod 600 "
                + COMPONENT_FILE + ".tmp; mv " + COMPONENT_FILE + ".tmp " + COMPONENT_FILE + "; "
                + "echo \"$now\" > " + APP_HEARTBEAT_FILE + ".tmp; chmod 600 "
                + APP_HEARTBEAT_FILE + ".tmp; mv " + APP_HEARTBEAT_FILE + ".tmp "
                + APP_HEARTBEAT_FILE + "; rm -f " + ERROR_FILE));
    }

    private static void writeError(String error) {
        root(lockedShell("echo " + shell(error) + " > " + ERROR_FILE + ".tmp; "
                + "chmod 600 " + ERROR_FILE + ".tmp; mv " + ERROR_FILE + ".tmp " + ERROR_FILE));
    }

    /** shell lock shared with the Magisk script; it never waits more than one second. */
    private static String lockedShell(String body) {
        return "mkdir -p " + STATE_DIR + "; chmod 700 " + STATE_DIR + "; tries=0; "
                + "while ! mkdir " + STATE_DIR + "/gesture.lock 2>/dev/null; do tries=$((tries+1)); "
                + "[ \"$tries\" -ge 10 ] && exit 75; sleep 0.1; done; "
                + "trap 'rmdir " + STATE_DIR + "/gesture.lock 2>/dev/null' EXIT; " + body;
    }

    private static ComponentResult discover(Context context) {
        PackageManager pm = context.getPackageManager();
        boolean installed = true;
        boolean packageEnabled = false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(TARGET_PACKAGE, 0);
            packageEnabled = ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        } catch (Throwable ignored) {}
        if (!installed) return new ComponentResult(false, false, null, "Gesture 未安装");
        ArrayList<ServiceInfo> candidates = new ArrayList<>();
        try {
            Intent query = new Intent(AccessibilityService.SERVICE_INTERFACE);
            List<ResolveInfo> list = pm.queryIntentServices(query, PackageManager.GET_META_DATA);
            if (list != null) for (ResolveInfo ri : list) {
                ServiceInfo si = ri == null ? null : ri.serviceInfo;
                if (si != null && TARGET_PACKAGE.equals(si.packageName)
                        && BIND_ACCESSIBILITY_SERVICE.equals(si.permission)
                        && si.name != null && !si.name.trim().isEmpty()) candidates.add(si);
            }
        } catch (Throwable ignored) {}
        // queryIntentServices omits disabled packages on some Android 5.1 builds.  PackageInfo
        // still exposes their declared service so a root pm enable can recover them.
        if (candidates.isEmpty()) try {
            PackageInfo info = pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SERVICES);
            if (info.services != null) for (ServiceInfo si : info.services) {
                if (si != null && TARGET_PACKAGE.equals(si.packageName)
                        && BIND_ACCESSIBILITY_SERVICE.equals(si.permission)
                        && si.name != null && !si.name.trim().isEmpty()) candidates.add(si);
            }
        } catch (Throwable ignored) {}
        Collections.sort(candidates, new Comparator<ServiceInfo>() {
            @Override public int compare(ServiceInfo a, ServiceInfo b) { return a.name.compareTo(b.name); }
        });
        if (candidates.size() != 1) {
            return new ComponentResult(true, packageEnabled, null,
                    candidates.isEmpty() ? "未发现合法 AccessibilityService"
                            : "发现多个 Gesture AccessibilityService，拒绝猜测组件");
        }
        ComponentName name = new ComponentName(TARGET_PACKAGE, candidates.get(0).name);
        // Secure settings are written in the canonical fully-qualified form. Android accepts
        // the short form too, but older AccessibilityManager builds bind more reliably when
        // the class name is not abbreviated.
        return new ComponentResult(true, packageEnabled, name.flattenToString(), "");
    }

    private static boolean containsComponent(String list, String wanted) {
        if (list == null) return false;
        String[] parts = list.split(":");
        ComponentName target = ComponentName.unflattenFromString(wanted);
        for (String part : parts) {
            ComponentName found = ComponentName.unflattenFromString(part == null ? "" : part.trim());
            if (target != null && target.equals(found)) return true;
            if (wanted.equals(part != null ? part.trim() : "")) return true;
        }
        return false;
    }

    private static String value(String output, String key) {
        if (output == null) return "";
        String[] lines = output.replace('\r', '\n').split("\\n");
        for (String line : lines) if (line.startsWith(key)) return line.substring(key.length()).trim();
        return "";
    }

    private static Snapshot remember(Snapshot snapshot) {
        cached = snapshot;
        cachedAt = SystemClock.elapsedRealtime();
        return snapshot;
    }

    private static String shell(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\\"'\\\"'")) + "'";
    }

    private static String compact(String value) {
        String s = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > 220) s = s.substring(0, 220);
        return s.isEmpty() ? "root 命令没有返回状态" : s;
    }

    private static CommandResult root(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            int code = process.waitFor();
            return new CommandResult(code == 0, output.toString());
        } catch (Exception e) {
            return new CommandResult(false, e.toString());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static final class ComponentResult {
        final boolean installed, packageEnabled;
        final String component, problem;
        ComponentResult(boolean installed, boolean packageEnabled, String component, String problem) {
            this.installed = installed; this.packageEnabled = packageEnabled;
            this.component = component; this.problem = problem == null ? "" : problem;
        }
    }

    private static final class CommandResult {
        final boolean ok; final String output;
        CommandResult(boolean ok, String output) { this.ok = ok; this.output = output == null ? "" : output; }
    }

    public static final class Snapshot {
        public final boolean installed, guardEnabled, packageEnabled, configured, accessibilityEnabled;
        public final boolean bound, processRunning;
        public final String component, detail, moduleHeartbeat, lastRepair, lastError;
        Snapshot(boolean installed, boolean guardEnabled, boolean packageEnabled, boolean configured,
                 boolean accessibilityEnabled, boolean bound, boolean processRunning, String component,
                 String detail, String moduleHeartbeat, String lastRepair, String lastError) {
            this.installed = installed; this.guardEnabled = guardEnabled;
            this.packageEnabled = packageEnabled; this.configured = configured;
            this.accessibilityEnabled = accessibilityEnabled; this.bound = bound;
            this.processRunning = processRunning; this.component = component == null ? "" : component;
            this.detail = detail == null ? "" : detail;
            this.moduleHeartbeat = moduleHeartbeat == null ? "" : moduleHeartbeat;
            this.lastRepair = lastRepair == null ? "" : lastRepair;
            this.lastError = lastError == null ? "" : lastError;
        }
        static Snapshot initial() { return new Snapshot(false, true, false, false, false, false, false,
                "", "尚未检查", "", "", ""); }
        boolean healthy() { return packageEnabled && configured && accessibilityEnabled && bound; }
        Snapshot withDetail(String value) { return new Snapshot(installed, guardEnabled, packageEnabled,
                configured, accessibilityEnabled, bound, processRunning, component, value,
                moduleHeartbeat, lastRepair, lastError); }
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", installed && (healthy() || !guardEnabled));
                o.put("guardEnabled", guardEnabled); o.put("installed", installed);
                o.put("packageEnabled", packageEnabled); o.put("component", component);
                o.put("configured", configured); o.put("accessibilityEnabled", accessibilityEnabled);
                o.put("bound", bound); o.put("processRunning", processRunning);
                o.put("moduleHeartbeat", moduleHeartbeat); o.put("lastRepair", lastRepair);
                o.put("lastError", lastError); o.put("detail", detail);
            } catch (Exception ignored) {}
            return o;
        }
    }
}
