package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.os.SystemClock;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Applies the configured HTTP proxy to every network stack the app can use from Java.
 *
 * <p>The key design point is that the switch is evaluated <em>per connection</em>, not when a
 * client is built. OkHttp 3/4 snapshots {@code ProxySelector.getDefault()} when a
 * {@code Builder} is created, so a module that only sets system properties or builds a fixed
 * selector at startup cannot react to the panel. Instead this class installs one dynamic selector
 * as the process default early and keeps it installed; it reads the current configuration on every
 * {@code select()} call. Clients built before or after the toggle therefore both follow the panel.
 *
 * <p>{@link URL#openConnection()} is additionally redirected to {@link URL#openConnection(Proxy)}
 * for http(s), which covers {@code HttpURLConnection} users that captured the previous selector
 * (third-party SDKs in this app do) and clients with an explicit {@code Proxy.NO_PROXY}.
 *
 * <p>WebView has its own native network stack and is handled separately by {@link WebViewProxy}.
 */
final class ProxyHelper {
    private static final long CACHE_TTL_MS = 1000L;

    private static final ProxySelector SELECTOR = new DynamicSelector();

    private static final Set<String> LOGGED_HOSTS =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private static volatile ProxySelector previous;
    private static volatile boolean selectorInstalled;
    private static volatile Context context;
    private static volatile Snapshot snapshot;

    private ProxyHelper() {}

    static void install(Context value, ClassLoader classLoader) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        synchronized (ProxyHelper.class) {
            if (!selectorInstalled) {
                selectorInstalled = true;
                try {
                    previous = ProxySelector.getDefault();
                    hookSelectorDefault();
                    ProxySelector.setDefault(SELECTOR);
                    LogSink.event("proxy_hook", "hook", "ProxySelector.setDefault");
                } catch (Throwable error) {
                    LogSink.error("proxy_selector_install", error);
                }
                hookUrlConnection();
            }
        }
        apply(Config.proxyEnabled(context), Config.proxyHost(context), Config.proxyPort(context));
    }

    /** The process-wide selector; OkHttp clients and URLConnection read it on every connection. */
    static ProxySelector selector() {
        return SELECTOR;
    }

    static Proxy proxy(String host, int port) {
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /**
     * Applies (or clears) the proxy for every Java stack. Cheap enough to call on every panel
     * change and at process start: it only rewrites properties and forwards the state to WebView.
     */
    static boolean apply(boolean enabled, String host, int port) {
        return apply(enabled, host, port, false);
    }

    /**
     * @param notify when true, broadcasts the new state so the target app's other processes apply
     *               it immediately. Startup calls pass {@code false}: each process reads the
     *               persisted configuration itself.
     */
    static boolean apply(boolean enabled, String host, int port, boolean notify) {
        boolean valid = host != null && !host.isEmpty() && port > 0 && port <= 65535;
        boolean on = enabled && valid;
        String safeHost = valid ? host : "";
        int safePort = valid ? port : 0;
        cache(on, safeHost, safePort);
        try {
            if (on) {
                setProperty("http.proxyHost", safeHost);
                setProperty("http.proxyPort", Integer.toString(safePort));
                setProperty("https.proxyHost", safeHost);
                setProperty("https.proxyPort", Integer.toString(safePort));
                setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");
            } else {
                clearProperty("http.proxyHost");
                clearProperty("http.proxyPort");
                clearProperty("https.proxyHost");
                clearProperty("https.proxyPort");
                clearProperty("http.nonProxyHosts");
            }
            boolean webview = WebViewProxy.apply(on, safeHost, safePort);
            LogSink.event("proxy_applied", "status", on ? "on" : "off",
                    "host", safeHost, "port", Integer.toString(safePort),
                    "stack", "okhttp,urlconnection",
                    "webview", webview ? "requested" : "unavailable");
            if (notify) WebViewProxy.notifyChanged(context);
            return true;
        } catch (Throwable error) {
            LogSink.error("apply_proxy", error);
            return false;
        }
    }

    /** Whether the panel currently has a usable proxy configured. Used by the OkHttp getters. */
    static boolean isEnabled() {
        Snapshot current = snapshot();
        return current.enabled;
    }

    private static void setProperty(String key, String value) {
        try {
            System.setProperty(key, value);
        } catch (Throwable error) {
            LogSink.error("proxy_property_" + key, error);
        }
    }

    private static void clearProperty(String key) {
        try {
            System.clearProperty(key);
        } catch (Throwable error) {
            LogSink.error("proxy_property_" + key, error);
        }
    }

    // ------------------------------------------------------------------ configuration snapshot

    private static void cache(boolean enabled, String host, int port) {
        snapshot = new Snapshot(enabled, host, port, SystemClock.uptimeMillis());
    }

    /** The panel writes SharedPreferences; this adds a 1 s snapshot so select() stays cheap. */
    private static Snapshot snapshot() {
        Snapshot current = snapshot;
        long now = SystemClock.uptimeMillis();
        if (current != null && now - current.at < CACHE_TTL_MS) return current;
        Context ctx = context;
        Snapshot fresh = ctx == null
                ? new Snapshot(false, "", 0, now)
                : new Snapshot(Config.proxyEnabled(ctx), Config.proxyHost(ctx),
                        Config.proxyPort(ctx), now);
        snapshot = fresh;
        return fresh;
    }

    private static final class Snapshot {
        final boolean enabled;
        final String host;
        final int port;
        final long at;

        Snapshot(boolean enabled, String host, int port, long at) {
            this.enabled = enabled && host != null && !host.isEmpty()
                    && port > 0 && port <= 65535;
            this.host = host == null ? "" : host;
            this.port = port;
            this.at = at;
        }
    }

    /**
     * One selector for the whole process. It never needs to be swapped when the panel changes:
     * disabled means "fall back to whatever selector the app originally had".
     */
    private static final class DynamicSelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            Snapshot current = snapshot();
            if (!current.enabled) {
                ProxySelector base = previous;
                if (base != null && base != this) {
                    try {
                        return base.select(uri);
                    } catch (Throwable ignored) {
                        // Fall through to a direct connection.
                    }
                }
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            if (uri == null || isTheProxyItself(uri, current) || isLoopback(uri.getHost())) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            String scheme = uri.getScheme();
            if (scheme != null && !"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)) {
                // Raw sockets / non-HTTP schemes cannot use an HTTP proxy.
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            return Collections.singletonList(proxy(current.host, current.port));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            LogSink.error("proxy_connect_failed", ioe);
        }
    }

    /** Prevents a request to the proxy endpoint itself from being sent through the proxy. */
    private static boolean isTheProxyItself(URI uri, Snapshot current) {
        if (uri == null || uri.getHost() == null) return false;
        if (!uri.getHost().equalsIgnoreCase(current.host)) return false;
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return port == current.port;
    }

    /** Local IPC (127.0.0.1, localhost, ::1) must stay direct; proxy tools usually listen there. */
    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        if (value.equals("localhost") || value.equals("::1") || value.equals("[::1]")) return true;
        if (value.startsWith("127.")) return true;
        return false;
    }

    // ------------------------------------------------------------------ default selector

    /**
     * Keep our selector installed even if the app replaces the process default later. The app's
     * selector is remembered as the fallback used while the panel proxy is off, so turning the
     * proxy off restores the app's original behaviour instead of silently keeping the proxy.
     */
    private static void hookSelectorDefault() {
        try {
            XposedHelpers.findAndHookMethod(ProxySelector.class, "setDefault", ProxySelector.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ProxySelector requested = (ProxySelector) param.args[0];
                            if (requested == SELECTOR) return;
                            previous = requested;
                            // Swallow the replacement so the dynamic selector stays authoritative.
                            param.setResult(null);
                            LogSink.event("proxy_selector_changed", "selector",
                                    requested == null ? "null" : requested.getClass().getName());
                        }
                    });
            LogSink.event("proxy_hook", "hook", "ProxySelector.setDefault(guard)");
        } catch (Throwable error) {
            LogSink.error("proxy_hook_selector_default", error);
        }
    }

    // ------------------------------------------------------------------ HttpURLConnection

    /**
     * OkHttp (and therefore Android's HttpURLConnection) resolves the proxy through a selector it
     * captured when its class/client was created. Clients created before the module was installed
     * would keep the old selector forever. Redirecting {@code openConnection()} to the explicit
     * proxy overload makes those connections follow the panel as well.
     */
    private static void hookUrlConnection() {
        try {
            XposedHelpers.findAndHookMethod(URL.class, "openConnection", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) return;
                    Object self = param.thisObject;
                    if (!(self instanceof URL)) return;
                    URL url = (URL) self;
                    String protocol = url.getProtocol();
                    if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                        return;
                    }
                    Snapshot current = snapshot();
                    if (!current.enabled) return;
                    if (isLoopback(url.getHost())) return;
                    try {
                        URLConnection connection = url.openConnection(
                                proxy(current.host, current.port));
                        param.setResult(connection);
                        logHost(url);
                    } catch (Throwable error) {
                        LogSink.error("proxy_urlconnection", error);
                    }
                }
            });
            LogSink.event("proxy_hook", "hook", "URL.openConnection");
        } catch (Throwable error) {
            LogSink.error("proxy_hook_urlconnection", error);
        }
        // Some SDKs pass Proxy.NO_PROXY explicitly to bypass the default selector.
        try {
            XposedHelpers.findAndHookMethod(URL.class, "openConnection", Proxy.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot current = snapshot();
                            if (!current.enabled) return;
                            Object self = param.thisObject;
                            if (self instanceof URL) {
                                URL url = (URL) self;
                                String protocol = url.getProtocol();
                                if (!"http".equalsIgnoreCase(protocol)
                                        && !"https".equalsIgnoreCase(protocol)) {
                                    return;
                                }
                                if (isLoopback(url.getHost())) return;
                            }
                            Object requested = param.args[0];
                            if (requested instanceof Proxy
                                    && ((Proxy) requested).type() == Proxy.Type.HTTP) {
                                return;
                            }
                            param.args[0] = proxy(current.host, current.port);
                        }
                    });
            LogSink.event("proxy_hook", "hook", "URL.openConnection(Proxy)");
        } catch (Throwable error) {
            LogSink.error("proxy_hook_urlconnection_proxy", error);
        }
    }

    /** Logs one line per destination host so a capture test can see the proxy is being used. */
    private static void logHost(URL url) {
        String host = url.getHost();
        if (host == null || host.isEmpty()) return;
        String key = host.toLowerCase(Locale.ROOT);
        synchronized (LOGGED_HOSTS) {
            if (LOGGED_HOSTS.contains(key)) return;
            if (LOGGED_HOSTS.size() >= 100) LOGGED_HOSTS.clear();
            LOGGED_HOSTS.add(key);
        }
        LogSink.event("proxy_urlconnection_used", "host", host);
    }
}
