package com.aiinsight.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("ai-insight.search.tavily")
public class TavilySearchProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.tavily.com/search";
    private int maxResults = 5;
    private String searchDepth = "basic";
    private boolean includeRawContent = true;
    private Duration timeout = Duration.ofSeconds(20);
}
