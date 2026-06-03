package com.aiinsight.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai-insight.llm.xiaomi")
public class XiaomiLlmProperties implements OpenAiCompatibleLlmProperties {

    private String apiKey;
    private String baseUrl = "https://token-plan-cn.xiaomimimo.com/v1";
    private String completionsPath = "/chat/completions";
    private String model = "mimo-v2.5-pro";
    private Duration timeout = Duration.ofSeconds(60);
}
