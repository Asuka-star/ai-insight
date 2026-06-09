package com.aiinsight.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai-insight.llm.doubao")
public class DoubaoLlmProperties implements OpenAiCompatibleLlmProperties {

    private String apiKey;
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
    private String completionsPath = "/chat/completions";
    private String endpointId;
    private String displayModel = "Doubao-Seed-2.0-lite";
    private Duration timeout = Duration.ofSeconds(30);

    @Override
    public String getModel() {
        return endpointId;
    }

    @Override
    public String getDisplayModel() {
        return displayModel;
    }
}
