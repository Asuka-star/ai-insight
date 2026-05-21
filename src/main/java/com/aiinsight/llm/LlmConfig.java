package com.aiinsight.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(XiaomiLlmProperties.class)
public class LlmConfig {

    @Bean
    @Primary
    LlmClient xiaomiLlmClient(XiaomiLlmProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            // 没有配置 key 时保持应用可启动，Agent 会自动走 deterministic fallback。
            return new NoopLlmClient();
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        RestClient restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
        return new XiaomiOpenAiCompatibleClient(restClient, properties);
    }
}
