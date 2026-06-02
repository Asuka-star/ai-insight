package com.aiinsight.service;

import com.aiinsight.config.HttpProxyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
@Slf4j
public class EmbeddingConfig {

    @Bean
    EmbeddingClient embeddingClient(EmbeddingProperties properties,
                                    ObjectMapper objectMapper,
                                    HttpProxyProperties proxyProperties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("Embedding client disabled because AI_INSIGHT_EMBEDDING_API_KEY is empty; retrieval will use keyword/hybrid fallback.");
            return new NoopEmbeddingClient();
        }
        log.info("Embedding client enabled: model={}, baseUrl={}, dimensions={}",
                properties.getModel(), properties.getBaseUrl(), properties.getDimensions());
        return new OpenAiCompatibleEmbeddingClient(properties, objectMapper, proxyProperties);
    }
}
