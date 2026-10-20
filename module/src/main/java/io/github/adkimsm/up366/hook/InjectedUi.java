package io.github.adkimsm.up366.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Process;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Network-debug panel injected as sibling Views on the target Activity's DecorView.
 *
 * <p>Exposes: applying a global HTTP proxy inside the target process, optionally trusting all
 * certificates for OkHttp (packet capture), and enabling WebView remote debugging. A small optional
 * file-log switch and diagnostics text are kept for troubleshooting.
 */
final class InjectedUi {
    private static final Map<Activity, Root> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Root>());

    /**
     * Panel geometry in dp; the height is additionally clamped to the window.
     */
    private static final int PANEL_WIDTH_DP = 340;
    private static final int PANEL_MAX_HEIGHT_DP = 560;
    private static final int PANEL_MIN_HEIGHT_DP = 240;

    private InjectedUi() {}

    /** Appends the floating button and (hidden) panel; returns true when newly attached. */
    static boolean show(Activity activity) {
        if (activity == null) return false;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(activity)) return false;
        }
        ViewGroup decor = decor(activity);
        if (decor == null) return false;

        Root root = new Root(activity);
        int parentWidth = decor.getWidth() > 0 ? decor.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels;
        int parentHeight = decor.getHeight() > 0 ? decor.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels;
        decor.addView(root.button, buttonParams(activity, parentWidth, parentHeight));
        decor.addView(root.panel, panelParams(activity, parentWidth, parentHeight));
        root.panel.setVisibility(View.GONE);
        root.installDrag(decor);
        ATTACHED.put(activity, root);
        return true;
    }

    /** Removes the injected Views from the Activity, leaving the target View tree otherwise intact. */
    static void hide(Activity activity) {
        Root root = ATTACHED.remove(activity);
        if (root == null) return;
        ViewGroup decor = decor(activity);
        if (decor == null) return;
        try {
            decor.removeView(root.button);
            decor.removeView(root.panel);
        } catch (Throwable ignored) {
            // The DecorView may already be detached; nothing to do.
        }
    }

    private static ViewGroup decor(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static FrameLayout.LayoutParams buttonParams(Context context, int parentWidth,
                                                         int parentHeight) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int savedX = Config.floatX(context, -1);
        int savedY = Config.floatY(context, -1);
        if (savedX >= 0 && savedY >= 0) {
            params.leftMargin = savedX;
            params.topMargin = savedY;
        } else {
            params.leftMargin = Math.max(0, parentWidth - dp(context, 64));
            params.topMargin = dp(context, 96);
        }
        return params;
    }

    private static FrameLayout.LayoutParams panelParams(Context context, int parentWidth,
                                                        int parentHeight) {
        int width = Math.min(dp(context, PANEL_WIDTH_DP),
                Math.max(dp(context, 220), parentWidth - dp(context, 24)));
        int height = Math.min(dp(context, PANEL_MAX_HEIGHT_DP),
                Math.max(dp(context, PANEL_MIN_HEIGHT_DP), parentHeight - dp(context, 96)));
        height = Math.min(height, Math.max(dp(context, 160), parentHeight - dp(context, 24)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(context, 8);
        params.topMargin = dp(context, 140);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private static StateListDrawable pressedBackground(int color, int radiusPx) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(darken(color), radiusPx));
        states.addState(new int[]{}, rounded(color, radiusPx));
        return states;
    }

    private static int darken(int color) {
        return Color.rgb(
                Math.max(0, (int) (Color.red(color) * 0.82f)),
                Math.max(0, (int) (Color.green(color) * 0.82f)),
                Math.max(0, (int) (Color.blue(color) * 0.82f)));
    }

    /** Light/dark color set resolved from the system night mode when the panel is built. */
    private static final class Palette {
        final int panel;
        final int secondary;
        final int primaryText;
        final int secondaryText;
        final int accent;
        final int accentText;
        final int dangerText;
        final int divider;

        private Palette(int panel, int secondary, int primaryText, int secondaryText, int accent,
                        int accentText, int dangerText, int divider) {
            this.panel = panel;
            this.secondary = secondary;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.accent = accent;
            this.accentText = accentText;
            this.dangerText = dangerText;
            this.divider = divider;
        }

        static Palette of(Context context) {
            boolean night = false;
            try {
                night = (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            } catch (Throwable ignored) {
                // Fall back to the light palette.
            }
            if (night) {
                return new Palette(0xFF1F2124, 0xFF2A2D31, 0xFFE8EAED, 0xFF9AA0A6,
                        0xFF1E88E5, Color.WHITE, 0xFFEF9A9A, 0xFF3C4043);
            }
            return new Palette(0xFFFFFFFF, 0xFFF5F6F7, 0xFF1B1C1E, 0xFF5F6368,
                    0xFF1565C0, Color.WHITE, 0xFFC62828, 0xFFE3E6EA);
        }
    }

    private static final class Root {
        final Activity activity;
        final Palette palette;
        final Button button;
        final LinearLayout panel;
        FrameLayout body;

        TextView status;
        TextView logStatus;
        TextView webviewStatus;

        CheckBox proxyEnabled;
        EditText proxyHost;
        EditText proxyPort;

        CheckBox trustAll;

        CheckBox webviewDebug;

        CheckBox logFile;

        Root(Activity activity) {
            this.activity = activity;
            this.palette = Palette.of(activity);
            button = buildButton(activity);
            panel = buildPanel(activity);
            refresh();
        }

        /** Wires drag-to-move plus tap-to-toggle on the floating button (UI thread only). */
        void installDrag(final ViewGroup parent) {
            final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            button.setOnTouchListener(new View.OnTouchListener() {
                float downX;
                float downY;
                int startLeft;
                int startTop;
                boolean dragging;

                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
                    FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) view.getLayoutParams();
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startLeft = params.leftMargin;
                            startTop = params.topMargin;
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (event.getRawX() - downX);
                            int dy = (int) (event.getRawY() - downY);
                            if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                                dragging = true;
                            }
                            if (dragging) {
                                int maxLeft = Math.max(0, parent.getWidth() - view.getWidth());
                                int maxTop = Math.max(0, parent.getHeight() - view.getHeight());
                                params.leftMargin = clamp(startLeft + dx, 0, maxLeft);
                                params.topMargin = clamp(startTop + dy, 0, maxTop);
                                view.setLayoutParams(params);
                                if (panel.getVisibility() == View.VISIBLE) positionPanel();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (dragging) {
                                Config.setFloatPosition(activity, params.leftMargin,
                                        params.topMargin);
                            } else {
                                togglePanel();
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            return true;
                        default:
                            return false;
                    }
                }
            });
        }

        private void togglePanel() {
            boolean opening = panel.getVisibility() != View.VISIBLE;
            if (opening) {
                positionPanel();
                panel.setVisibility(View.VISIBLE);
                panel.post(this::positionPanel);
                refresh();
            } else {
                panel.setVisibility(View.GONE);
            }
        }

        /** Anchors the panel next to the button so it follows the button's dragged position. */
        private void positionPanel() {
            if (!(button.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            if (!(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            if (!(button.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) button.getParent();
            FrameLayout.LayoutParams buttonParams =
                    (FrameLayout.LayoutParams) button.getLayoutParams();
            FrameLayout.LayoutParams panelLayout =
                    (FrameLayout.LayoutParams) panel.getLayoutParams();
            panelLayout.gravity = Gravity.TOP | Gravity.START;

            int gap = dp(activity, 6);
            int parentWidth = parent.getWidth();
            int parentHeight = parent.getHeight();
            int panelWidth = panel.getWidth() > 0 ? panel.getWidth() : panelLayout.width;
            if (panelWidth <= 0) panelWidth = dp(activity, PANEL_WIDTH_DP);
            int panelHeight = panel.getHeight();

            int left = clamp(buttonParams.leftMargin, 0, Math.max(0, parentWidth - panelWidth));
            int below = buttonParams.topMargin + button.getHeight() + gap;
            int top = below;
            if (panelHeight > 0 && below + panelHeight > parentHeight) {
                int above = buttonParams.topMargin - gap - panelHeight;
                if (above >= 0) top = above;
            }
            panelLayout.leftMargin = left;
            panelLayout.topMargin = clamp(top, 0,
                    Math.max(0, parentHeight - Math.max(0, panelHeight)));
            panel.setLayoutParams(panelLayout);
        }

        private static int clamp(int value, int min, int max) {
            if (max < min) return min;
            return Math.max(min, Math.min(max, value));
        }

        private Button buildButton(Context context) {
            Button view = new Button(context);
            view.setText("网");
            view.setTextSize(12);
            view.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
            view.setBackground(rounded(palette.accent, dp(context, 18)));
            view.setTextColor(palette.accentText);
            return view;
        }

        // ------------------------------------------------------------------ panel shell

        private LinearLayout buildPanel(Context context) {
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(rounded(palette.panel, dp(context, 10)));
            panel.addView(buildHeader(context));
            panel.addView(divider(context));

            body = new FrameLayout(context);
            ScrollView scroll = new ScrollView(context);
            scroll.addView(buildContent(context), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            panel.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return panel;
        }

        private View buildHeader(Context context) {
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(context, 12), dp(context, 8), dp(context, 8), dp(context, 8));

            LinearLayout titles = new LinearLayout(context);
            titles.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(context, 14, palette.primaryText, "UP366 网络调试");
            status = text(context, 11, palette.secondaryText, "正在查询…");
            titles.addView(title);
            titles.addView(status);
            header.addView(titles, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button refresh = smallButton(context, "刷新");
            refresh.setOnClickListener(view -> refresh());
            header.addView(refresh);
            Button collapse = smallButton(context, "收起");
            collapse.setOnClickListener(view -> panel.setVisibility(View.GONE));
            header.addView(collapse);
            return header;
        }

        private LinearLayout buildContent(Context context) {
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 12));

            // ---------------------------------------------------------- proxy
            content.addView(caption(context, "应用全局代理"));
            proxyEnabled = checkBox(context, "启用代理（当前进程）", Config.proxyEnabled(context));
            proxyEnabled.setOnCheckedChangeListener((view, checked) -> applyProxySettings());
            content.addView(proxyEnabled);

            content.addView(caption(context, "代理主机"));
            proxyHost = edit(context, "例如 127.0.0.1");
            proxyHost.setSingleLine(true);
            proxyHost.setText(Config.proxyHost(context));
            content.addView(proxyHost, fullWidth(context));

            content.addView(caption(context, "代理端口"));
            proxyPort = edit(context, "例如 8080");
            proxyPort.setSingleLine(true);
            proxyPort.setInputType(InputType.TYPE_CLASS_NUMBER);
            int port = Config.proxyPort(context);
            if (port > 0) proxyPort.setText(Integer.toString(port));
            content.addView(proxyPort, fullWidth(context));

            Button applyProxy = smallButton(context, "应用代理");
            applyProxy.setOnClickListener(view -> applyProxySettings());
            content.addView(applyProxy, fullWidth(context));

            content.addView(caption(context, "HTTPS 抓包"));
            trustAll = checkBox(context, "信任全部证书（跳过校验，仅用于抓包测试）",
                    Config.trustAllCerts(context));
            trustAll.setOnCheckedChangeListener((view, checked) -> {
                Config.setTrustAllCerts(activity, checked);
                Toast.makeText(activity, checked
                                ? "已开启：所有证书放行，新建连接后生效"
                                : "已关闭：恢复系统证书校验",
                        Toast.LENGTH_SHORT).show();
            });
            content.addView(trustAll);

            content.addView(text(context, 11, palette.secondaryText,
                    "代理仅作用于本应用进程；已创建的连接需要重启应用后才会走新代理。"));

            // ---------------------------------------------------------- webview
            content.addView(caption(context, "WebView 调试"));
            webviewDebug = checkBox(context, "开启 WebView 调试", Config.webviewDebugEnabled(context));
            webviewDebug.setOnCheckedChangeListener((view, checked) -> {
                Config.setWebviewDebugEnabled(activity, checked);
                try {
                    WebView.setWebContentsDebuggingEnabled(checked);
                } catch (Throwable error) {
                    LogSink.error("webview_debug_toggle", error);
                }
                updateWebviewStatus();
            });
            content.addView(webviewDebug);

            webviewStatus = text(context, 11, palette.secondaryText, "");
            content.addView(webviewStatus);

            Button copyAdb = smallButton(context, "复制 adb forward 命令");
            copyAdb.setOnClickListener(view -> copyToClipboard(adbForwardCommand()));
            content.addView(copyAdb, fullWidth(context));

            // ---------------------------------------------------------- log
            content.addView(caption(context, "日志"));
            logFile = checkBox(context, "写日志文件（可选）", Config.enabled(context, Config.LOG_FILE));
            logFile.setOnCheckedChangeListener((view, checked) -> {
                Config.set(activity, Config.LOG_FILE, checked);
                if (checked) {
                    LogFileSink.announce();
                    Toast.makeText(activity, pathOrReason(), Toast.LENGTH_LONG).show();
                }
                updateLogStatus();
            });
            content.addView(logFile);

            logStatus = text(context, 11, palette.secondaryText, "");
            content.addView(logStatus);

            Button copyLog = smallButton(context, "复制日志文件路径");
            copyLog.setOnClickListener(view -> copyToClipboard(LogFileSink.path()));
            content.addView(copyLog, fullWidth(context));

            content.addView(text(context, 11, palette.secondaryText,
                    "「信任全部证书」仅用于验证代理/抓包是否生效，会放行任意证书与主机名，测试完请关闭。"));
            return content;
        }

        // ------------------------------------------------------------------ proxy

        private void applyProxySettings() {
            String host = value(proxyHost);
            String portText = value(proxyPort);
            int port = 0;
            if (!portText.isEmpty()) {
                try {
                    port = Integer.parseInt(portText);
                } catch (Throwable ignored) {
                    port = 0;
                }
            }
            boolean enabled = proxyEnabled.isChecked();
            if (enabled && (host.isEmpty() || port <= 0 || port > 65535)) {
                Toast.makeText(activity, "请填写有效的代理主机与端口（1-65535）",
                        Toast.LENGTH_LONG).show();
                proxyEnabled.setChecked(false);
                return;
            }
            Config.setProxyHost(activity, host);
            Config.setProxyPort(activity, port);
            Config.setProxyEnabled(activity, enabled);
            boolean applied = ProxyHelper.apply(enabled, host, port);
            Toast.makeText(activity,
                    enabled ? (applied ? "代理已应用：" + host + ":" + port : "代理应用失败，请查看日志")
                            : "代理已关闭",
                    Toast.LENGTH_SHORT).show();
        }

        // ------------------------------------------------------------------ webview

        private void updateWebviewStatus() {
            boolean enabled = Config.webviewDebugEnabled(activity);
            int port = Config.webviewDebugPort(activity);
            webviewStatus.setText(enabled
                    ? "已开启，端口 " + port + "\nChrome/Edge 打开 chrome://inspect 连接设备"
                    : "未开启");
        }

        private String adbForwardCommand() {
            return "adb forward tcp:" + Config.webviewDebugPort(activity)
                    + " localabstract:webview_devtools_remote_" + Process.myPid();
        }

        // ------------------------------------------------------------------ log

        private void updateLogStatus() {
            if (!Config.enabled(activity, Config.LOG_FILE)) {
                String path = LogFileSink.path();
                logStatus.setText(path.isEmpty() ? "未开启" : "未开启（路径预览）\n" + path);
                return;
            }
            String path = LogFileSink.path();
            logStatus.setText(path.isEmpty() ? "不可用：" + LogFileSink.reason() : path);
        }

        private String pathOrReason() {
            String path = LogFileSink.path();
            return path.isEmpty() ? "日志不可用：" + LogFileSink.reason() : "日志写入：" + path;
        }

        // ------------------------------------------------------------------ refresh

        void refresh() {
            status.setText("Hook 就绪，processRole=" + LogSink.processRole());
            if (proxyHost.getText() == null || proxyHost.getText().length() == 0) {
                proxyHost.setText(Config.proxyHost(activity));
            }
            if (proxyPort.getText() == null || proxyPort.getText().length() == 0) {
                int port = Config.proxyPort(activity);
                if (port > 0) proxyPort.setText(Integer.toString(port));
            }
            updateWebviewStatus();
            updateLogStatus();
        }

        // ------------------------------------------------------------------ widgets

        private TextView text(Context context, float size, int color, String value) {
            TextView view = new TextView(context);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setText(value);
            return view;
        }

        private TextView caption(Context context, String label) {
            TextView view = new TextView(context);
            view.setText(label);
            view.setTextSize(12);
            view.setTextColor(palette.secondaryText);
            view.setPadding(0, dp(context, 12), 0, dp(context, 4));
            return view;
        }

        private CheckBox checkBox(Context context, String label, boolean checked) {
            CheckBox view = new CheckBox(context);
            view.setText(label);
            view.setTextSize(12);
            view.setTextColor(palette.primaryText);
            view.setButtonTintList(ColorStateList.valueOf(palette.accent));
            view.setMinHeight(0);
            view.setMinimumHeight(0);
            view.setPadding(0, 0, 0, 0);
            view.setChecked(checked);
            return view;
        }

        private EditText edit(Context context, String hint) {
            EditText view = new EditText(context);
            view.setTextSize(12);
            view.setTextColor(palette.primaryText);
            view.setHintTextColor(palette.secondaryText);
            view.setHint(hint);
            view.setInputType(InputType.TYPE_CLASS_TEXT);
            view.setBackground(rounded(palette.secondary, dp(context, 6)));
            view.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
            return view;
        }

        private Button smallButton(Context context, String label) {
            Button view = new Button(context);
            view.setText(label);
            view.setTextSize(12);
            view.setAllCaps(false);
            view.setTextColor(palette.primaryText);
            view.setMinWidth(0);
            view.setMinimumWidth(0);
            view.setMinHeight(0);
            view.setMinimumHeight(0);
            view.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
            view.setStateListAnimator(null);
            view.setBackground(pressedBackground(palette.secondary, dp(context, 8)));
            return view;
        }

        private LinearLayout.LayoutParams fullWidth(Context context) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(context, 2), 0, dp(context, 2));
            return params;
        }

        private View divider(Context context) {
            View view = new View(context);
            view.setBackgroundColor(palette.divider);
            view.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            return view;
        }

        private static String value(EditText view) {
            return view.getText() == null ? "" : view.getText().toString().trim();
        }

        private void copyToClipboard(String text) {
            try {
                ClipboardManager clipboard =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(ClipData.newPlainText("UP366", text));
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Copy is best-effort; a failure must never affect the target app.
            }
        }
    }
}
