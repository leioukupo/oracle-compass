package com.magneo.compass;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
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

/** WiFi/IP 辅助定位（GPS 硬件收不到卫星时兜底）：
 *  1) WiFi 扫描 BSSID -> Mozilla Location Services（WiFi 级，约百米精度；test key 失效时跳过）
 *  2) 兜底 ip-api.com 按出口 IP 定位（城市级，约 5km，国内可达无需 key）
 *  如日后申请到高德/腾讯定位 key，可在此把 WiFi 请求换到对应服务。 */
public class WifiLocator {
    public interface Callback { void onResult(double lat, double lon, float accuracy, String source); }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long INTERVAL_MS = 30000L;

    private final Context ctx;
    private final WifiManager wm;
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
        if (!Prefs.getB(ctx, Prefs.K_SHOW_LOC, true)) return;   // 定位显示开关关闭：不请求
        try { if (wm != null) wm.startScan(); } catch (Exception ignored) {}
        handler.postDelayed(new Runnable() {
            @Override public void run() { readAndQuery(); }
        }, 1500);
    }

    private void readAndQuery() {
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
                String wifiUrl = Prefs.get(ctx, Prefs.K_LOC_WIFI_URL, Prefs.DEFAULT_LOC_WIFI_URL);
                Request req = new Request.Builder()
                        .url(wifiUrl)
                        .post(RequestBody.create(JSON, body.toString()))
                        .build();
                client.newCall(req).enqueue(new okhttp3.Callback() {
                    @Override public void onFailure(Call c, java.io.IOException e) { ipFallback(); }
                    @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                        try {
                            String s = r.body().string();
                            JSONObject o = new JSONObject(s);
                            JSONObject loc = o.getJSONObject("location");
                            double lat = loc.getDouble("lat"), lng = loc.getDouble("lng");
                            float acc = (float) o.optDouble("accuracy", 500);
                            fire(lat, lng, acc, "WiFi");
                        } catch (Exception e) { ipFallback(); }
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        ipFallback();
    }

    private void ipFallback() {
        String ipUrl = Prefs.get(ctx, Prefs.K_LOC_IP_URL, Prefs.DEFAULT_LOC_IP_URL);
        Request req = new Request.Builder().url(ipUrl).build();
        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call c, java.io.IOException e) {}
            @Override public void onResponse(Call c, Response r) throws java.io.IOException {
                try {
                    JSONObject o = new JSONObject(r.body().string());
                    if ("success".equals(o.optString("status"))) {
                        fire(o.getDouble("lat"), o.getDouble("lon"), 5000f, "IP");
                    }
                } catch (Exception ignored) {}
            }
        });
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
