package com.aiinsight.service;

import com.aiinsight.config.HttpProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(TavilySearchProperties.class)
@Slf4j
public class SearchConfig {

    @Bean
    SearchProvider searchProvider(TavilySearchProperties properties,
                                  ObjectMapper objectMapper,
                                  HttpProxyProperties proxyProperties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("Search provider disabled because TAVILY_API_KEY is empty; Researcher will only fetch user-provided URLs.");
            return new NoopSearchProvider();
        }
        log.info("Tavily Search provider enabled: baseUrl={}, maxResults={}",
                properties.getBaseUrl(), properties.getMaxResults());
        return new TavilySearchProvider(properties, objectMapper, proxyProperties);
    }
}
