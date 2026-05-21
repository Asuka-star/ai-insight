package com.aiinsight.llm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

class XiaomiOpenAiCompatibleClient implements LlmClient {

    private final RestClient restClient;
    private final XiaomiLlmProperties properties;

    XiaomiOpenAiCompatibleClient(RestClient restClient, XiaomiLlmProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(ChatRequest request) {
        // 小米接口按 OpenAI chat/completions 兼容格式调用，便于后续替换其他模型供应商。
        OpenAiChatResponse response = restClient.post()
                .uri(chatCompletionsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .body(toPayload(request))
                .retrieve()
                .body(OpenAiChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("Xiaomi LLM returned an empty response");
        }
        OpenAiChatMessage message = response.choices().get(0).message();
        if (message == null || message.content() == null || message.content().isBlank()) {
            throw new IllegalStateException("Xiaomi LLM returned an empty message");
        }
        return message.content().trim();
    }

    private Map<String, Object> toPayload(ChatRequest request) {
        ChatOptions options = request.options() == null ? ChatOptions.deterministic() : request.options();
        // 只透传当前系统用到的最小参数集合，减少不同兼容接口之间的差异风险。
        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList();
        return Map.of(
                "model", properties.getModel(),
                "messages", messages,
                "temperature", options.temperature(),
                "max_tokens", options.maxTokens()
        );
    }

    private String chatCompletionsUrl() {
        // 允许 .env 里的 base-url 带或不带结尾斜杠。
        return properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    }

    private record OpenAiChatResponse(List<OpenAiChoice> choices) {
    }

    private record OpenAiChoice(OpenAiChatMessage message) {
    }

    private record OpenAiChatMessage(String role, String content) {
    }
}
