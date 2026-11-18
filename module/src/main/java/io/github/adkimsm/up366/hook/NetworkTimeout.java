package io.github.adkimsm.up366.hook;

import android.content.Context;

import java.io.RandomAccessFile;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.URLConnection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Extends timeouts for large downloads (resource packages) without making every other request in
 * the app wait forever.
 *
 * <p>Only requests that look like file downloads are affected, either by URL (archive extension or
 * "download") or by an {@code Accept} header asking for zip/octet-stream. For those requests the
 * read timeout is extended to treat a long silence as the only failure, and the connect timeout is
 * raised. All other traffic keeps the app's own timeouts, so a failed heartbeat or login can still
 * surface an error instead of hanging the UI.
 */
final class NetworkTimeout {
    /**
     * Idle read timeout for download requests. This is not the total transfer time: a socket read
     * timeout only limits how long one read may wait for new bytes, so a slow but steady download
     * can run for hours without hitting it.
     *
     * <p>A finite value is deliberate. With "wait forever", a server that has already sent every
     * byte but never terminates the response cleanly (missing length/terminator, half-closed
     * connection) leaves the download thread blocked in the final read forever: no success and no
     * error callback, so the app looks frozen right after a slow download "finishes". Ten minutes
     * without a single byte is treated as a dead link.
     */
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 10 * 60 * 1000;
    /**
     * Connect timeout for download requests. On a bad mobile link the TCP handshake may only
     * complete after the kernel's SYN retries, which is roughly two minutes; a shorter timeout
     * aborts such a download before a single byte is transferred.
     */
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 180_000;

    /**
     * The "download info" request (file id -> URL/size/md5) runs before the transfer on the app's
     * shared HTTP client, whose read timeout is only 20 s. A two minute silence there fails the
     * download before the transfer starts, so this metadata stage gets a longer but still bounded
     * timeout. It is deliberately shorter than {@link #DOWNLOAD_READ_TIMEOUT_MS}: a stalled info
     * request should surface as an error, not leave the loading screen up for ten minutes.
     */
    private static final int METADATA_READ_TIMEOUT_MS = 3 * 60 * 1000;
    private static final int METADATA_CONNECT_TIMEOUT_MS = DOWNLOAD_CONNECT_TIMEOUT_MS;

    /**
     * The app guards every download with its own {@code WatchDog(60000, ...)}: when no download
     * state callback arrives for 60 s it reports "Download timeout" and abandons the wait, even
     * though bytes may still be flowing. Progress callbacks are emitted only when the integer
     * percentage changes, so a slow transfer of a large package easily exceeds 60 s between two
     * callbacks.
     *
     * <p>Keep the watchdog slightly above the idle read timeout: a stalled socket then fails
     * through its own error path first, while a merely slow download keeps running. It is also
     * fed from real body writes below, so continuing traffic never counts as inactivity.
     */
    private static final long DOWNLOAD_WATCHDOG_TIMEOUT_MS = DOWNLOAD_READ_TIMEOUT_MS + 2 * 60_000L;

    /** Marks the thread that is currently running a download request (OkHttp 3/4). */
    private static final ThreadLocal<Boolean> DOWNLOAD = new ThreadLocal<>();
    /** Marks the thread on which the app's download implementation builds its request. */
    private static final ThreadLocal<Boolean> DOWNLOAD_CALL = new ThreadLocal<>();
    /** Marks the thread that is writing a download body to its temporary file. */
    private static final ThreadLocal<Boolean> DOWNLOAD_WRITE = new ThreadLocal<>();
    /** Marks the thread that is fetching download metadata (file id -> URL/size/md5). */
    private static final ThreadLocal<Boolean> DOWNLOAD_METADATA = new ThreadLocal<>();

    private static final String METADATA_EXTRA = "up366.download.metadata";

    private static final Map<Object, Boolean> INTERCEPTOR_ADDED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** Requests created by the app's dedicated download client, keyed by request instance. */
    private static final Map<Object, Boolean> DOWNLOAD_REQUESTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** Requests that fetch download metadata and therefore get the shorter extended timeout. */
    private static final Map<Object, Boolean> METADATA_REQUESTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** Bounded set used to avoid spamming the log when a download is retried or chunked. */
    private static final Set<String> LOGGED_DOWNLOADS =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private static volatile Context context;
    private static volatile boolean installed;
    private static volatile boolean callTimeoutWarningLogged;
    private static volatile boolean watchDogLogged;
    private static volatile long lastWatchDogFeedAt;
    private static volatile Object activeWatchDog;
    private static volatile Method watchDogFeed;
    private static volatile Class<?> interceptorType;
    private static volatile Object interceptorInstance;
    private static volatile Class<?> requestType;

