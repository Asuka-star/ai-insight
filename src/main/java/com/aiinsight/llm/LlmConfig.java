package com.aiinsight.llm;

import com.aiinsight.config.HttpClientFactory;
import com.aiinsight.config.HttpProxyProperties;
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
import java.util.Map;

@Configuration
@EnableConfigurationProperties({XiaomiLlmProperties.class, DoubaoLlmProperties.class})
@Slf4j
public class LlmConfig {

    @Bean
    @Primary
    LlmClient xiaomiLlmClient(XiaomiLlmProperties properties,
                              DoubaoLlmProperties doubaoProperties,
                              HttpProxyProperties proxyProperties) {
        LlmClient defaultClient = openAiCompatibleClient("xiaomi-openai-compatible", properties, proxyProperties);
        if (!defaultClient.isAvailable()) {
            log.warn("LLM client disabled because XIAOMI_LLM_API_KEY is empty; LLM-first agents will use deterministic fallback.");
            return defaultClient;
        }

        LlmClient clarifierClient = openAiCompatibleClient("doubao-openai-compatible", doubaoProperties, proxyProperties);
        if (!clarifierClient.isAvailable()) {
            log.info("Clarifier small-model route disabled because DOUBAO_LLM_API_KEY or DOUBAO_LLM_ENDPOINT_ID is empty; Clarifier will use the default model.");
            return defaultClient;
        }
        log.info("Clarifier small-model route enabled: agent=CLARIFIER, model={}, endpointId={}, baseUrl={}, completionsPath={}",
                doubaoProperties.getDisplayModel(),
                doubaoProperties.getEndpointId(),
                doubaoProperties.getBaseUrl(),
                doubaoProperties.getCompletionsPath());
        return new AgentRoutingLlmClient(defaultClient, Map.of("CLARIFIER", clarifierClient));
    }

    LlmClient xiaomiLlmClient(XiaomiLlmProperties properties) {
        return xiaomiLlmClient(properties, new DoubaoLlmProperties(), null);
    }

    private LlmClient openAiCompatibleClient(String provider,
                                             OpenAiCompatibleLlmProperties properties,
                                             HttpProxyProperties proxyProperties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            return new NoopLlmClient();
        }
        if (!StringUtils.hasText(properties.getModel())) {
            return new NoopLlmClient();
        }
        log.info("LLM client enabled: provider={}, model={}, baseUrl={}, completionsPath={}",
                provider, properties.getModel(), properties.getBaseUrl(), properties.getCompletionsPath());
        return new SpringAiLlmClient(openAiCompatibleChatModel(properties, proxyProperties), properties);
    }

    private ChatModel openAiCompatibleChatModel(OpenAiCompatibleLlmProperties properties, HttpProxyProperties proxyProperties) {
        HttpClient httpClient = HttpClientFactory.builder(properties.getTimeout(), proxyProperties)
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
