package com.aiinsight.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties("ai-insight.web-renderer")
public class WebRendererProperties {

    private boolean enabled = true;
    private String browserChannel = "msedge";
    private boolean headless = true;
    private Duration navigationTimeout = Duration.ofSeconds(15);
    private Duration waitAfterLoad = Duration.ofSeconds(1);
    private int maxHtmlLength = 2_000_000;
}
