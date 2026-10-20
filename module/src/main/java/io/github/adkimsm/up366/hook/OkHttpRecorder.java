package io.github.adkimsm.up366.hook;

import android.content.Context;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Applies the configured proxy and optional trust-all to every OkHttp client the target app builds. */
final class OkHttpRecorder {
    private static final Map<Object, Boolean> INJECTED_BUILDERS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static volatile Context context;

    private OkHttpRecorder() {}

    static void install(ClassLoader loader) {
        try {
            Class<?> builder = Class.forName("okhttp3.OkHttpClient$Builder", false, loader);
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    inject(param.thisObject);
                }
            });
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_build", error);
        }
    }

    static void setContext(Context value) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
    }

    private static void inject(Object builder) {
        Context current = context;
        if (current == null) return;
        synchronized (INJECTED_BUILDERS) {
            if (INJECTED_BUILDERS.containsKey(builder)) return;
            INJECTED_BUILDERS.put(builder, Boolean.TRUE);
        }
        applyTrust(builder, current);
        applyProxy(builder, current);
    }

    /** Installs a trust-all manager when enabled (for packet capture). */
    private static void applyTrust(Object builder, Context current) {
        if (!Config.trustAllCerts(current)) return;
        try {
            X509TrustManager manager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{manager}, null);
            Method method = builder.getClass().getMethod("sslSocketFactory",
                    javax.net.ssl.SSLSocketFactory.class, X509TrustManager.class);
            method.invoke(builder, context.getSocketFactory(), manager);
            Method hostname = builder.getClass().getMethod("hostnameVerifier", HostnameVerifier.class);
            hostname.invoke(builder, new HostnameVerifier() {
                @Override
                public boolean verify(String host, SSLSession session) {
                    return true;
                }
            });
            LogSink.event("okhttp_trust_applied", "builder", builder.getClass().getName(),
                    "mode", "trust_all", "status", "ok");
        } catch (Throwable error) {
            LogSink.error("apply_okhttp_trust", error);
        }
    }

    /** Installs the configured HTTP proxy directly on the builder. */
    private static void applyProxy(Object builder, Context current) {
        if (!Config.proxyEnabled(current)) return;
        String host = Config.proxyHost(current);
        int port = Config.proxyPort(current);
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return;
        try {
            Method method = builder.getClass().getMethod("proxy", java.net.Proxy.class);
            method.invoke(builder, ProxyHelper.proxy(host, port));
            LogSink.event("okhttp_proxy_applied", "builder", builder.getClass().getName(),
                    "host", host, "port", Integer.toString(port));
        } catch (Throwable error) {
            LogSink.error("apply_okhttp_proxy", error);
        }
    }
}
