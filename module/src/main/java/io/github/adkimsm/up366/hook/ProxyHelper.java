package io.github.adkimsm.up366.hook;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies an HTTP proxy to the current (target) process. It sets the JVM proxy properties used by
 * {@code HttpURLConnection} and installs a default {@link ProxySelector}, which is also what OkHttp
 * consults when no explicit proxy is configured. The injected OkHttp builder additionally sets the
 * proxy directly so already-diversified clients are covered.
 */
final class ProxyHelper {
    private static volatile ProxySelector previous;

    private ProxyHelper() {}

    static boolean apply(boolean enabled, String host, int port) {
        try {
            if (enabled && host != null && !host.isEmpty() && port > 0 && port <= 65535) {
                System.setProperty("http.proxyHost", host);
                System.setProperty("http.proxyPort", Integer.toString(port));
                System.setProperty("https.proxyHost", host);
                System.setProperty("https.proxyPort", Integer.toString(port));
                System.setProperty("http.nonProxyHosts", "");
                ProxySelector selector = new FixedProxySelector(host, port);
                if (previous == null) previous = ProxySelector.getDefault();
                ProxySelector.setDefault(selector);
                LogSink.event("proxy_applied", "host", host, "port", Integer.toString(port),
                        "status", "ok");
                return true;
            }
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.nonProxyHosts");
            ProxySelector.setDefault(previous != null ? previous : new NoProxySelector());
            LogSink.event("proxy_applied", "status", "disabled");
            return true;
        } catch (Throwable error) {
            LogSink.error("apply_proxy", error);
            return false;
        }
    }

    static Proxy proxy(String host, int port) {
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    private static final class FixedProxySelector extends ProxySelector {
        private final List<Proxy> proxies;

        FixedProxySelector(String host, int port) {
            List<Proxy> list = new ArrayList<>();
            list.add(proxy(host, port));
            proxies = list;
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            LogSink.error("proxy_connect_failed", ioe);
        }
    }

    private static final class NoProxySelector extends ProxySelector {
        private final List<Proxy> noProxy = new ArrayList<>();

        NoProxySelector() {
            noProxy.add(Proxy.NO_PROXY);
        }

        @Override
        public List<Proxy> select(URI uri) {
            return noProxy;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            // Nothing to do.
        }
    }
}
