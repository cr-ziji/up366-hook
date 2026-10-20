package io.github.adkimsm.up366.hook;

import android.content.Context;
import android.webkit.WebView;

/** Applies the persisted WebView remote-debugging switch at process start. */
final class WebViewRecorder {
    private WebViewRecorder() {}

    static void install(Context context) {
        if (context == null || !Config.webviewDebugEnabled(context)) return;
        try {
            WebView.setWebContentsDebuggingEnabled(true);
            LogSink.event("webview_debug_enabled", "port",
                    Integer.toString(Config.webviewDebugPort(context)));
        } catch (Throwable error) {
            LogSink.error("webview_debug_install", error);
        }
    }
}
