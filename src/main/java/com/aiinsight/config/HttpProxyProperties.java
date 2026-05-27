package com.aiinsight.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties("ai-insight.http.proxy")
public class HttpProxyProperties {

    private boolean enabled = false;
    private String url = "";
    private String host = "";
    private int port = 0;
    private String username = "";
    private String password = "";
    private List<String> nonProxyHosts = new ArrayList<>(List.of("localhost", "127.0.0.1", "::1"));

    public boolean configured() {
        return enabled && StringUtils.hasText(resolvedHost()) && resolvedPort() > 0;
    }

    public String resolvedHost() {
        ProxyEndpoint endpoint = endpoint();
        return endpoint == null ? "" : endpoint.host();
    }

    public int resolvedPort() {
        ProxyEndpoint endpoint = endpoint();
        return endpoint == null ? 0 : endpoint.port();
    }

    public String resolvedUsername() {
        ProxyEndpoint endpoint = endpoint();
        if (StringUtils.hasText(username)) {
            return username;
        }
        return endpoint == null ? "" : endpoint.username();
    }

    public String resolvedPassword() {
        ProxyEndpoint endpoint = endpoint();
        if (StringUtils.hasText(password)) {
            return password;
        }
        return endpoint == null ? "" : endpoint.password();
    }

    private ProxyEndpoint endpoint() {
        if (StringUtils.hasText(url)) {
            try {
                URI uri = URI.create(url.trim());
                return new ProxyEndpoint(
                        uri.getHost(),
                        uri.getPort(),
                        userInfoPart(uri, 0),
                        userInfoPart(uri, 1)
                );
            } catch (RuntimeException ignored) {
                return new ProxyEndpoint(host, port, "", "");
            }
        }
        return new ProxyEndpoint(host, port, "", "");
    }

    private String userInfoPart(URI uri, int index) {
        String userInfo = uri.getUserInfo();
        if (!StringUtils.hasText(userInfo)) {
            return "";
        }
        String[] parts = userInfo.split(":", 2);
        if (index >= parts.length) {
            return "";
        }
        return parts[index];
    }

    private record ProxyEndpoint(String host, int port, String username, String password) {
    }
}
