package com.magneo.compass.netfs;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/** TLS 公共处理：按设置“兼容 CA（忽略证书）”忽略证书，否则在系统信任库上追加内置现代根证书
 *  （安卓 5.1 信任库旧，Let's Encrypt / Google 的新根不在其中会报 trust anchor 找不到）。 */
public final class Tls {
    private static final String TAG = "Tls";

    private Tls() {}

    /** 构建带证书策略的 OkHttpClient.Builder（ctx 传 application context 即可）。 */
    public static OkHttpClient.Builder builder(Context ctx) {
        OkHttpClient.Builder b = new OkHttpClient.Builder();
        if (com.magneo.compass.Prefs.getB(ctx, com.magneo.compass.Prefs.K_IGNORE_SSL, false)) {
            bypass(b);
        } else {
            bundled(b, ctx);
        }
        return b;
    }

    private static void bypass(OkHttpClient.Builder b) {
        try {
            TrustManager[] tms = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, tms, new java.security.SecureRandom());
            b.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) tms[0]);
            b.hostnameVerifier((h, s) -> true);
        } catch (Exception e) { Log.w(TAG, "ssl bypass failed", e); }
    }

    private static void bundled(OkHttpClient.Builder b, Context ctx) {
        try {
            TrustManager[] tms = bundledTrustManagers(ctx);
            if (tms != null) {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, tms, null);
                b.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) tms[0]);
            }
        } catch (Exception e) { Log.w(TAG, "bundled trust failed", e); }
    }

    /** 系统信任库 + assets/certs 内置根证书的复合 TrustManager。 */
    public static TrustManager[] bundledTrustManagers(Context ctx) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] files = ctx.getAssets().list("certs");
            if (files != null) {
                for (String f : files) {
                    try {
                        InputStream in = ctx.getAssets().open("certs/" + f);
                        Certificate c = cf.generateCertificate(in);
                        in.close();
                        ks.setCertificateEntry(f, c);
                    } catch (Exception ignored) {}
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            return tmf.getTrustManagers();
        } catch (Exception e) {
            Log.w(TAG, "bundled trust init failed", e);
            return null;
        }
    }
}
