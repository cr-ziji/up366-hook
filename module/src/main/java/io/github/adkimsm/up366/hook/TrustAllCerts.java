package io.github.adkimsm.up366.hook;

import android.content.Context;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Process-wide "trust all certificates" support for packet capture. It covers more than the
 * OkHttp builder path: {@link HttpsURLConnection}, app-created {@link SSLContext}s, OkHttp
 * certificate pinning, legacy OkHttp 2 clients and WebView SSL errors.
 */
final class TrustAllCerts {
    private static final X509TrustManager TRUST_MANAGER = new X509TrustManager() {
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

    private static final HostnameVerifier ALLOW_ALL = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static volatile Context context;
    private static volatile SSLContext trustContext;
    private static volatile SSLSocketFactory trustFactory;
    private static volatile boolean installed;
    private static volatile boolean applied;

    private static SSLSocketFactory previousFactory;
    private static HostnameVerifier previousVerifier;
    private static SSLContext previousContext;

    private TrustAllCerts() {}

    static void install(Context value, ClassLoader classLoader) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        synchronized (TrustAllCerts.class) {
            if (!installed) {
                installed = true;
                hookSslContextInit();
                hookDefaultSetters();
                hookInstanceSetters();
                hookConscryptTrustManager();
                hookPlatformHostnameVerifier(classLoader);
                hookPlatformSocketFactories();
                hookCertificatePinners(classLoader);
                hookOkHttpGetters(classLoader);
                hookOkHttp2(classLoader);
            }
        }
        apply(Config.trustAllCerts(context));
    }

