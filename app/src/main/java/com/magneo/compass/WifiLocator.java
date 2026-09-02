package com.magneo.compass;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 辅助定位：缓存优先，Wi-Fi 变化或缓存过期时才扫描；IP 只作粗略兜底。 */
public class WifiLocator {
    public interface Callback { void onResult(double lat, double lon, float accuracy, String source); }

    private static final String TAG = "WifiLocator";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long NORMAL_INTERVAL_MS = 12 * 60 * 1000L;
    private static final long SCAN_TTL_MS = 12 * 60 * 1000L;
    private static final long RESULT_TTL_MS = 15 * 60 * 1000L;
    private static volatile long globalLastScanMs;
    private static volatile long globalLastResultMs;

    private final Context ctx;
    private final WifiManager wm;
    private final LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();
    private Callback cb;
    private boolean running;
    private long lastScanMs;
    private long lastResultMs;
    private String lastWifiIdentity = "";
    private int failures;
    private Call wifiCall;
    private Call ipCall;
    private LocationListener networkListener;
    private Runnable pendingRead;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            locate("定时");
        }
    };

    public WifiLocator(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        wm = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
        lm = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public static long scanAgeMs() {
        return ageMs(globalLastScanMs);
    }

    public static long resultAgeMs() {
        return ageMs(globalLastResultMs);
    }

    private static long ageMs(long timestamp) {
        if (timestamp <= 0) return -1L;
        return Math.max(0L, System.currentTimeMillis() - timestamp);
    }

    public void start(Callback callback) {
        cb = callback;
        running = true;
        failures = 0;
        handler.removeCallbacks(loop);
        locate("启动");
    }

    public void stop() {
        running = false;
        cb = null;
        handler.removeCallbacks(loop);
        if (pendingRead != null) handler.removeCallbacks(pendingRead);
        pendingRead = null;
        cancelCall(wifiCall);
        cancelCall(ipCall);
        wifiCall = null;
        ipCall = null;
        if (lm != null && networkListener != null) {
            try { lm.removeUpdates(networkListener); } catch (Exception ignored) {}
        }
        networkListener = null;
    }

    private void locate(String reason) {
        if (!running || !Prefs.locSourceWifiIp(ctx)) return;
        long now = System.currentTimeMillis();
        String wifiIdentity = wifiIdentity();
        boolean changed = !lastWifiIdentity.isEmpty() && !lastWifiIdentity.equals(wifiIdentity);
        if (lastWifiIdentity.isEmpty()) changed = true;
        lastWifiIdentity = wifiIdentity;
        boolean freshResult = lastResultMs > 0 && now - lastResultMs < RESULT_TTL_MS;
        boolean scanFresh = lastScanMs > 0 && now - lastScanMs < SCAN_TTL_MS;

        if (freshResult && !changed) {
            log("skip locate reason=" + reason + " cacheAgeMs=" + (now - lastResultMs)
                    + " bssidChanged=false");
            scheduleNext(false);
            return;
        }

        requestNetworkLocation(!freshResult || changed);
        if (!scanFresh || changed || !freshResult) {
            lastScanMs = now;
            globalLastScanMs = now;
            try { if (wm != null) wm.startScan(); } catch (Exception e) {
                log("wifi scan request failed: " + e.getClass().getSimpleName());
            }
            pendingRead = () -> {
                pendingRead = null;
                readAndQuery("fresh");
            };
            handler.postDelayed(pendingRead, 1500);
            log("wifi scan requested reason=" + reason + " bssidChanged=" + changed);
        } else {
            readAndQuery("cached-scan");
            log("use cached scan reason=" + reason + " scanAgeMs=" + (now - lastScanMs));
        }
        scheduleNext(false);
    }

    private void scheduleNext(boolean retry) {
        if (!running) return;
        handler.removeCallbacks(loop);
        long delay;
        if (!retry || failures == 0) delay = NORMAL_INTERVAL_MS;
        else if (failures == 1) delay = 30 * 1000L;
        else if (failures == 2) delay = 2 * 60 * 1000L;
        else delay = 5 * 60 * 1000L;
        handler.postDelayed(loop, delay);
    }

    private void requestNetworkLocation(boolean needed) {
        if (!needed || lm == null) return;
        if (networkListener != null) {
            try { lm.removeUpdates(networkListener); } catch (Exception ignored) {}
        }
        networkListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                markSuccess("系统网络");
                fire(l, "系统网络");
            }
            @Override public void onStatusChanged(String p, int st, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) {
                markSuccess("系统网络缓存");
                fire(last, "系统网络");
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, networkListener,
                        Looper.getMainLooper());
            }
        } catch (Exception e) {
            log("network location failed: " + e.getClass().getSimpleName());
        }
    }

    private void readAndQuery(String mode) {
        if (!running || !Prefs.locSourceWifiIp(ctx)) return;
        List<ScanResult> aps = new ArrayList<ScanResult>();
        try { if (wm != null) aps.addAll(wm.getScanResults()); } catch (Exception ignored) {}
        if (aps.isEmpty()) {
            log("no wifi scan results mode=" + mode);
            ipFallbackIfConfigured();
            return;
        }
        Collections.sort(aps, new Comparator<ScanResult>() {
            @Override public int compare(ScanResult a, ScanResult b) { return b.level - a.level; }
        });
        int n = Math.min(8, aps.size());
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < n; i++) {
                arr.put(new JSONObject()
                        .put("macAddress", aps.get(i).BSSID)
                        .put("signalStrength", aps.get(i).level));
            }
            JSONObject body = new JSONObject().put("wifiAccessPoints", arr);
            String wifiUrl = Prefs.locWifiUrl(ctx);
            if (wifiUrl == null || wifiUrl.trim().isEmpty()) {
                ipFallbackIfConfigured();
                return;
            }
            cancelCall(wifiCall);
            Request req = new Request.Builder()
                    .url(wifiUrl)
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            wifiCall = client.newCall(req);
            wifiCall.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call c, java.io.IOException e) {
                    if (!running) return;
                    wifiCall = null;
                    log("wifi locator failed, trying ip fallback: " + e.getClass().getSimpleName());
                    ipFallbackIfConfigured();
                }
                @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                    try {
                        if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
                        String s = r.body() == null ? "" : r.body().string();
                        JSONObject o = new JSONObject(s);
                        JSONObject loc = o.getJSONObject("location");
                        double lat = loc.getDouble("lat"), lng = loc.getDouble("lng");
                        float acc = (float) o.optDouble("accuracy", 500);
                        markSuccess("WiFi");
                        fire(lat, lng, acc, "WiFi");
                    } catch (Exception e) {
                        if (running) {
                            log("wifi locator response invalid, trying ip fallback");
                            ipFallbackIfConfigured();
                        }
                    } finally { r.close(); wifiCall = null; }
                }
            });
        } catch (Exception e) {
            log("wifi request build failed: " + e.getClass().getSimpleName());
            ipFallbackIfConfigured();
        }
    }

    private void ipFallbackIfConfigured() {
        if (!running || !Prefs.locSourceWifiIp(ctx)) return;
        String ipUrl = Prefs.locIpUrl(ctx);
        if (ipUrl == null || ipUrl.trim().isEmpty()) {
            markFailure("no IP locator configured");
            return;
        }
        cancelCall(ipCall);
        try {
            Request req = new Request.Builder().url(ipUrl).build();
            ipCall = client.newCall(req);
            ipCall.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call c, java.io.IOException e) {
                    if (!running) return;
                    ipCall = null;
                    markFailure("ip locator: " + e.getClass().getSimpleName());
                }
                @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                    try {
                        if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
                        JSONObject o = new JSONObject(r.body() == null ? "" : r.body().string());
                        double[] ll = parseIpLocation(o);
                        if (ll == null) throw new java.io.IOException("invalid location");
                        markSuccess("IP");
                        fire(ll[0], ll[1], 5000f, ipSourceLabel(o));
                    } catch (Exception e) {
                        if (running) markFailure("ip locator response invalid");
                    } finally { r.close(); ipCall = null; }
                }
            });
        } catch (Exception e) {
            markFailure("ip request build failed");
        }
    }

    private double[] parseIpLocation(JSONObject o) {
        try {
            if (o.has("lat") && o.has("lon")) {
                String status = o.optString("status", "success");
                if (status.isEmpty() || "success".equalsIgnoreCase(status)) {
                    return new double[]{o.getDouble("lat"), o.getDouble("lon")};
                }
            }
            if (o.has("latitude") && o.has("longitude")) {
                return new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
            }
            if (o.has("lat") && o.has("lng")) {
                return new double[]{o.getDouble("lat"), o.getDouble("lng")};
            }
            String loc = o.optString("loc", "");
            if (!loc.isEmpty() && loc.indexOf(',') > 0) {
                String[] p = loc.split(",", 2);
                return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String ipSourceLabel(JSONObject o) {
        String city = o.optString("city", "");
        if (city == null || city.trim().isEmpty()) return "IP粗略";
        return "IP粗略 " + city.trim();
    }

    private String wifiIdentity() {
        try {
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String bssid = info.getBSSID();
            if (bssid == null || bssid.trim().isEmpty() || "<none>".equalsIgnoreCase(bssid)) return "";
            return bssid;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void markSuccess(String source) {
        lastResultMs = System.currentTimeMillis();
        globalLastResultMs = lastResultMs;
        failures = 0;
        log("location success source=" + source);
    }

    private void markFailure(String reason) {
        failures++;
        log("location failure attempt=" + failures + " reason=" + reason);
        scheduleNext(true);
    }

    private void fire(Location l, String src) {
        if (l == null) return;
        float acc = l.hasAccuracy() ? l.getAccuracy() : 1000f;
        fire(l.getLatitude(), l.getLongitude(), acc, src);
    }

    private void fire(double lat, double lon, float acc, String src) {
        if (cb != null && running) {
            final double fl = lat, fo = lon;
            final float fa = acc;
            final String fs = src;
            handler.post(() -> {
                if (cb != null && running) cb.onResult(fl, fo, fa, fs);
            });
        }
    }

    private void cancelCall(Call call) {
        if (call != null) {
            try { call.cancel(); } catch (Exception ignored) {}
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        try { DebugLog.append(ctx, "location", message); } catch (Throwable ignored) {}
    }
}
