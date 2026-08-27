package com.magneo.compass;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reconnects the device to the strongest visible network already saved by Android. */
public final class SavedWifiAutoConnector {
    private static final String TAG = "OracleWifi";
    private static final long SCAN_INTERVAL_MS = 4000L;
    private static final long CONNECT_SETTLE_MS = 20000L;

    private static long lastScanAt;
    private static long lastConnectAt;
    private static boolean startupSelectionComplete;
    private static String lastDetail = "not checked";

    private SavedWifiAutoConnector() {}

    public static synchronized boolean ensureConnected(Context context) {
        Context app = context.getApplicationContext();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            lastDetail = "wifi service unavailable";
            return false;
        }
        try {
            if (!wifi.isWifiEnabled()) {
                boolean requested = wifi.setWifiEnabled(true);
                lastDetail = "enabling wifi requested=" + requested;
                Log.i(TAG, lastDetail);
                return false;
            }

            WifiInfo info = wifi.getConnectionInfo();
            if (hasUsableConnection(info)) {
                if (!startupSelectionComplete
                        && selectStrongerSavedNetwork(wifi, info, SystemClock.elapsedRealtime())) {
                    return false;
                }
                lastDetail = "connected networkId=" + info.getNetworkId()
                        + " rssi=" + info.getRssi();
                return true;
            }

            long now = SystemClock.elapsedRealtime();
            if (isAssociationInProgress(info) && now - lastConnectAt < CONNECT_SETTLE_MS) {
                lastDetail = "connection settling networkId=" + info.getNetworkId();
                return false;
            }
            if (now - lastScanAt >= SCAN_INTERVAL_MS) {
                lastScanAt = now;
                wifi.startScan();
            }

            List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
            List<ScanResult> visible = wifi.getScanResults();
            Candidate best = strongestSaved(saved, visible);
            if (best == null) {
                lastDetail = "no visible saved network configured=" + size(saved)
                        + " visible=" + size(visible);
                return false;
            }
            if (now - lastConnectAt < CONNECT_SETTLE_MS) {
                lastDetail = "waiting before reconnect networkId=" + best.config.networkId;
                return false;
            }

            connect(wifi, best, visibleSaved(saved, visible), now);
            return false;
        } catch (SecurityException e) {
            lastDetail = "wifi permission denied";
            Log.w(TAG, lastDetail, e);
            return false;
        } catch (Throwable t) {
            lastDetail = "wifi reconnect failed: " + t.getClass().getSimpleName();
            Log.w(TAG, lastDetail, t);
            return false;
        }
    }

    public static synchronized boolean isConnected(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            return wifi != null && hasUsableConnection(wifi.getConnectionInfo());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized String detail() {
        return lastDetail;
    }

    private static boolean selectStrongerSavedNetwork(WifiManager wifi, WifiInfo current,
                                                        long now) {
        if (now - lastScanAt >= SCAN_INTERVAL_MS) {
            lastScanAt = now;
            wifi.startScan();
        }
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        List<ScanResult> visible = wifi.getScanResults();
        List<Candidate> candidates = visibleSaved(saved, visible);
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.level > best.level) best = candidate;
        }
        if (best == null) {
            lastDetail = "connected; waiting for startup scan";
            return false;
        }
        if (best.config.networkId == current.getNetworkId()
                || best.level < current.getRssi() + 6) {
            startupSelectionComplete = true;
            return false;
        }
        if (now - lastConnectAt < CONNECT_SETTLE_MS) {
            lastDetail = "stronger saved network found; waiting to switch";
            return false;
        }
        connect(wifi, best, candidates, now);
        return true;
    }

    private static void connect(WifiManager wifi, Candidate best,
                                List<Candidate> candidates, long now) {
        // Re-enable every visible saved candidate first; then select only the strongest one.
        for (Candidate candidate : candidates) {
            wifi.enableNetwork(candidate.config.networkId, false);
        }
        boolean enabled = wifi.enableNetwork(best.config.networkId, true);
        boolean reconnecting = wifi.reconnect();
        lastConnectAt = now;
        lastDetail = "connect networkId=" + best.config.networkId
                + " rssi=" + best.level + " enabled=" + enabled
                + " reconnect=" + reconnecting;
        Log.i(TAG, lastDetail);
    }

    private static boolean hasUsableConnection(WifiInfo info) {
        return info != null
                && info.getNetworkId() >= 0
                && info.getSupplicantState() == SupplicantState.COMPLETED
                && info.getIpAddress() != 0;
    }

    private static boolean isAssociationInProgress(WifiInfo info) {
        if (info == null || info.getNetworkId() < 0) return false;
        SupplicantState state = info.getSupplicantState();
        return state == SupplicantState.ASSOCIATING
                || state == SupplicantState.ASSOCIATED
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE
                || state == SupplicantState.SCANNING
                || state == SupplicantState.COMPLETED;
    }

    private static Candidate strongestSaved(List<WifiConfiguration> saved,
                                             List<ScanResult> visible) {
        List<Candidate> candidates = visibleSaved(saved, visible);
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || candidate.level > best.level
                    || (candidate.level == best.level
                    && candidate.config.priority > best.config.priority)) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<Candidate> visibleSaved(List<WifiConfiguration> saved,
                                                 List<ScanResult> visible) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (saved == null || visible == null) return out;
        Map<String, WifiConfiguration> byNetwork = new HashMap<String, WifiConfiguration>();
        for (WifiConfiguration config : saved) {
            if (config == null || config.networkId < 0) continue;
            String key = networkKey(unquote(config.SSID), configSecurity(config));
            WifiConfiguration previous = byNetwork.get(key);
            if (previous == null || config.priority > previous.priority) {
                byNetwork.put(key, config);
            }
        }
        Map<Integer, Candidate> bestById = new HashMap<Integer, Candidate>();
        for (ScanResult scan : visible) {
            if (scan == null || scan.SSID == null || scan.SSID.length() == 0) continue;
            WifiConfiguration config = byNetwork.get(
                    networkKey(scan.SSID, scanSecurity(scan.capabilities)));
            if (config == null) continue;
            Candidate previous = bestById.get(config.networkId);
            if (previous == null || scan.level > previous.level) {
                bestById.put(config.networkId, new Candidate(config, scan.level));
            }
        }
        out.addAll(bestById.values());
        return out;
    }

    private static int configSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return 2;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) return 3;
        if (config.wepKeys != null) {
            for (String key : config.wepKeys) if (key != null) return 1;
        }
        return 0;
    }

    private static int scanSecurity(String capabilities) {
        String value = capabilities == null ? "" : capabilities.toUpperCase(Locale.US);
        if (value.contains("EAP")) return 3;
        if (value.contains("PSK")) return 2;
        if (value.contains("WEP")) return 1;
        return 0;
    }

    private static String networkKey(String ssid, int security) {
        return (ssid == null ? "" : ssid) + '\n' + security;
    }

    private static String unquote(String ssid) {
        if (ssid == null) return "";
        if (ssid.length() >= 2 && ssid.charAt(0) == '"'
                && ssid.charAt(ssid.length() - 1) == '"') {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static int size(List<?> value) {
        return value == null ? 0 : value.size();
    }

    private static final class Candidate {
        final WifiConfiguration config;
        final int level;

        Candidate(WifiConfiguration config, int level) {
            this.config = config;
            this.level = level;
        }
    }
}
