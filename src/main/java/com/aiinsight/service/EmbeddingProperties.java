package com.aiinsight.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai-insight.embedding")
public class EmbeddingProperties {

    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String embeddingsPath = "/embeddings";
    private String model = "text-embedding-3-small";
    private int dimensions = 0;
    private int maxBatchSize = 32;
    private Duration timeout = Duration.ofSeconds(30);
    private boolean cacheEnabled = true;
    private Duration cacheTtl = Duration.ofDays(90);
}
