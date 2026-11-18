package io.github.adkimsm.up366.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class A366Module implements IXposedHookLoadPackage {
    public static final String TARGET_PACKAGE = "com.up366.mobile";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        LogSink.init(lpparam.classLoader);
        LogSink.setProcessRole("target_hook");
        WebViewProxy.setProcessName(lpparam.processName);
        LogSink.event("module_loaded", "package", lpparam.packageName, "process", lpparam.processName);
        hookApplicationAttach(lpparam.classLoader);
        hookApplicationCreate(lpparam.classLoader);
        hookInstrumentationCreate(lpparam.classLoader);
        scheduleFallback(lpparam.classLoader);
    }

    private static void hookApplicationAttach(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    install(param.thisObject, (Context) param.args[0], classLoader, "application_attach");
                }
            });
            LogSink.event("hook_registered", "hook", "Application.attach");
        } catch (Throwable error) {
            LogSink.error("hook_application_attach", error);
        }
    }

    private static void hookApplicationCreate(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    install(param.thisObject, (Application) param.thisObject, classLoader, "application_on_create");
                }
            });
            LogSink.event("hook_registered", "hook", "Application.onCreate");
        } catch (Throwable error) {
            LogSink.error("hook_application_create", error);
        }
    }

    private static void hookInstrumentationCreate(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Application application = (Application) param.args[0];
                    install(application, application, classLoader, "instrumentation_call_application_on_create");
                }
            });
            LogSink.event("hook_registered", "hook", "Instrumentation.callApplicationOnCreate");
        } catch (Throwable error) {
            LogSink.error("hook_instrumentation_create", error);
        }
    }

    private static void install(Object application, Context context, ClassLoader classLoader, String source) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        LogSink.event("context_initialized", "source", source, "package", context.getPackageName());
        step("log_sink", () -> {
            LogFileSink.init(context);
            LogFileSink.announce();
        });
        // Each stage is isolated: one failing hook group must not prevent the download/proxy
        // hooks from being installed.
        step("ui_injector", () -> UiInjector.install());
        step("okhttp_context", () -> OkHttpRecorder.setContext(context));
        // WebViewProxy must be installed before ProxyHelper: ProxyHelper.apply() forwards the
        // state to WebViewProxy, which needs its WebView hooks and class loader in place.
        step("webview_proxy", () -> WebViewProxy.install(context, classLoader));
        step("proxy_selector", () -> ProxyHelper.install(context, classLoader));
        step("okhttp_proxy", () -> OkHttpRecorder.install(classLoader));
        step("trust_all_certs", () -> TrustAllCerts.install(context, classLoader));
        step("network_timeout", () -> NetworkTimeout.install(context, classLoader));
        step("webview_debug", () -> WebViewRecorder.install(context, classLoader));
    }

    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            LogSink.error("install_" + name, error);
        }
    }

    private static void scheduleFallback(final ClassLoader classLoader) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (INSTALLED.get()) return;
                try {
                    Class<?> helper = XposedHelpers.findClass("android.app.AndroidAppHelper", classLoader);
                    Object application = helper.getMethod("currentApplication").invoke(null);
                    if (application instanceof Application) {
                        Application app = (Application) application;
                        install(app, app, classLoader, "delayed_current_application");
                    } else {
                        LogSink.event("context_initialized", "source", "delayed_current_application",
                                "status", "application_unavailable");
                    }
                } catch (Throwable error) {
                    LogSink.error("fallback_install_hooks", error);
                }
            }, 30_000L);
            LogSink.event("hook_registered", "hook", "delayed_current_application", "delay_ms", "30000");
        } catch (Throwable error) {
            LogSink.error("schedule_fallback_install", error);
        }
    }
}
