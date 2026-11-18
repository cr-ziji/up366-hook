package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.webkit.WebView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Applies WebView remote debugging and bypasses WebView SSL errors while trust-all is enabled. */
final class WebViewRecorder {
    private WebViewRecorder() {}

    static void install(Context context, ClassLoader classLoader) {
        applyDebugSetting(context);
        hookSslErrors(classLoader);
    }

    private static void applyDebugSetting(Context context) {
        if (context == null || !Config.webviewDebugEnabled(context)) return;
        try {
            WebView.setWebContentsDebuggingEnabled(true);
            LogSink.event("webview_debug_enabled", "port",
                    Integer.toString(Config.webviewDebugPort(context)));
        } catch (Throwable error) {
            LogSink.error("webview_debug_install", error);
        }
    }

    /**
     * WebView owns a separate TLS stack, so the Java/OkHttp trust overrides do not reach it.
     * When trust-all is enabled, turn the default "cancel" outcome into "proceed".
     *
     * <p>{@code SslErrorHandler.cancel} is abstract on current platforms; the implementation lives
     * in the WebView APK. Hooking an abstract method is skipped on purpose: it has no code to
     * patch and would only put the hook engine into an unsupported state.
     */
    private static void hookSslErrors(ClassLoader classLoader) {
        try {
            Class<?> handler = XposedHelpers.findClass("android.webkit.SslErrorHandler", classLoader);
            java.lang.reflect.Method cancel = null;
            try {
                cancel = handler.getDeclaredMethod("cancel");
            } catch (Throwable ignored) {
                // No such method on this platform.
            }
            if (cancel == null || java.lang.reflect.Modifier.isAbstract(cancel.getModifiers())) {
                LogSink.event("trust_all_hook", "hook", "SslErrorHandler.cancel",
                        "status", "skipped_abstract");
                return;
            }
            XposedHelpers.findAndHookMethod(handler, "cancel", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!TrustAllCerts.isEnabled()) return;
                    try {
                        XposedHelpers.callMethod(param.thisObject, "proceed");
                        param.setResult(null);
                    } catch (Throwable error) {
                        LogSink.error("webview_ssl_proceed", error);
                    }
                }
            });
            LogSink.event("trust_all_hook", "hook", "SslErrorHandler.cancel");
        } catch (Throwable error) {
            LogSink.error("hook_webview_ssl_error", error);
        }
    }
}
