package com.magneo.compass;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 辅助定位：优先系统网络定位，再用 WiFi BSSID 定位；IP 只在用户显式配置时作为粗略兜底。 */
public class WifiLocator {
    public interface Callback { void onResult(double lat, double lon, float accuracy, String source); }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long INTERVAL_MS = 30000L;

    private final Context ctx;
    private final WifiManager wm;
    private final LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private Callback cb;
    private boolean running;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            locate();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public WifiLocator(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        wm = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
        lm = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start(Callback callback) {
        cb = callback;
        running = true;
        handler.removeCallbacks(loop);
        locate();
        handler.postDelayed(loop, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(loop);
    }

    private void locate() {
        if (!Prefs.locSourceWifiIp(ctx)) return;  // 只有粗定位模式才请求系统网络/WiFi/IP
        requestNetworkLocation();
        try { if (wm != null) wm.startScan(); } catch (Exception ignored) {}
        handler.postDelayed(new Runnable() {
            @Override public void run() { readAndQuery(); }
        }, 1500);
    }

    private void requestNetworkLocation() {
        if (lm == null) return;
        try {
            Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) fire(last, "系统网络");
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                    @Override public void onLocationChanged(Location l) { fire(l, "系统网络"); }
                    @Override public void onStatusChanged(String p, int st, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                }, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}
    }

    private void readAndQuery() {
        if (!running || !Prefs.locSourceWifiIp(ctx)) return;
        List<ScanResult> aps = new ArrayList<ScanResult>();
        try { if (wm != null) aps.addAll(wm.getScanResults()); } catch (Exception ignored) {}
        if (!aps.isEmpty()) {
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
                if (wifiUrl.isEmpty()) {
                    ipFallbackIfConfigured();
                    return;
                }
                Request req = new Request.Builder()
                        .url(wifiUrl)
                        .post(RequestBody.create(JSON, body.toString()))
                        .build();
                client.newCall(req).enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(Call c, java.io.IOException e) { ipFallbackIfConfigured(); }
                    @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                        try {
                            String s = r.body() == null ? "" : r.body().string();
                            JSONObject o = new JSONObject(s);
                            JSONObject loc = o.getJSONObject("location");
                            double lat = loc.getDouble("lat"), lng = loc.getDouble("lng");
                            float acc = (float) o.optDouble("accuracy", 500);
                            fire(lat, lng, acc, "WiFi");
                        } catch (Exception e) { ipFallbackIfConfigured(); }
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        ipFallbackIfConfigured();
    }

    private void ipFallbackIfConfigured() {
        if (!running || !Prefs.locSourceWifiIp(ctx)) return;
        String ipUrl = Prefs.locIpUrl(ctx);
        if (ipUrl == null || ipUrl.trim().isEmpty()) return;
        Request req = new Request.Builder().url(ipUrl).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, java.io.IOException e) {}
            @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                try {
                    JSONObject o = new JSONObject(r.body() == null ? "" : r.body().string());
                    double[] ll = parseIpLocation(o);
                    if (ll != null) fire(ll[0], ll[1], 5000f, ipSourceLabel(o));
                } catch (Exception ignored) {}
            }
        });
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

    private void fire(Location l, String src) {
        if (l == null) return;
        float acc = l.hasAccuracy() ? l.getAccuracy() : 1000f;
        fire(l.getLatitude(), l.getLongitude(), acc, src);
    }

    private void fire(double lat, double lon, float acc, String src) {
        if (cb != null) {
            final double fl = lat, fo = lon;
            final float fa = acc;
            final String fs = src;
            handler.post(new Runnable() {
                @Override public void run() { if (cb != null) cb.onResult(fl, fo, fa, fs); }
            });
        }
    }
}
