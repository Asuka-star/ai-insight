package com.aiinsight.llm;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(XiaomiLlmProperties.class)
@Slf4j
public class LlmConfig {

    @Bean
    @Primary
    LlmClient xiaomiLlmClient(XiaomiLlmProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            log.warn("LLM client disabled because XIAOMI_LLM_API_KEY is empty; LLM-first agents will use deterministic fallback.");
            return new NoopLlmClient();
        }
        log.info("LLM client enabled: model={}, baseUrl={}, completionsPath={}",
                properties.getModel(), properties.getBaseUrl(), properties.getCompletionsPath());
        return new SpringAiLlmClient(xiaomiChatModel(properties), properties);
    }

    private ChatModel xiaomiChatModel(XiaomiLlmProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .completionsPath(properties.getCompletionsPath())
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory(httpClient)))
                .webClientBuilder(WebClient.builder())
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(ChatOptions.deterministic().getTemperature())
                .maxTokens(ChatOptions.deterministic().getMaxTokens())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .toolCallingManager(ToolCallingManager.builder()
                        .observationRegistry(ObservationRegistry.NOOP)
                        .build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
}
