package com.aiinsight.config;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public final class HttpClientFactory {

    private HttpClientFactory() {
    }

    public static HttpClient.Builder builder(Duration connectTimeout, HttpProxyProperties proxyProperties) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        if (proxyProperties != null && proxyProperties.configured()) {
            builder.proxy(new ConfigurableProxySelector(proxyProperties));
            String username = proxyProperties.resolvedUsername();
            String password = proxyProperties.resolvedPassword();
            if (StringUtils.hasText(username)) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password == null ? new char[0] : password.toCharArray());
                    }
                });
            }
        }
        return builder;
    }

    private static final class ConfigurableProxySelector extends ProxySelector {

        private final HttpProxyProperties proxyProperties;
        private final Proxy proxy;

        private ConfigurableProxySelector(HttpProxyProperties proxyProperties) {
            this.proxyProperties = proxyProperties;
            this.proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(proxyProperties.resolvedHost(), proxyProperties.resolvedPort())
            );
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null || bypass(uri.getHost())) {
                return List.of(Proxy.NO_PROXY);
            }
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // The caller owns retry and logging; ProxySelector only supplies routing.
        }

        private boolean bypass(String host) {
            if (!StringUtils.hasText(host)) {
                return true;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return proxyProperties.getNonProxyHosts().stream()
                    .filter(StringUtils::hasText)
                    .map(pattern -> pattern.toLowerCase(Locale.ROOT).trim())
                    .anyMatch(pattern -> matches(pattern, normalizedHost));
        }

        private boolean matches(String pattern, String host) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                return host.endsWith(suffix);
            }
            if (pattern.startsWith(".")) {
                return host.endsWith(pattern);
            }
            return host.equals(pattern);
        }
    }
}
