package io.github.adkimsm.up366.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ProxyInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Applies the panel proxy to the WebView network stack.
 *
 * <p>WebView runs Chromium with its own network stack, so {@code ProxySelector} and the OkHttp
 * hooks do not reach it. Chromium offers exactly one supported, process-wide, live mechanism:
 * {@code ProxyController.setProxyOverride}. It is reached here in two ways, both without touching
 * a single Chromium class (which is what used to trap inside the WebView native glue and kill the
 * app):
 *
 * <ol>
 *   <li>the app's own {@code androidx.webkit.ProxyController}, when the APK bundles it;
 *   <li>otherwise the support-library glue from the WebView APK itself:
 *       {@link WebView#getWebViewClassLoader()} →
 *       {@code SupportLibReflectionUtil.createWebViewProviderFactory()} →
 *       {@code WebViewProviderFactoryBoundaryInterface#getProxyController()}.
 * </ol>
 *
 * <p>No {@code WebView.<init>}, {@code WebViewFactory} or {@code org.chromium.net.*} hook is
 * installed: merely creating a WebView cannot execute module code. The override is pushed when the
 * panel changes, shortly after startup when the proxy is already enabled, and again (bounded) when
 * an Activity comes to the foreground so a Chromium that started later still picks it up.
 *
 * <p>Chromium also reads the {@code http.proxyHost}/{@code https.proxyHost} system properties at
 * startup ({@code ProxyConfigServiceAndroid} → {@code ProxyChangeListener.getProperty()}), which
 * {@link ProxyHelper} already sets before any WebView exists; that covers the cold-start case even
 * if the override call is unavailable.
 */
final class WebViewProxy {
    /** In-app broadcast so every process of the target app applies a panel change immediately. */
    private static final String ACTION_PROXY_UPDATED = "io.github.adkimsm.up366.PROXY_UPDATED";

    private static final String GLUE_ENTRY =
            "org.chromium.support_lib_glue.SupportLibReflectionUtil";
    private static final String FACTORY_INTERFACE =
            "org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface";
    private static final String PROXY_INTERFACE =
            "org.chromium.support_lib_boundary.ProxyControllerBoundaryInterface";

    private static final String ANDROIDX_CONTROLLER = "androidx.webkit.ProxyController";
    private static final String ANDROIDX_CONFIG = "androidx.webkit.ProxyConfig";

    private static final Executor MAIN_EXECUTOR = new Executor() {
        @Override
        public void execute(Runnable command) {
            if (command == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                command.run();
                return;
            }
            new Handler(Looper.getMainLooper()).post(command);
        }
    };

    private static final int MAX_RESUME_PUSHES = 3;
    private static final int MAX_TRANSIENT_RETRIES = 4;

    private static volatile Context context;
    private static volatile String processName;
    private static volatile boolean installed;
    private static volatile boolean mainProcess;
    private static volatile BroadcastReceiver updateReceiver;

    private static volatile boolean desiredEnabled;
    private static volatile String desiredHost = "";
    private static volatile int desiredPort;

    private static volatile boolean applied;
    private static volatile boolean appliedEnabled;
    private static volatile String appliedHost = "";
    private static volatile int appliedPort;

    /** Chosen transport to the WebView proxy API, resolved lazily on the first push. */
    private static volatile Route route;
    private static volatile boolean routeUnavailable;
    private static volatile boolean legacyHooked;
    private static volatile boolean unsupportedLogged;
    private static volatile String lastError;

    private static int resumePushes;
    private static int transientRetries;
    private static volatile boolean forceNext;
    private static volatile boolean pushScheduled;

    private WebViewProxy() {}

    static void install(Context value, ClassLoader loader) {
        if (value == null) return;
        Context app = value.getApplicationContext();
        context = app != null ? app : value;
        mainProcess = isMainProcess(context);
        synchronized (WebViewProxy.class) {
            if (installed) return;
            installed = true;
            // Nothing provider-specific is installed here: merely creating a WebView must never
            // execute module code. The proxy route is resolved lazily on the first push, and the
            // deprecated android.net.Proxy hooks are only installed if no route exists at all.
            registerUpdateReceiver(context);
            LogSink.event("webview_proxy_hook", "hook", "proxy_controller",
                    "process", mainProcess ? "main" : "secondary");
        }
    }

    /**
     * Stores the desired state and schedules a push on the UI thread. Returns {@code false} only
     * for an unusable proxy address; the WebView result is reported by {@link #statusText()}.
     */
    static boolean apply(boolean enabled, String host, int port) {
        desiredEnabled = enabled;
        desiredHost = host == null ? "" : host;
        desiredPort = port;
        if (enabled && (desiredHost.isEmpty() || desiredPort <= 0 || desiredPort > 65535)) {
            return false;
        }
        if (!installed) return true;
        // A WebView lives in the main process (the renderer/sandbox process has no app WebView).
        // Pushing from the push service would only force Chromium to start there for nothing.
        if (!mainProcess) return true;
        if (routeUnavailable) return false;
        if (enabled || applied) schedulePush();
        return true;
    }

    /**
     * Re-asserts the override when the user actually brings an Activity to the front. Chromium may
     * not have been running yet when the startup push happened, and a WebView process restart
     * drops the process-wide override. Cheap, and touches only the public proxy API.
     */
    static void onForegroundActivity() {
        if (!installed || !desiredEnabled || routeUnavailable) return;
        // An Activity only exists in a UI process, so this is a cheap correctness net even if the
        // process-name check above guessed wrong.
        if (resumePushes >= MAX_RESUME_PUSHES) return;
        resumePushes++;
        forceNext = true;
        schedulePush(0L);
    }

    /** Human-readable state for the injected panel. Never starts Chromium on its own. */
    static String statusText() {
        if (!installed) return "未初始化";
        if (!mainProcess) return "非主进程，不适用";
        if (routeUnavailable) return "不可用：系统 WebView 未提供代理接口";
        if (!desiredEnabled) return "未启用";
        if (applied) {
            String via = route == null ? "" : "，" + route.name();
            return "已下发 " + appliedHost + ":" + appliedPort + via;
        }
        if (lastError != null) return "应用失败：" + lastError;
        return pushScheduled ? "正在应用…" : "待应用";
    }

    /**
     * Tells the other processes of the target app that the proxy configuration changed. They
     * re-read SharedPreferences and update their own Java stacks; only the main process owns a
     * WebView proxy controller.
     */
    static void notifyChanged(Context value) {
        Context ctx = value == null ? null : value.getApplicationContext();
        if (ctx == null) ctx = value;
        if (ctx == null) return;
        try {
            Intent intent = new Intent(ACTION_PROXY_UPDATED).setPackage(ctx.getPackageName());
            ctx.sendBroadcast(intent);
        } catch (Throwable error) {
            LogSink.error("webview_proxy_notify", error);
        }
    }

    /**
     * Registers a process-local receiver so a panel change in the UI process reaches every other
     * process of the target app (push/web/remote) without waiting for a restart. The broadcast is
     * package-scoped and not exported.
     */
    private static void registerUpdateReceiver(Context value) {
        Context ctx = value == null ? null : value.getApplicationContext();
        if (ctx == null) ctx = value;
        if (ctx == null) return;
        synchronized (WebViewProxy.class) {
            if (updateReceiver != null) return;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context source, Intent intent) {
                    if (intent == null || !ACTION_PROXY_UPDATED.equals(intent.getAction())) return;
                    Context local = source != null ? source : context;
                    if (local != null) {
                        ProxyHelper.apply(Config.proxyEnabled(local), Config.proxyHost(local),
                                Config.proxyPort(local));
                    }
                }
            };
            try {
                IntentFilter filter = new IntentFilter(ACTION_PROXY_UPDATED);
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    ctx.registerReceiver(receiver, filter);
                }
                updateReceiver = receiver;
                LogSink.event("webview_proxy_receiver", "status", "registered");
            } catch (Throwable error) {
                LogSink.error("webview_proxy_receiver", error);
            }
        }
    }

    /**
     * Records the process name reported by the loader. This is cheaper and more reliable than
     * {@link Application#getProcessName()} before {@code Application.attach()}, and it lets the
     * push service be excluded deterministically.
     */
    static void setProcessName(String name) {
        if (name != null && !name.isEmpty()) processName = name;
    }

    private static boolean isMainProcess(Context ctx) {
        String name = processName;
        if (name == null || name.isEmpty()) {
            try {
                if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
            } catch (Throwable ignored) {
                // Fall back to the conservative "this is the main process" answer.
            }
        }
        String pkg = null;
        if (ctx != null) {
            pkg = ctx.getPackageName();
        }
        if (name != null && !name.isEmpty()) {
            return pkg == null || name.equals(pkg);
        }
        return true;
    }

    private static void schedulePush() {
        // The first push may start Chromium and is deferred a little so it never lands inside
        // Application.attach()/onCreate(). Later panel changes are posted right away.
        schedulePush(applied ? 0L : 1_500L);
    }

    private static void schedulePush(long delay) {
        final long delayMs = Math.max(0L, delay);
        synchronized (WebViewProxy.class) {
            if (pushScheduled) return;
            pushScheduled = true;
        }
        try {
            // Always post: pushing may load Chromium, which must never run inside
            // Application.attach() or inside a broadcast receiver.
            boolean posted = new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (WebViewProxy.class) {
                        pushScheduled = false;
                    }
                    try {
                        push();
                    } catch (Throwable error) {
                        LogSink.error("webview_proxy_push", error);
                    }
                }
            }, delayMs);
            if (!posted) throw new IllegalStateException("handler_rejected");
        } catch (Throwable error) {
            synchronized (WebViewProxy.class) {
                pushScheduled = false;
            }
            LogSink.error("webview_proxy_schedule", error);
        }
    }

    /** Must run on the UI thread. Applies the desired state only when it actually changed. */
    private static void push() {
        boolean enabled = desiredEnabled;
        String host = desiredHost;
        int port = desiredPort;

        if (!enabled) {
            if (!applied) return;
            Route active = route;
            if (active == null) return;
            try {
                active.clear();
                applied = false;
                appliedEnabled = false;
                appliedHost = "";
                appliedPort = 0;
                lastError = null;
                LogSink.event("webview_proxy_cleared", "path", active.name());
            } catch (Throwable error) {
                lastError = describe(error);
                LogSink.error("webview_proxy_clear", error);
            }
            return;
        }

        boolean forced = forceNext;
        forceNext = false;
        if (!forced && applied && appliedEnabled && appliedPort == port && appliedHost.equals(host)) {
            return;
        }
        Route active = route;
        if (active == null) {
            active = ensureRoute();
            if (active == null) {
                if (routeUnavailable) {
                    logUnsupported();
                } else {
                    retryLater("route");
                }
                return;
            }
        }

        try {
            active.set(host, port);
            applied = true;
            appliedEnabled = true;
            appliedHost = host;
            appliedPort = port;
            lastError = null;
            transientRetries = 0;
            LogSink.event("webview_proxy_requested", "path", active.name(),
                    "host", host, "port", Integer.toString(port),
                    "rules", proxyUrl(host, port), "forced", forced ? "true" : "false");
        } catch (Throwable error) {
            lastError = describe(error);
            LogSink.error("webview_proxy_set", error);
            retryLater("set");
        }
    }

    /** Bounded retry for a transient failure; never floods the main thread. */
    private static void retryLater(String reason) {
        if (transientRetries >= MAX_TRANSIENT_RETRIES) {
            LogSink.event("webview_proxy_retry", "status", "gave_up",
                    "reason", reason, "attempts", Integer.toString(transientRetries));
            return;
        }
        transientRetries++;
        LogSink.event("webview_proxy_retry", "status", "scheduled",
                "reason", reason, "attempt", Integer.toString(transientRetries));
        schedulePush(3_000L);
    }

    // ------------------------------------------------------------------ route resolution

    /**
     * Resolves one working transport to {@code ProxyController}. The app's own androidx.webkit is
     * preferred because every class then comes from a single class loader; the raw glue is used
     * when the APK does not bundle that API.
     */
    private static Route ensureRoute() {
        Route current = route;
        if (current != null) return current;
        synchronized (WebViewProxy.class) {
            if (route != null) return route;
            if (routeUnavailable) return null;

            boolean permanent = true;
            try {
                Route androidX = createAndroidxRoute();
                if (androidX != null) {
                    route = androidX;
                    LogSink.event("webview_proxy_glue", "status", "ok", "path", androidX.name(),
                            "provider", providerVersion());
                    return route;
                }
            } catch (Throwable error) {
                permanent = isPermanent(error);
                LogSink.event("webview_proxy_glue", "status", "androidx_failed",
                        "path", "androidx", "error_type", error.getClass().getName(),
                        "detail", describe(error));
            }

            try {
                Route glue = createGlueRoute();
                if (glue != null) {
                    route = glue;
                    LogSink.event("webview_proxy_glue", "status", "ok", "path", glue.name(),
                            "provider", providerVersion());
                    return route;
                }
            } catch (Throwable error) {
                permanent = permanent && isPermanent(error);
                LogSink.event("webview_proxy_glue", "status", "glue_failed",
                        "path", "support_lib_glue", "error_type", error.getClass().getName(),
                        "detail", describe(error));
            }

            if (permanent) {
                routeUnavailable = true;
                // Last resort for pre-Chromium WebViews and vendor builds.
                hookLegacyProxy();
            }
            return null;
        }
    }

    /**
     * {@code androidx.webkit.ProxyController.getInstance().setProxyOverride(ProxyConfig, ...)}.
     * The app bundles androidx.webkit (its own code calls {@code WebViewCompat}), so this usually
     * exists and keeps every reflective call inside the app's class loader.
     */
    private static Route createAndroidxRoute() throws Throwable {
        if (context == null) return null;
        ClassLoader loader = context.getClassLoader();
        if (loader == null) return null;
        final Class<?> controllerClass;
        try {
            controllerClass = Class.forName(ANDROIDX_CONTROLLER, false, loader);
        } catch (ClassNotFoundException missing) {
            // androidx.webkit without ProxyController (older than 1.5.0).
            return null;
        }
        final Class<?> configClass = Class.forName(ANDROIDX_CONFIG, false, loader);
        final Class<?> builderClass = Class.forName(ANDROIDX_CONFIG + "$Builder", false, loader);

        final Object controller = controllerClass.getMethod("getInstance").invoke(null);
        if (controller == null) return null;

        final java.lang.reflect.Constructor<?> builderCtor = builderClass.getConstructor();
        final Method addProxyRule = builderClass.getMethod("addProxyRule", String.class);
        final Method addBypassRule = builderClass.getMethod("addBypassRule", String.class);
        final Method build = builderClass.getMethod("build");
        final Method set = controllerClass.getMethod("setProxyOverride",
                configClass, Executor.class, Runnable.class);
        final Method clear = controllerClass.getMethod("clearProxyOverride",
                Executor.class, Runnable.class);

        return new Route("androidx") {
            @Override
            void set(String host, int port) throws Throwable {
                Object builder = builderCtor.newInstance();
                addProxyRule.invoke(builder, proxyUrl(host, port));
                for (String bypass : BYPASS) addBypassRule.invoke(builder, bypass);
                Object config = build.invoke(builder);
                set.invoke(controller, config, MAIN_EXECUTOR,
                        callback("webview_proxy_applied", host, port, "androidx"));
            }

            @Override
            void clear() throws Throwable {
                clear.invoke(controller, MAIN_EXECUTOR,
                        callback("webview_proxy_cleared", "", 0, "androidx"));
            }
        };
    }

    /**
     * Raw support-library glue from the WebView APK. Mirrors what androidx.webkit does internally:
     * the glue factory is an {@link InvocationHandler}, and so is its proxy controller.
     */
    private static Route createGlueRoute() throws Throwable {
        final ClassLoader loader = webViewClassLoader();
        if (loader == null) {
            throw new IllegalStateException("webview_class_loader_unavailable");
        }

        Class<?> entry = Class.forName(GLUE_ENTRY, false, loader);
        Object factory = entry.getDeclaredMethod("createWebViewProviderFactory").invoke(null);
        if (!(factory instanceof InvocationHandler)) {
            throw new IllegalStateException("glue factory is not an InvocationHandler");
        }

        Class<?> factoryInterface = Class.forName(FACTORY_INTERFACE, false, loader);
        Object controller = ((InvocationHandler) factory)
                .invoke(null, factoryInterface.getMethod("getProxyController"), null);
        if (!(controller instanceof InvocationHandler)) {
            throw new IllegalStateException("proxy controller is not an InvocationHandler");
        }

        Class<?> proxyInterface = Class.forName(PROXY_INTERFACE, false, loader);
        final Method set = proxyInterface.getMethod("setProxyOverride", String[][].class,
                String[].class, Runnable.class, Executor.class);
        final Method clear = proxyInterface.getMethod("clearProxyOverride", Runnable.class,
                Executor.class);
        final InvocationHandler handler = (InvocationHandler) controller;

        return new Route("glue") {
            @Override
            void set(String host, int port) throws Throwable {
                set.invoke(handler, new String[][]{{null, proxyUrl(host, port)}}, BYPASS,
                        callback("webview_proxy_applied", host, port, "glue"), MAIN_EXECUTOR);
            }

            @Override
            void clear() throws Throwable {
                clear.invoke(handler, callback("webview_proxy_cleared", "", 0, "glue"),
                        MAIN_EXECUTOR);
            }
        };
    }

    private static final String[] BYPASS = new String[]{"localhost", "127.0.0.1", "[::1]"};

    /** One resolved transport to the WebView proxy API. */
    private abstract static class Route {
        private final String name;

        Route(String name) {
            this.name = name;
        }

        final String name() {
            return name;
        }

        abstract void set(String host, int port) throws Throwable;

        abstract void clear() throws Throwable;
    }

    /** Missing glue is permanent; a not-yet-initialised provider is not. */
    private static boolean isPermanent(Throwable error) {
        return error instanceof ClassNotFoundException
                || error instanceof NoSuchMethodException
                || error instanceof NoClassDefFoundError;
    }

    private static ClassLoader webViewClassLoader() {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            // Public API since API 28; it loads the WebView provider, so only call it while a
            // proxy is being pushed.
            return WebView.getWebViewClassLoader();
        } catch (Throwable error) {
            LogSink.event("webview_proxy_loader", "status", "failed",
                    "error_type", error.getClass().getName(),
                    "detail", String.valueOf(error.getMessage()));
            return null;
        }
    }

    private static String providerVersion() {
        try {
            android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
            return info == null ? "unknown" : (info.packageName + "/" + info.versionName);
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static String proxyUrl(String host, int port) {
        String value = host == null ? "" : host.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("socks://") || value.startsWith("socks4://")
                || value.startsWith("socks5://")) {
            return value;
        }
        return "http://" + value + ":" + port;
    }

    private static Runnable callback(final String event, final String host, final int port,
            final String path) {
        return new Runnable() {
            @Override
            public void run() {
                LogSink.event(event, "path", path, "host", host, "port", Integer.toString(port),
                        "status", "callback");
            }
        };
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : (": " + message));
    }

    private static void logUnsupported() {
        if (unsupportedLogged) return;
        unsupportedLogged = true;
        LogSink.event("webview_proxy_unsupported", "status", "no_path",
                "detail", "neither androidx.webkit nor support-lib glue");
    }

    // ------------------------------------------------------------------ legacy WebView

    /**
     * Very old providers read the deprecated {@code android.net.Proxy} helpers instead of the
     * Chromium service. They are plain static getters, cheap to hook, and only installed when no
     * modern route exists.
     */
    private static void hookLegacyProxy() {
        synchronized (WebViewProxy.class) {
            if (legacyHooked) return;
            legacyHooked = true;
        }
        Class<?> proxy;
        try {
            proxy = Class.forName("android.net.Proxy");
        } catch (Throwable error) {
            return;
        }
        hookLegacyValue(proxy, "getDefaultHost", true);
        hookLegacyValue(proxy, "getHost", true);
        hookLegacyValue(proxy, "getDefaultPort", false);
        hookLegacyValue(proxy, "getPort", false);
        try {
            XposedBridge.hookAllMethods(proxy, "getProxy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!desiredEnabled || desiredHost.isEmpty() || desiredPort <= 0) return;
                    Class<?> returnType = ((Method) param.method).getReturnType();
                    if (returnType != ProxyInfo.class) return;
                    param.setResult(ProxyInfo.buildDirectProxy(desiredHost, desiredPort));
                }
            });
            LogSink.event("webview_proxy_hook", "hook", "android.net.Proxy.getProxy");
        } catch (Throwable ignored) {
            // Method not present on this platform.
        }
    }

    private static void hookLegacyValue(Class<?> proxy, String name, final boolean host) {
        try {
            XposedBridge.hookAllMethods(proxy, name, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!desiredEnabled || desiredHost.isEmpty() || desiredPort <= 0) return;
                    if (host) {
                        param.setResult(desiredHost);
                    } else {
                        param.setResult(desiredPort);
                    }
                }
            });
            LogSink.event("webview_proxy_hook", "hook", "android.net.Proxy." + name);
        } catch (Throwable ignored) {
            // Method not present on this platform.
        }
    }
}
