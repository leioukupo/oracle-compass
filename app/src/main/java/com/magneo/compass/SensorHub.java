package com.magneo.compass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationProvider;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** 统一订阅设备全部传感器与 GPS，提供最新读数与罗盘方位。 */
public class SensorHub {
    public interface Listener { void onUpdate(); }
    /** 供网页状态接口读取当前 GPS 状态 */
    public static volatile SensorHub instance;

    private final SensorManager sm;
    private final LocationManager lm;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public volatile float ax, ay, az, gx, gy, gz, mx, my, mz;
    public volatile float light = -1, prox = -1;
    public volatile float azimuth = Float.NaN, pitch, roll;
    public volatile int battery = 100;
    public volatile double lat = Double.NaN, lon = Double.NaN, alt = Double.NaN;
    public volatile double netLat = Double.NaN, netLon = Double.NaN;
    public volatile float netAcc = 0;
    public volatile String netSrc = "";
    public volatile int sats = 0;
    public volatile String gpsStatus = "未定位";
    public volatile long lastSensorMs = 0;
    private volatile boolean started;
    /** 定位显示开关：关闭后不请求 GPS（省电），由 MainActivity 设置 */
    public volatile boolean gpsEnabled = true;
    private boolean gpsRequested;
    private long lastGpsEventMs = 0;
    private boolean batteryRcRegistered;
    private final Context appCtx;

    private final float[] rMat = new float[9];
    private final float[] iMat = new float[9];
    private final float[] ori = new float[3];
    private boolean hasAccel, hasMag;

    public SensorHub(Context ctx, Listener l) {
        instance = this;
        appCtx = ctx.getApplicationContext();
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        listener = l;
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) battery = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
    }

    /** 电量广播：ACTION_BATTERY_CHANGED 每次电量变化都会触发，保证电量显示自动更新 */
    private final BroadcastReceiver batteryRc = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            battery = i.getIntExtra(BatteryManager.EXTRA_LEVEL, battery);
            listener.onUpdate();
        }
    };

    private final SensorEventListener sens = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            lastSensorMs = System.currentTimeMillis();
            switch (e.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    ax = e.values[0]; ay = e.values[1]; az = e.values[2]; hasAccel = true; break;
                case Sensor.TYPE_GYROSCOPE:
                    gx = e.values[0]; gy = e.values[1]; gz = e.values[2]; break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    mx = e.values[0]; my = e.values[1]; mz = e.values[2]; hasMag = true; break;
                case Sensor.TYPE_LIGHT: light = e.values[0]; break;
                case Sensor.TYPE_PROXIMITY: prox = e.values[0]; break;
            }
            updateFused();
            listener.onUpdate();
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    private final GpsStatus.Listener gpsListener = new GpsStatus.Listener() {
        @Override public void onGpsStatusChanged(int event) {
            try {
                GpsStatus st = lm.getGpsStatus(null);
                if (st == null) return;
                int total = 0, used = 0;
                for (GpsSatellite s : st.getSatellites()) { total++; if (s.usedInFix()) used++; }
                sats = used;
                lastGpsEventMs = System.currentTimeMillis();
                if (event == GpsStatus.GPS_EVENT_FIRST_FIX) gpsStatus = "已定位";
                else if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS)
                    gpsStatus = total > 0 ? ("定位中 " + used + "/" + total) : "搜索卫星…";
                listener.onUpdate();
            } catch (Exception ignored) {}
        }
    };

    /** 传感器静默自愈：MTK 传感器偶发停摆，3 秒无事件就重挂一次监听 */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long now = System.currentTimeMillis();
            if (now - lastSensorMs > 3000) {
                try { sm.unregisterListener(sens); } catch (Exception ignored) {}
                registerAll();
            }
            if (lastGpsEventMs > 0 && now - lastGpsEventMs > 120000
                    && (gpsStatus.startsWith("搜索") || gpsStatus.startsWith("定位中"))) {
                gpsStatus = "无卫星信号";   // 2 分钟没有任何卫星事件：天线/信号问题
            }
            if (gpsEnabled && !gpsRequested) requestGps();       // 开关打开：恢复 GPS 请求
            else if (!gpsEnabled && gpsRequested) {              // 开关关闭：1 秒内停掉 GPS
                stopGps();
                gpsStatus = "已关闭";
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final LocationListener loc = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            lat = l.getLatitude(); lon = l.getLongitude(); alt = l.getAltitude();
            listener.onUpdate();
        }
        @Override public void onStatusChanged(String p, int st, Bundle b) {
            gpsStatus = st == LocationProvider.AVAILABLE ? "定位中" : st == LocationProvider.OUT_OF_SERVICE ? "失效" : "暂不可用";
            listener.onUpdate();
        }
        @Override public void onProviderEnabled(String p) { listener.onUpdate(); }
        @Override public void onProviderDisabled(String p) { listener.onUpdate(); }
    };

    private void updateFused() {
        if (hasAccel && hasMag && SensorManager.getRotationMatrix(rMat, iMat,
                new float[]{ax, ay, az}, new float[]{mx, my, mz})) {
            SensorManager.getOrientation(rMat, ori);
            azimuth = (float) Math.toDegrees(ori[0]);
            pitch = (float) Math.toDegrees(ori[1]);
            roll = (float) Math.toDegrees(ori[2]);
        }
    }

    public void start() {
        started = true;
        registerAll();
        lastGpsEventMs = System.currentTimeMillis();
        if (!batteryRcRegistered) {
            try {
                appCtx.registerReceiver(batteryRc, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                batteryRcRegistered = true;
            } catch (Exception ignored) {}
        }
        if (gpsEnabled) requestGps();
        else gpsStatus = "已关闭";
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 1000);
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(watchdog);
        sm.unregisterListener(sens);
        try { lm.removeUpdates(loc); } catch (Exception ignored) {}
        try { lm.removeGpsStatusListener(gpsListener); } catch (Exception ignored) {}
        if (batteryRcRegistered) {
            try { appCtx.unregisterReceiver(batteryRc); } catch (Exception ignored) {}
            batteryRcRegistered = false;
        }
    }

    private void requestGps() {
        try {
            gpsStatus = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? "搜索卫星…" : "定位已关闭";
            try { lm.addGpsStatusListener(gpsListener); } catch (Exception ignored) {}
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l != null) { lat = l.getLatitude(); lon = l.getLongitude(); alt = l.getAltitude(); }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, loc);
            gpsRequested = true;
        } catch (Exception ignored) {}
    }

    private void stopGps() {
        try { lm.removeUpdates(loc); } catch (Exception ignored) {}
        try { lm.removeGpsStatusListener(gpsListener); } catch (Exception ignored) {}
        gpsRequested = false;
    }

    private void registerAll() {
        register(Sensor.TYPE_ACCELEROMETER);
        register(Sensor.TYPE_GYROSCOPE);
        register(Sensor.TYPE_MAGNETIC_FIELD);
        register(Sensor.TYPE_LIGHT);
        register(Sensor.TYPE_PROXIMITY);
    }

    private void register(int type) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(sens, s, SensorManager.SENSOR_DELAY_UI, handler);
    }
}