    private NetworkTimeout() {}

    static void install(Context value, ClassLoader classLoader) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        synchronized (NetworkTimeout.class) {
            if (installed) return;
            installed = true;
            hookSockets();
            hookSslSockets(classLoader);
            hookUrlConnectionTimeouts(classLoader);
            hookOkHttp(classLoader);
            hookDownloadCalls(classLoader);
            hookDownloadMetadata(classLoader);
            hookDownloadWatchDog(classLoader);
            hookDownloadWrites(classLoader);
        }
        LogSink.event("download_timeout_installed", "state", isEnabled() ? "on" : "off");
    }

    static boolean isEnabled() {
        Context current = context;
        return current != null && Config.enabled(current, Config.NO_NETWORK_TIMEOUT);
    }

    // ------------------------------------------------------------------ OkHttp 3/4

    private static void hookOkHttp(ClassLoader classLoader) {
        Class<?> builder;
        Class<?> interceptor;
        Class<?> chain;
        Class<?> request;
        try {
            builder = Class.forName("okhttp3.OkHttpClient$Builder", false, classLoader);
            interceptor = Class.forName("okhttp3.Interceptor", false, classLoader);
            chain = Class.forName("okhttp3.Interceptor$Chain", false, classLoader);
            request = Class.forName("okhttp3.Request", false, classLoader);
        } catch (Throwable ignored) {
            LogSink.event("download_timeout_hook", "hook", "okhttp3", "status", "absent");
            return;
        }
        interceptorType = interceptor;
        requestType = request;
        interceptorInstance = Proxy.newProxyInstance(classLoader, new Class<?>[]{interceptor},
                new DownloadInterceptor(chain, request));

        try {
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // The interceptor is always present and decides per request, so enabling the
                    // switch later still applies to clients built before it was switched on.
                    addInterceptor(param.thisObject);
                }
            });
            LogSink.event("download_timeout_hook", "hook", "okhttp3.OkHttpClient$Builder.build");
        } catch (Throwable error) {
            LogSink.error("hook_okhttp_build", error);
        }

        // The client-level call timeout is applied when the call object is created, before any
        // interceptor runs. Lift it only for download calls; other calls keep the app's value so a
        // stalled follow-up request (progress report, next item, login refresh) still fails and the
        // UI can react instead of waiting forever.
        try {
            Class<?> client = Class.forName("okhttp3.OkHttpClient", false, classLoader);
            XposedHelpers.findAndHookMethod(client, "newCall", request, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isEnabled()) return;
                    if (param.args == null || param.args.length != 1) return;
                    Object value = param.args[0];
                    // The app's download implementation is the authoritative marker; the URL
                    // heuristic stays as a fallback for other download paths.
                    if (Boolean.TRUE.equals(DOWNLOAD_CALL.get()) || isDownloadRequest(value)) {
                        markDownloadRequest(value);
                        clearCallTimeout(param.getResult());
                        return;
                    }
                    // Download metadata (file id -> URL/size/md5) is a normal API call on the
                    // shared client, so it needs its own marker: the shared client's 20 s read
                    // timeout would otherwise kill the download before it starts.
                    if (Boolean.TRUE.equals(DOWNLOAD_METADATA.get()) || isMetadataRequest(value)) {
                        markMetadataRequest(value);
                        clearCallTimeout(param.getResult());
                    }
                }
            });
            LogSink.event("download_timeout_hook", "hook", "okhttp3.OkHttpClient.newCall");
        } catch (Throwable error) {
            // OkHttp 2 has no call timeout at all, and some builds rewrite the method.
            LogSink.error("hook_okhttp_new_call", error);
        }
    }

    /** Removes the whole-call deadline from a single call, leaving other calls untouched. */
    private static void clearCallTimeout(Object call) {
        if (call == null) return;
        try {
            Method getter = call.getClass().getMethod("timeout");
            Object timeout = getter.invoke(call);
            if (timeout == null) return;
            timeout.getClass().getMethod("timeout", long.class, TimeUnit.class)
                    .invoke(timeout, 0L, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            if (!callTimeoutWarningLogged) {
                callTimeoutWarningLogged = true;
                LogSink.error("download_timeout_clear_call_timeout", error);
            }
        }
    }

    private static void addInterceptor(Object builder) {
        Object interceptor = interceptorInstance;
        Class<?> type = interceptorType;
        if (interceptor == null || type == null) return;
        synchronized (INTERCEPTOR_ADDED) {
            if (INTERCEPTOR_ADDED.containsKey(builder)) return;
            INTERCEPTOR_ADDED.put(builder, Boolean.TRUE);
        }
        try {
            // newBuilder() clones copy our interceptor along, so do not stack another copy on top.
            Object existing = builder.getClass().getMethod("interceptors").invoke(builder);
            if (existing instanceof List) {
                for (Object item : (List<?>) existing) {
                    if (item == interceptor) return;
                }
            }
            builder.getClass().getMethod("addInterceptor", type).invoke(builder, interceptor);
        } catch (Throwable error) {
            LogSink.error("add_download_interceptor", error);
        }
    }

    /** Applies the per-request timeout only when the request looks like a large file download. */
    private static final class DownloadInterceptor implements InvocationHandler {
        private final Method proceed;
        private final Method request;
        private final Method withReadTimeout;
        private final Method withConnectTimeout;

        DownloadInterceptor(Class<?> chain, Class<?> requestType) {
            Method proceed = null;
            Method request = null;
            Method withReadTimeout = null;
            Method withConnectTimeout = null;
            try {
                proceed = chain.getMethod("proceed", requestType);
                request = chain.getMethod("request");
            } catch (Throwable ignored) {
                // Leave null; invoke() falls back to a plain rejection.
            }
            try {
                withReadTimeout = chain.getMethod("withReadTimeout", int.class, TimeUnit.class);
            } catch (Throwable ignored) {
                // OkHttp versions without per-chain timeouts.
            }
            try {
                withConnectTimeout = chain.getMethod("withConnectTimeout", int.class, TimeUnit.class);
            } catch (Throwable ignored) {
                // OkHttp versions without per-chain timeouts.
            }
            this.proceed = proceed;
            this.request = request;
            this.withReadTimeout = withReadTimeout;
            this.withConnectTimeout = withConnectTimeout;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"intercept".equals(method.getName()) || args == null || args.length != 1) {
                return objectMethod(proxy, method, args);
            }
            if (proceed == null || request == null) {
                throw new IllegalStateException("unsupported OkHttp interceptor chain");
            }
            Object chain = args[0];
            Object value = request.invoke(chain);
            if (!isEnabled()) {
                return proceed(chain, value);
            }
            boolean download = isMarkedDownload(value) || isDownloadRequest(value);
            boolean metadata = !download && (isMarkedMetadata(value) || isMetadataRequest(value));
            if (!download && !metadata) {
                return proceed(chain, value);
            }
            int readTimeout = download ? DOWNLOAD_READ_TIMEOUT_MS : METADATA_READ_TIMEOUT_MS;
            int connectTimeout = download
                    ? DOWNLOAD_CONNECT_TIMEOUT_MS
                    : METADATA_CONNECT_TIMEOUT_MS;
            logDownload(download ? "okhttp" : "okhttp-metadata", requestUrl(value));
            Boolean previous = DOWNLOAD.get();
            DOWNLOAD.set(Boolean.TRUE);
            try {
                Object target = chain;
                if (withReadTimeout != null) {
                    target = withReadTimeout.invoke(target, readTimeout, TimeUnit.MILLISECONDS);
                }
                if (withConnectTimeout != null) {
                    target = withConnectTimeout.invoke(target, connectTimeout,
                            TimeUnit.MILLISECONDS);
                }
                return proceed(target, value);
            } finally {
                if (previous == null) DOWNLOAD.remove();
                else DOWNLOAD.set(previous);
            }
        }

        private Object proceed(Object chain, Object requestValue) throws Throwable {
            try {
                return proceed.invoke(chain, requestValue);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        private Object objectMethod(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() != Object.class) return null;
            if ("toString".equals(method.getName())) return "Up366DownloadInterceptor";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            return null;
        }
    }

    // ------------------------------------------------------------------ app download pipeline

    /**
     * Marks requests made by the app's own download client. Resource packages are served from
     * opaque URLs, so matching on the URL is not reliable; the download implementation
     * ({@code OKHttpDownloadImpl.downloadImpl}) is the one place that builds them.
     */
    private static void hookDownloadCalls(ClassLoader classLoader) {
        Class<?> impl = loadClass("com.up366.common.download.OKHttpDownloadImpl", classLoader);
        if (impl == null) {
            LogSink.event("download_timeout_hook", "hook", "OKHttpDownloadImpl", "status", "absent");
            return;
        }
        try {
            XposedBridge.hookAllMethods(impl, "downloadImpl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    DOWNLOAD_CALL.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    DOWNLOAD_CALL.remove();
                }
            });
            LogSink.event("download_timeout_hook", "hook", "OKHttpDownloadImpl.downloadImpl");
        } catch (Throwable error) {
            LogSink.error("hook_download_impl", error);
        }
    }

    private static void markDownloadRequest(Object request) {
        if (request == null) return;
        DOWNLOAD_REQUESTS.put(request, Boolean.TRUE);
    }

    private static boolean isMarkedDownload(Object request) {
        return request != null && DOWNLOAD_REQUESTS.containsKey(request);
    }

    /**
     * Marks the metadata requests that precede a download. They are ordinary API calls on the
     * shared client (20 s read timeout), yet a slow network can leave them silent for minutes, so
     * the whole download would fail before the file transfer even starts.
     *
     * <p>The scope is derived from the call sites instead of the URL alone, because the same
     * client is used for unrelated requests that must keep their own timeouts.
     */
    private static void hookDownloadMetadata(ClassLoader classLoader) {
        int hooked = 0;
        Class<?> helper = loadClass("com.up366.mobile.book.helper.ContentDownloadHelper", classLoader);
        if (helper != null) {
            for (Method method : helper.getDeclaredMethods()) {
                if (method.getDeclaringClass() != helper) continue;
                if (hookMetadataScope(method)) hooked++;
            }
        }
        Class<?> bookMgr = loadClass("com.up366.mobile.book.BookMgr", classLoader);
        if (bookMgr != null) {
            for (String name : new String[]{"fetchFileInfo", "fetchFileInfoBatch"}) {
                try {
                    for (Method method : bookMgr.getDeclaredMethods()) {
                        if (!name.equals(method.getName())) continue;
                        if (hookMetadataScope(method)) hooked++;
                    }
                } catch (Throwable ignored) {
                    // The name may not exist in this app version.
                }
            }
        }
        if (hooked == 0) {
            LogSink.event("download_timeout_hook", "hook", "download_metadata", "status", "absent");
        } else {
            LogSink.event("download_timeout_hook", "hook", "download_metadata",
                    "methods", Integer.toString(hooked));
        }
    }

    /** Marks the current thread while a metadata-fetching method runs, restoring any outer state. */
    private static boolean hookMetadataScope(Method method) {
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra(METADATA_EXTRA, DOWNLOAD_METADATA.get());
                    if (isEnabled()) DOWNLOAD_METADATA.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object previous = param.getObjectExtra(METADATA_EXTRA);
                    if (previous == null) DOWNLOAD_METADATA.remove();
                    else DOWNLOAD_METADATA.set((Boolean) previous);
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markMetadataRequest(Object request) {
        if (request == null) return;
        METADATA_REQUESTS.put(request, Boolean.TRUE);
    }

    private static boolean isMarkedMetadata(Object request) {
        return request != null && METADATA_REQUESTS.containsKey(request);
    }

    /**
     * Raises the app's per-download watchdog from 60 s to the same order of magnitude as the idle
     * read timeout. Without this, a slow download is abandoned after one minute of few (or no)
     * percentage progress even though it is still receiving data, which is what leaves the study
     * page stuck after a slow resource package download.
     */
    private static void hookDownloadWatchDog(ClassLoader classLoader) {
        Class<?> type = loadClass("com.up366.mobile.common.utils.WatchDog", classLoader);
        if (type == null) {
            LogSink.event("download_timeout_hook", "hook", "WatchDog", "status", "absent");
            return;
        }
        try {
            watchDogFeed = type.getMethod("feed");
        } catch (Throwable error) {
            LogSink.error("hook_watchdog_feed", error);
        }
        try {
            XposedHelpers.findAndHookConstructor(type, long.class, Runnable.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isEnabled() || param.args.length != 2) return;
                    Object value = param.args[0];
                    if (!(value instanceof Long)) return;
                    long timeout = (Long) value;
                    if (timeout <= 0 || timeout >= DOWNLOAD_WATCHDOG_TIMEOUT_MS) return;
                    param.args[0] = DOWNLOAD_WATCHDOG_TIMEOUT_MS;
                    if (!watchDogLogged) {
                        watchDogLogged = true;
                        LogSink.event("download_watchdog_extended",
                                "original_ms", Long.toString(timeout),
                                "timeout_ms", Long.toString(DOWNLOAD_WATCHDOG_TIMEOUT_MS));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isEnabled()) activeWatchDog = param.thisObject;
                }
            });
            XposedHelpers.findAndHookMethod(type, "cancel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (activeWatchDog == param.thisObject) activeWatchDog = null;
                }
            });
            LogSink.event("download_timeout_hook", "hook", "WatchDog");
        } catch (Throwable error) {
            LogSink.error("hook_watchdog", error);
        }
        hookWatchDogFiredLog(classLoader);
    }

    /** Logs when the app's own watchdog actually gives up, to tell a dead link from slow progress. */
    private static void hookWatchDogFiredLog(ClassLoader classLoader) {
        Class<?> manager = loadClass("com.up366.mobile.common.utils.MultiFileDownloadManager", classLoader);
        if (manager == null) return;
        try {
            XposedBridge.hookAllMethods(manager, "lambda$downloadSync$9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    LogSink.event("download_watchdog_fired", "target", "MultiFileDownloadManager");
                }
            });
        } catch (Throwable error) {
            LogSink.error("hook_watchdog_fired_log", error);
        }
    }

    /**
     * Feeds the app's watchdog whenever the download implementation writes received bytes to its
     * temporary file. The watchdog therefore measures real inactivity instead of the coarse
     * percentage progress, so a slow but steady transfer never trips it.
     */
    private static void hookDownloadWrites(ClassLoader classLoader) {
        Class<?> impl = loadClass("com.up366.common.download.OKHttpDownloadImpl", classLoader);
        if (impl != null) {
            try {
                XposedBridge.hookAllMethods(impl, "handleResponse", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        DOWNLOAD_WRITE.set(Boolean.TRUE);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        DOWNLOAD_WRITE.remove();
                    }
                });
            } catch (Throwable error) {
                LogSink.error("hook_download_response", error);
            }
        }
        try {
            XposedBridge.hookAllMethods(RandomAccessFile.class, "write", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (DOWNLOAD_WRITE.get() == null) return;
                    if (param.args == null || param.args.length != 3) return;
                    feedWatchDog();
                }
            });
            LogSink.event("download_timeout_hook", "hook", "download_write_feed");
        } catch (Throwable error) {
            LogSink.error("hook_download_write", error);
        }
    }

    /** Feeds the active download watchdog from real body writes, at most once per second. */
    private static void feedWatchDog() {
        Object dog = activeWatchDog;
        Method feed = watchDogFeed;
        if (dog == null || feed == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastWatchDogFeedAt < 1000L) return;
        lastWatchDogFeedAt = now;
        try {
            feed.invoke(dog);
        } catch (Throwable ignored) {
            // The watchdog may have been cancelled between the check and the call.
        }
    }

    // ------------------------------------------------------------------ URLConnection

    private static void hookUrlConnectionTimeouts(ClassLoader classLoader) {
        hookTimeoutSetters(URLConnection.class);
        String[] names = {
                "com.android.okhttp.internal.huc.DelegatingHttpsURLConnection",
                "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
                "com.android.okhttp.internal.huc.HttpsURLConnectionImpl",
                "okhttp3.internal.huc.OkHttpURLConnection"
        };
        for (String name : names) {
            Class<?> type = loadClass(name, classLoader);
            if (type != null) hookTimeoutSetters(type);
        }
    }

    private static void hookTimeoutSetters(Class<?> type) {
        try {
            XposedBridge.hookAllMethods(type, "setReadTimeout", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled() && isDownloadConnection(param.thisObject)) {
                        param.args[0] = DOWNLOAD_READ_TIMEOUT_MS;
                    }
                }
            });
        } catch (Throwable ignored) {
            // Not declared on this class.
        }
        try {
            XposedBridge.hookAllMethods(type, "setConnectTimeout", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isEnabled() || !isDownloadConnection(param.thisObject)) return;
                    Object value = param.args[0];
                    if (value instanceof Integer && (Integer) value > 0
                            && (Integer) value < DOWNLOAD_CONNECT_TIMEOUT_MS) {
                        param.args[0] = DOWNLOAD_CONNECT_TIMEOUT_MS;
                    }
                }
            });
        } catch (Throwable ignored) {
            // Not declared on this class.
        }
    }

    private static boolean isDownloadConnection(Object connection) {
        if (connection == null) return false;
        try {
            Object url = connection.getClass().getMethod("getURL").invoke(connection);
            String value = String.valueOf(url);
            if (isDownloadUrl(value)) {
                logDownload("urlconnection", value);
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the Accept header.
        }
        try {
            Object accept = connection.getClass().getMethod("getRequestProperty", String.class)
                    .invoke(connection, "Accept");
            if (!isDownloadType(String.valueOf(accept))) return false;
            logDownload("urlconnection", String.valueOf(connection));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ sockets

    /**
     * Catch-all for the download thread: OkHttp's connection setup runs on the thread that executes
     * the interceptor, so its socket gets the extended timeout even if the library bypasses the
     * chain timeout APIs.
     */
    private static void hookSockets() {
        XposedBridge.hookAllMethods(Socket.class, "setSoTimeout", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(DOWNLOAD.get())) {
                    param.args[0] = DOWNLOAD_READ_TIMEOUT_MS;
                }
            }
        });
        XposedBridge.hookAllMethods(Socket.class, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(DOWNLOAD.get())) return;
                if (param.args != null && param.args.length == 2
                        && param.args[1] instanceof Integer
                        && (Integer) param.args[1] > 0
                        && (Integer) param.args[1] < DOWNLOAD_CONNECT_TIMEOUT_MS) {
                    param.args[1] = DOWNLOAD_CONNECT_TIMEOUT_MS;
                }
            }
        });
        LogSink.event("download_timeout_hook", "hook", "Socket");
    }

    private static void hookSslSockets(ClassLoader classLoader) {
        String[] names = {
                "com.android.org.conscrypt.OpenSSLSocketImpl",
                "com.android.org.conscrypt.ConscryptEngineSocket",
                "org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl"
        };
        for (String name : names) {
            Class<?> type = loadClass(name, classLoader);
            if (type == null) continue;
            XposedBridge.hookAllMethods(type, "setSoTimeout", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(DOWNLOAD.get())) {
                        param.args[0] = DOWNLOAD_READ_TIMEOUT_MS;
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ detection helpers

    private static boolean isDownloadRequest(Object request) {
        if (request == null) return false;
        String url = requestUrl(request);
        if (isDownloadUrl(url)) return true;
        Class<?> type = requestType;
        if (type == null) type = request.getClass();
        try {
            Object headers = type.getMethod("headers").invoke(request);
            Object accept = headers.getClass().getMethod("get", String.class)
                    .invoke(headers, "Accept");
            if (!isDownloadType(String.valueOf(accept))) return false;
            logDownload("okhttp", url);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The app's file-info endpoints answer with the metadata needed before a download starts. */
    private static boolean isMetadataRequest(Object request) {
        return isMetadataUrl(requestUrl(request));
    }

    private static boolean isMetadataUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return url.toLowerCase(java.util.Locale.ROOT).contains("/fileinfo/");
    }

    private static String requestUrl(Object request) {        if (request == null) return "";
        Class<?> type = requestType;
        if (type == null) type = request.getClass();
        try {
            Object url = type.getMethod("url").invoke(request);
            return String.valueOf(url);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Records which request was actually treated as a download, to make mismatches visible. */
    private static void logDownload(String source, String url) {
        if (url == null || url.isEmpty() || "null".equals(url)) return;
        synchronized (LOGGED_DOWNLOADS) {
            if (LOGGED_DOWNLOADS.contains(url)) return;
            if (LOGGED_DOWNLOADS.size() >= 200) LOGGED_DOWNLOADS.clear();
            LOGGED_DOWNLOADS.add(url);
        }
        LogSink.event("download_timeout_match", "source", source, "url", url);
    }

    private static boolean isDownloadUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(".zip")
                || lower.contains(".7z")
                || lower.contains(".rar")
                || lower.contains(".tar")
                || lower.contains(".tgz")
                || lower.contains(".gz")
                || lower.contains("download")
                || lower.contains("resourcepack")
                || lower.contains("resource_package");
    }

    private static boolean isDownloadType(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("zip") || lower.contains("octet-stream");
    }

    // ------------------------------------------------------------------ misc

    private static Class<?> loadClass(String name, ClassLoader classLoader) {
        if (classLoader != null) {
            try {
                return Class.forName(name, false, classLoader);
            } catch (Throwable ignored) {
                // Fall through to the default loader.
            }
        }
        try {
            return Class.forName(name, false, NetworkTimeout.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
