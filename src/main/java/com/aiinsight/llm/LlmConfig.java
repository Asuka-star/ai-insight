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

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(XiaomiLlmProperties.class)
@Slf4j
public class LlmConfig {

    @Bean
    @Primary
    LlmClient xiaomiLlmClient(XiaomiLlmProperties properties) {
        // 本地演示允许读取 .env，线上仍优先使用系统环境变量，避免把密钥写进仓库配置。
        applyDotenvFallback(properties);
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

    private void applyDotenvFallback(XiaomiLlmProperties properties) {
        // 单测可关闭 .env 兜底，确保 Noop 与真实 Client 两条分支都能稳定验证。
        if (!Boolean.parseBoolean(System.getProperty("ai.insight.llm.dotenv.enabled", "true"))) {
            return;
        }
        Map<String, String> dotenv = readDotenv();
        if (dotenv.isEmpty()) {
            return;
        }
        boolean applied = false;
        if (!hasSystemEnv("XIAOMI_LLM_API_KEY") && StringUtils.hasText(dotenv.get("XIAOMI_LLM_API_KEY"))) {
            properties.setApiKey(dotenv.get("XIAOMI_LLM_API_KEY"));
            applied = true;
        }
        if (!hasSystemEnv("XIAOMI_LLM_BASE_URL") && StringUtils.hasText(dotenv.get("XIAOMI_LLM_BASE_URL"))) {
            properties.setBaseUrl(dotenv.get("XIAOMI_LLM_BASE_URL"));
            applied = true;
        }
        if (!hasSystemEnv("XIAOMI_LLM_COMPLETIONS_PATH") && StringUtils.hasText(dotenv.get("XIAOMI_LLM_COMPLETIONS_PATH"))) {
            properties.setCompletionsPath(dotenv.get("XIAOMI_LLM_COMPLETIONS_PATH"));
            applied = true;
        }
        if (!hasSystemEnv("XIAOMI_LLM_MODEL") && StringUtils.hasText(dotenv.get("XIAOMI_LLM_MODEL"))) {
            properties.setModel(dotenv.get("XIAOMI_LLM_MODEL"));
            applied = true;
        }
        if (applied) {
            log.info("Loaded LLM configuration from local .env fallback; secrets are not logged.");
        }
    }

    private boolean hasSystemEnv(String name) {
        return StringUtils.hasText(System.getenv(name));
    }

    private Map<String, String> readDotenv() {
        Path dotenv = Path.of(".env");
        if (!Files.isRegularFile(dotenv)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(dotenv)) {
                String trimmed = line.trim();
                // 只支持简单 KEY=VALUE，足够覆盖本地 LLM 配置，也避免引入额外 dotenv 依赖。
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                values.put(trimmed.substring(0, equals).trim(), stripQuotes(trimmed.substring(equals + 1).trim()));
            }
            return values;
        } catch (IOException ex) {
            log.warn("Failed to read local .env for LLM configuration fallback: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