    /** Called by the panel switch: persists the value and updates the process-wide defaults now. */
    static void setEnabled(Context value, boolean enabled) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        Config.setTrustAllCerts(context, enabled);
        apply(enabled);
    }

    static boolean isEnabled() {
        Context current = context;
        return current != null && Config.trustAllCerts(current);
    }

    static SSLSocketFactory socketFactory() {
        return trustFactory();
    }

    static HostnameVerifier hostnameVerifier() {
        return ALLOW_ALL;
    }

    static X509TrustManager trustManager() {
        return TRUST_MANAGER;
    }

    private static synchronized void apply(boolean enabled) {
        try {
            if (enabled) {
                if (!applied) {
                    previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
                    previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
                    try {
                        previousContext = SSLContext.getDefault();
                    } catch (Throwable ignored) {
                        previousContext = null;
                    }
                    applied = true;
                }
                HttpsURLConnection.setDefaultSSLSocketFactory(trustFactory());
                HttpsURLConnection.setDefaultHostnameVerifier(ALLOW_ALL);
                if (previousContext != null) {
                    SSLContext.setDefault(trustContext());
                }
                LogSink.event("trust_all_applied", "mode", "process_defaults", "status", "ok");
            } else if (applied) {
                if (previousFactory != null) {
                    HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory);
                }
                if (previousVerifier != null) {
                    HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier);
                }
                if (previousContext != null) {
                    SSLContext.setDefault(previousContext);
                }
                previousFactory = null;
                previousVerifier = null;
                previousContext = null;
                applied = false;
                LogSink.event("trust_all_applied", "mode", "process_defaults", "status", "disabled");
            }
        } catch (Throwable error) {
            LogSink.error("apply_trust_all", error);
        }
    }

    private static SSLSocketFactory trustFactory() {
        SSLSocketFactory current = trustFactory;
        if (current != null) return current;
        synchronized (TrustAllCerts.class) {
            if (trustFactory == null) trustFactory = trustContext().getSocketFactory();
            return trustFactory;
        }
    }

    private static SSLContext trustContext() {
        SSLContext current = trustContext;
        if (current != null) return current;
        synchronized (TrustAllCerts.class) {
            if (trustContext == null) {
                try {
                    SSLContext created = SSLContext.getInstance("TLS");
                    created.init(null, new TrustManager[]{TRUST_MANAGER}, null);
                    trustContext = created;
                } catch (Throwable error) {
                    throw new IllegalStateException(error);
                }
            }
            return trustContext;
        }
    }

    private static void hookSslContextInit() {
        try {
            XposedHelpers.findAndHookMethod(SSLContext.class, "init",
                    KeyManager[].class, TrustManager[].class, java.security.SecureRandom.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isEnabled()) {
                                param.args[1] = new TrustManager[]{TRUST_MANAGER};
                            }
                        }
                    });
            LogSink.event("trust_all_hook", "hook", "SSLContext.init");
        } catch (Throwable error) {
            LogSink.error("hook_ssl_context_init", error);
        }
    }

    private static void hookDefaultSetters() {
        try {
            XposedHelpers.findAndHookMethod(HttpsURLConnection.class,
                    "setDefaultSSLSocketFactory", SSLSocketFactory.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isEnabled()) param.args[0] = trustFactory();
                        }
                    });
            LogSink.event("trust_all_hook", "hook", "HttpsURLConnection.setDefaultSSLSocketFactory");
        } catch (Throwable error) {
            LogSink.error("hook_default_ssl_factory", error);
        }
        try {
            XposedHelpers.findAndHookMethod(HttpsURLConnection.class,
                    "setDefaultHostnameVerifier", HostnameVerifier.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isEnabled()) param.args[0] = ALLOW_ALL;
                        }
                    });
            LogSink.event("trust_all_hook", "hook", "HttpsURLConnection.setDefaultHostnameVerifier");
        } catch (Throwable error) {
            LogSink.error("hook_default_hostname_verifier", error);
        }
    }

    private static void hookInstanceSetters() {
        try {
            XposedHelpers.findAndHookMethod(HttpsURLConnection.class,
                    "setSSLSocketFactory", SSLSocketFactory.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isEnabled()) param.args[0] = trustFactory();
                        }
                    });
            LogSink.event("trust_all_hook", "hook", "HttpsURLConnection.setSSLSocketFactory");
        } catch (Throwable error) {
            LogSink.error("hook_connection_ssl_factory", error);
        }
        try {
            XposedHelpers.findAndHookMethod(HttpsURLConnection.class,
                    "setHostnameVerifier", HostnameVerifier.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isEnabled()) param.args[0] = ALLOW_ALL;
                        }
                    });
            LogSink.event("trust_all_hook", "hook", "HttpsURLConnection.setHostnameVerifier");
        } catch (Throwable error) {
            LogSink.error("hook_connection_hostname_verifier", error);
        }
    }

    /**
     * The platform trust manager is what Android's default {@code SSLContext} and OkHttp's
     * {@code Platform} actually validate against. Bypassing it here covers clients that built their
     * own {@code SSLContext} from {@code TrustManagerFactory} before the switch was turned on, and
     * also covers {@code android.net.http.X509TrustManagerExtensions}.
     */
    private static void hookConscryptTrustManager() {
        String[] names = {
                "com.android.org.conscrypt.TrustManagerImpl",
                "com.android.org.conscrypt.X509TrustManagerImpl",
                "org.apache.harmony.xnet.provider.jsse.TrustManagerImpl",
                "android.net.http.X509TrustManagerExtensions"
        };
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name);
            } catch (Throwable ignored) {
                continue;
            }
            try {
                XposedBridge.hookAllMethods(type, "checkServerTrusted", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled()) allowTrust(param);
                    }
                });
                LogSink.event("trust_all_hook", "hook", name + ".checkServerTrusted");
            } catch (Throwable ignored) {
                // Older/newer platforms may not declare this method here.
            }
        }
    }

    /** Short-circuits a trust check, preserving the shape of the original result. */
    private static void allowTrust(XC_MethodHook.MethodHookParam param) {
        Class<?> returnType = ((Method) param.method).getReturnType();
        if (returnType == void.class) {
            param.setResult(null);
            return;
        }
        if (List.class.isAssignableFrom(returnType)) {
            List<X509Certificate> chain = new ArrayList<>();
            if (param.args != null && param.args.length > 0
                    && param.args[0] instanceof X509Certificate[]) {
                Collections.addAll(chain, (X509Certificate[]) param.args[0]);
            }
            param.setResult(chain);
        }
    }

    /** Android's default hostname verifier, used outside OkHttp's own verifier. */
    private static void hookPlatformHostnameVerifier(ClassLoader classLoader) {
        String[] names = {
                "com.android.org.conscrypt.OkHostnameVerifier",
                "org.apache.harmony.xnet.provider.jsse.OkHostnameVerifier",
                "com.android.okhttp.internal.tls.OkHostnameVerifier",
                "com.squareup.okhttp.internal.tls.OkHostnameVerifier",
                "okhttp3.internal.tls.OkHostnameVerifier"
        };
        for (String name : names) {
            Class<?> type = loadClass(name, classLoader);
            if (type == null) continue;
            try {
                XposedBridge.hookAllMethods(type, "verify", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled()) param.setResult(Boolean.TRUE);
                    }
                });
                LogSink.event("trust_all_hook", "hook", name + ".verify");
            } catch (Throwable ignored) {
                // Try the next provider name.
            }
        }
    }

    /** Loads a class from the target app first, then falls back to the boot class path. */
    private static Class<?> loadClass(String name, ClassLoader classLoader) {
        if (classLoader != null) {
            try {
                return Class.forName(name, false, classLoader);
            } catch (Throwable ignored) {
                // Fall through to the default loader.
            }
        }
        try {
            return Class.forName(name, false, TrustAllCerts.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Covers clients/factories created before the switch was enabled: whenever a platform TLS
     * socket is created, hand out one from the trust-all context instead.
     */
    private static void hookPlatformSocketFactories() {
        String[] names = {
                "com.android.org.conscrypt.SSLSocketFactoryImpl",
                "com.android.org.conscrypt.OpenSSLSocketFactoryImpl",
                "org.apache.harmony.xnet.provider.jsse.SSLSocketFactoryImpl"
        };
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name);
                XposedBridge.hookAllMethods(type, "createSocket", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        SSLSocketFactory target = trustFactory();
                        if (param.thisObject == target) return;
                        try {
                            Method hooked = (Method) param.method;
                            Method method = target.getClass().getMethod(hooked.getName(),
                                    hooked.getParameterTypes());
                            param.setResult(method.invoke(target, param.args));
                        } catch (Throwable error) {
                            LogSink.error("trust_all_socket_factory", error);
                        }
                    }
                });
                LogSink.event("trust_all_hook", "hook", name + ".createSocket");
            } catch (Throwable ignored) {
                // Try the next platform provider name.
            }
        }
    }

    private static void hookCertificatePinners(ClassLoader classLoader) {
        hookPinner(classLoader, "okhttp3.CertificatePinner");
        hookPinner(classLoader, "com.squareup.okhttp.CertificatePinner");
    }

    private static void hookPinner(ClassLoader classLoader, String className) {
        Class<?> pinner;
        try {
            pinner = Class.forName(className, false, classLoader);
        } catch (Throwable ignored) {
            // This OkHttp generation is not used by the target app.
            return;
        }
        boolean hooked = false;
        for (Method method : pinner.getDeclaredMethods()) {
            String name = method.getName();
            // OkHttp 4 exposes the internal variant as check$okhttp.
            if (!name.equals("check") && !name.startsWith("check$")) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isEnabled()) param.setResult(null);
                    }
                });
                hooked = true;
            } catch (Throwable ignored) {
                // Some overloads may be abstract or fail to hook.
            }
        }
        if (hooked) {
            LogSink.event("trust_all_hook", "hook", className + ".check");
        }
    }

    /**
     * OkHttp clients are immutable after build, so this also fixes clients created before the
     * switch was turned on: the connection reads these getters right before the TLS handshake.
     */
    private static void hookOkHttpGetters(ClassLoader classLoader) {
        Class<?> client;
        try {
            client = Class.forName("okhttp3.OkHttpClient", false, classLoader);
        } catch (Throwable ignored) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(client, "hostnameVerifier", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled()) param.setResult(ALLOW_ALL);
                }
            });
            LogSink.event("trust_all_hook", "hook", "okhttp3.OkHttpClient.hostnameVerifier");
        } catch (Throwable ignored) {
            // This OkHttp version does not expose the getter.
        }
        try {
            XposedHelpers.findAndHookMethod(client, "sslSocketFactory", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled()) param.setResult(trustFactory());
                }
            });
            LogSink.event("trust_all_hook", "hook", "okhttp3.OkHttpClient.sslSocketFactory");
        } catch (Throwable ignored) {
            // This OkHttp version does not expose the getter.
        }
    }

    /** OkHttp 2 clients are mutable; patch them again before every request. */
    private static void hookOkHttp2(ClassLoader classLoader) {
        try {
            Class<?> clientClass = Class.forName("com.squareup.okhttp.OkHttpClient", false, classLoader);
            XposedBridge.hookAllMethods(clientClass, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled()) applyOkHttp2(param.thisObject, classLoader);
                }
            });
            LogSink.event("trust_all_hook", "hook", "com.squareup.okhttp.OkHttpClient.newCall");
        } catch (Throwable ignored) {
            // OkHttp 2 is not used.
        }
    }

    private static void applyOkHttp2(Object client, ClassLoader classLoader) {
        try {
            Method setFactory = client.getClass().getMethod("setSslSocketFactory",
                    SSLSocketFactory.class);
            setFactory.invoke(client, trustFactory());
        } catch (Throwable error) {
            LogSink.error("apply_okhttp2_ssl_factory", error);
        }
        try {
            Method setVerifier = client.getClass().getMethod("setHostnameVerifier",
                    HostnameVerifier.class);
            setVerifier.invoke(client, ALLOW_ALL);
        } catch (Throwable error) {
            LogSink.error("apply_okhttp2_hostname_verifier", error);
        }
        try {
            Class<?> pinnerClass = Class.forName("com.squareup.okhttp.CertificatePinner", false,
                    classLoader);
            Class<?> builderClass = Class.forName("com.squareup.okhttp.CertificatePinner$Builder",
                    false, classLoader);
            Object builder = builderClass.getDeclaredConstructor().newInstance();
            Object pinner = builderClass.getMethod("build").invoke(builder);
            client.getClass().getMethod("setCertificatePinner", pinnerClass)
                    .invoke(client, pinner);
        } catch (Throwable ignored) {
            // Older OkHttp 2 builds may not expose a pinner setter.
        }
    }
}
