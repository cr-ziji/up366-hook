package io.github.adkimsm.up366.hook;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.ProxySelector;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Applies the configured proxy and optional trust-all to every OkHttp client the target app uses.
 *
 * <p>Proxy handling is intentionally not a build-time {@code .proxy(...)} call: that can only be
 * changed by rebuilding a client, which most apps do once. Instead the builder always receives the
 * process-wide dynamic selector from {@link ProxyHelper} and has any explicit proxy cleared, and
 * clients that were already built (or built by a library that captured the old selector) are
 * patched again right before each {@code newCall}.
 */
final class OkHttpRecorder {
    private static final Map<Object, Boolean> INJECTED_BUILDERS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Boolean> PATCHED_CLIENTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private static volatile Context context;
    private static volatile boolean builderProxyFailureLogged;
    private static volatile boolean clientProxyFailureLogged;
    private static volatile boolean okhttp2FailureLogged;

    private OkHttpRecorder() {}

    static void install(ClassLoader loader) {
        hookBuilder(loader);
        hookClient(loader);
        hookOkHttp2Client(loader);
    }

    static void setContext(Context value) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
    }

    // ------------------------------------------------------------------ okhttp3 / okhttp4

    private static void hookBuilder(ClassLoader loader) {
        try {
            Class<?> builder = Class.forName("okhttp3.OkHttpClient$Builder", false, loader);
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    inject(param.thisObject);
                }
            });
            LogSink.event("proxy_hook", "hook", "okhttp3.OkHttpClient$Builder.build");
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_build", error);
        }
    }

    private static void inject(Object builder) {
        Context current = context;
        if (current == null) return;
        synchronized (INJECTED_BUILDERS) {
            if (INJECTED_BUILDERS.containsKey(builder)) return;
            INJECTED_BUILDERS.put(builder, Boolean.TRUE);
        }
        applyTrust(builder, current);
        applyProxyToBuilder(builder);
    }

    /** Installs the process-wide trust-all factory when the panel switch is on. */
    private static void applyTrust(Object builder, Context current) {
        if (!Config.trustAllCerts(current)) return;
        try {
            Method method = builder.getClass().getMethod("sslSocketFactory",
                    javax.net.ssl.SSLSocketFactory.class, X509TrustManager.class);
            method.invoke(builder, TrustAllCerts.socketFactory(), TrustAllCerts.trustManager());
            Method hostname = builder.getClass().getMethod("hostnameVerifier", HostnameVerifier.class);
            hostname.invoke(builder, TrustAllCerts.hostnameVerifier());
            LogSink.event("okhttp_trust_applied", "builder", builder.getClass().getName(),
                    "mode", "trust_all", "status", "ok");
        } catch (Throwable error) {
            LogSink.error("apply_okhttp_trust", error);
        }
    }

    /**
     * Clears an explicit proxy and installs the dynamic selector. The selector itself decides
     * whether the proxy is on, so this is done even while the panel switch is off: toggling later
     * then works without rebuilding the client.
     */
    private static void applyProxyToBuilder(Object builder) {
        try {
            boolean ok = call(builder, "proxy", Proxy.class, new Object[]{null});
            ok &= call(builder, "proxySelector", ProxySelector.class,
                    new Object[]{ProxyHelper.selector()});
            if (!ok) {
                // Obfuscated or unusual OkHttp build: fall back to the backing fields.
                setField(builder, "proxy", null);
                setField(builder, "proxySelector", ProxyHelper.selector());
            }
            LogSink.event("okhttp_proxy_selector", "builder", builder.getClass().getName());
        } catch (Throwable error) {
            if (!builderProxyFailureLogged) {
                builderProxyFailureLogged = true;
                LogSink.error("apply_okhttp_proxy", error);
            }
        }
    }

    /**
     * Hooks the client itself so that clients created before the module was installed (or with an
     * explicit proxy/selector) also follow the panel. {@code newCall} is the last common entry
     * point before routing happens.
     */
    private static void hookClient(ClassLoader loader) {
        Class<?> client;
        try {
            client = Class.forName("okhttp3.OkHttpClient", false, loader);
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_client", error);
            return;
        }
        try {
            XposedBridge.hookAllMethods(client, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    patchClient(param.thisObject);
                }
            });
            LogSink.event("proxy_hook", "hook", "okhttp3.OkHttpClient.newCall");
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_newcall", error);
        }
        // Getter hooks cover clients whose final fields cannot be rewritten on this platform.
        hookClientGetters(client);
    }

    private static void patchClient(Object client) {
        synchronized (PATCHED_CLIENTS) {
            if (PATCHED_CLIENTS.containsKey(client)) return;
            PATCHED_CLIENTS.put(client, Boolean.TRUE);
        }
        try {
            setField(client, "proxy", null);
            setField(client, "proxySelector", ProxyHelper.selector());
            LogSink.event("okhttp_proxy_patched", "client", client.getClass().getName());
        } catch (Throwable error) {
            if (!clientProxyFailureLogged) {
                clientProxyFailureLogged = true;
                LogSink.error("apply_okhttp_client_proxy", error);
            }
        }
    }

    private static void hookClientGetters(Class<?> client) {
        try {
            XposedHelpers.findAndHookMethod(client, "proxySelector", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ProxyHelper.isEnabled()) param.setResult(ProxyHelper.selector());
                }
            });
        } catch (Throwable ignored) {
            // OkHttp 5 may hide the getter; field patching still applies.
        }
        try {
            XposedHelpers.findAndHookMethod(client, "proxy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ProxyHelper.isEnabled()) param.setResult(null);
                }
            });
        } catch (Throwable ignored) {
            // OkHttp 5 may hide the getter; field patching still applies.
        }
    }

    // ------------------------------------------------------------------ okhttp2

    /** OkHttp 2 clients are mutable; re-apply before every request so toggling works live. */
    private static void hookOkHttp2Client(ClassLoader loader) {
        Class<?> client;
        try {
            client = Class.forName("com.squareup.okhttp.OkHttpClient", false, loader);
        } catch (Throwable ignored) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(client, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    applyOkHttp2Proxy(param.thisObject);
                }
            });
            LogSink.event("proxy_hook", "hook", "com.squareup.okhttp.OkHttpClient.newCall");
        } catch (Throwable error) {
            LogSink.error("hook_okhttp2_proxy", error);
        }
    }

    private static void applyOkHttp2Proxy(Object client) {
        if (!ProxyHelper.isEnabled()) return;
        try {
            client.getClass().getMethod("setProxy", Proxy.class).invoke(client, new Object[]{null});
            client.getClass().getMethod("setProxySelector", ProxySelector.class)
                    .invoke(client, ProxyHelper.selector());
        } catch (Throwable error) {
            if (!okhttp2FailureLogged) {
                okhttp2FailureLogged = true;
                LogSink.error("apply_okhttp2_proxy", error);
            }
        }
    }

    // ------------------------------------------------------------------ reflection helpers

    private static boolean call(Object target, String name, Class<?> type, Object[] args) {
        try {
            target.getClass().getMethod(name, type).invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
