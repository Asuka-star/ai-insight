package com.aiinsight.llm;

import com.aiinsight.observability.AgentTraceContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
class SpringAiLlmClient implements LlmClient {

    private final ChatModel chatModel;
    private final XiaomiLlmProperties properties;

    SpringAiLlmClient(ChatModel chatModel, XiaomiLlmProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String complete(ChatRequest request) {
        ChatOptions options = request.getOptions() == null ? ChatOptions.deterministic() : request.getOptions();
        // Trace 保存完整 Prompt 和模型输出；日志只打印元信息，避免控制台泄露报告内容或用户资料。
        AgentTraceContext.recordModelRequest(properties.getModel(), request);
        long startedAt = System.currentTimeMillis();
        log.info("LLM request started: model={}, messages={}, temperature={}, maxTokens={}",
                properties.getModel(), request.getMessages().size(), options.getTemperature(), options.getMaxTokens());
        OpenAiChatOptions springAiOptions = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .build();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(toSpringMessages(request.getMessages()), springAiOptions));
        } catch (RuntimeException ex) {
            log.error("LLM request failed: model={}, latencyMs={}, exceptionType={}, message={}",
                    properties.getModel(),
                    System.currentTimeMillis() - startedAt,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex);
            throw ex;
        }

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            log.warn("LLM response empty: model={}, latencyMs={}, reason=response/result/output null, generationMetadata={}",
                    properties.getModel(),
                    System.currentTimeMillis() - startedAt,
                    generationMetadata(response));
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            log.warn("LLM response blank: model={}, latencyMs={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                    properties.getModel(),
                    System.currentTimeMillis() - startedAt,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    generationMetadata(response));
            throw new IllegalStateException("Spring AI returned an empty chat message");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        AgentTraceContext.recordModelResponse(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens()
        );
        log.info("LLM response completed: model={}, latencyMs={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                properties.getModel(),
                System.currentTimeMillis() - startedAt,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                generationMetadata(response));
        if (usage != null && usage.getCompletionTokens() != null && usage.getCompletionTokens() >= options.getMaxTokens()) {
            log.warn("LLM response reached maxTokens: model={}, maxTokens={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                    properties.getModel(),
                    options.getMaxTokens(),
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    generationMetadata(response));
        }
        return content.trim();
    }

    private String generationMetadata(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return "unavailable";
        }
        var metadata = response.getResult().getMetadata();
        return "finishReason=%s, contentFilters=%s, keys=%s".formatted(
                metadata.getFinishReason(),
                metadata.getContentFilters(),
                metadata.keySet()
        );
    }

    private List<Message> toSpringMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Chat messages must not be empty");
        }
        return messages.stream()
                .map(this::toSpringMessage)
                .toList();
    }

    private Message toSpringMessage(ChatMessage message) {
        String content = message.getContent() == null ? "" : message.getContent();
        return switch (message.getRole()) {
            case "system" -> new SystemMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "user" -> new UserMessage(content);
            default -> new UserMessage(content);
        };
    }
}
