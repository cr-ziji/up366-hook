package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target-process configuration store. Values live in the target app's own private
 * {@code SharedPreferences} and are read and written only from inside the hooked target process
 * (the Hook code and the injected panel). No cross-process or exported surface is involved.
 *
 * <p>Reads are cheap but frequent, so they are cached for a short window and the cache is dropped
 * whenever the panel writes a value through {@link #set}.
 */
final class Config {
    static final String LOG_FILE = "log_file";

    static final String PROXY_ENABLED = "proxy_enabled";
    static final String PROXY_HOST = "proxy_host";
    static final String PROXY_PORT = "proxy_port";
    static final String TRUST_ALL_CERTS = "trust_all_certs";
    static final String NO_NETWORK_TIMEOUT = "no_network_timeout";

    static final String WEBVIEW_DEBUG_ENABLED = "webview_debug_enabled";
    static final String WEBVIEW_DEBUG_PORT = "webview_debug_port";

    static final String FLOAT_X = "float_x";
    static final String FLOAT_Y = "float_y";

    static final String PREFS = "p0_flags";

    static final String[] KEYS = {
            LOG_FILE, PROXY_ENABLED, TRUST_ALL_CERTS, NO_NETWORK_TIMEOUT, WEBVIEW_DEBUG_ENABLED
    };

    private static final Object LOCK = new Object();
    private static final long CACHE_TTL_MS = 1000L;
    private static final Map<String, Boolean> VALUES = new LinkedHashMap<>();
    private static boolean loaded;
    private static long loadedAt;

    private Config() {}

    /** Drops the cached flag values so the next read reloads the SharedPreferences. */
    static void invalidate() {
        synchronized (LOCK) {
            loaded = false;
            loadedAt = 0L;
        }
    }

    static boolean enabled(Context context, String key) {
        if (context == null) return defaultValue(key);
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (loaded && now - loadedAt < CACHE_TTL_MS) {
                Boolean cached = VALUES.get(key);
                if (cached != null) return cached;
            }
        }
        Map<String, Boolean> values = read(context);
        synchronized (LOCK) {
            VALUES.putAll(values);
            loaded = true;
            loadedAt = now;
        }
        return values.containsKey(key) ? values.get(key) : defaultValue(key);
    }

    static void set(Context context, String key, boolean value) {
        if (context == null || !isKnownKey(key)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", key, "value", Boolean.toString(value),
                "status", "ok");
    }

    /** TEST ONLY: trust every certificate chain without importing a CA. */
    static boolean trustAllCerts(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(TRUST_ALL_CERTS, false);
    }

    static void setTrustAllCerts(Context context, boolean value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(TRUST_ALL_CERTS, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", TRUST_ALL_CERTS, "value", Boolean.toString(value),
                "status", "ok");
    }

    // Proxy
    static boolean proxyEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PROXY_ENABLED, false);
    }

    static void setProxyEnabled(Context context, boolean value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PROXY_ENABLED, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", PROXY_ENABLED, "value", Boolean.toString(value),
                "status", "ok");
    }

    static String proxyHost(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PROXY_HOST, "");
    }

    static void setProxyHost(Context context, String value) {
        if (context == null) return;
        String v = value == null ? "" : value.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PROXY_HOST, v).apply();
        invalidate();
        LogSink.event("config_updated", "key", PROXY_HOST, "status", "ok");
    }

    static int proxyPort(Context context) {
        if (context == null) return 0;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PROXY_PORT, 0);
    }

    static void setProxyPort(Context context, int value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(PROXY_PORT, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", PROXY_PORT, "status", "ok");
    }

    // WebView debug
    static boolean webviewDebugEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(WEBVIEW_DEBUG_ENABLED, false);
    }

    static void setWebviewDebugEnabled(Context context, boolean value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(WEBVIEW_DEBUG_ENABLED, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", WEBVIEW_DEBUG_ENABLED, "value", Boolean.toString(value),
                "status", "ok");
    }

    static int webviewDebugPort(Context context) {
        if (context == null) return 9222;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(WEBVIEW_DEBUG_PORT, 9222);
    }

    static void setWebviewDebugPort(Context context, int value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(WEBVIEW_DEBUG_PORT, value).apply();
        invalidate();
        LogSink.event("config_updated", "key", WEBVIEW_DEBUG_PORT, "status", "ok");
    }

    /** Saved floating-button x offset in px, or {@code fallback} when never dragged. */
    static int floatX(Context context, int fallback) {
        if (context == null) return fallback;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(FLOAT_X, fallback);
    }

    /** Saved floating-button y offset in px, or {@code fallback} when never dragged. */
    static int floatY(Context context, int fallback) {
        if (context == null) return fallback;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(FLOAT_Y, fallback);
    }

    static void setFloatPosition(Context context, int x, int y) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(FLOAT_X, x).putInt(FLOAT_Y, y).apply();
        LogSink.event("config_updated", "key", "float_position", "status", "ok");
    }

    static boolean defaultValue(String key) {
        // Large resource packages over a slow link should wait instead of timing out by default.
        return NO_NETWORK_TIMEOUT.equals(key);
    }

    static boolean isKnownKey(String key) {
        for (String candidate : KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    private static Map<String, Boolean> read(Context context) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        try {
            SharedPreferences preferences =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String key : KEYS) {
                values.put(key, preferences.getBoolean(key, defaultValue(key)));
            }
        } catch (Throwable error) {
            for (String key : KEYS) values.put(key, defaultValue(key));
        }
        return values;
    }
}
