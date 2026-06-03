package com.aiinsight.llm;

import com.aiinsight.observability.AgentTraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
class SpringAiLlmClient implements LlmClient {

    private static final int RETRY_TOKEN_HEADROOM = 800;
    private static final int MAX_RETRY_TOKENS = 6000;
    private static final boolean SEND_MAX_TOKENS = false;
    private static final String COMPACT_RETRY_INSTRUCTION = """
            Return only the final answer. Do not include reasoning or explanations.
            Keep the response compact and valid for the requested format.
            If the requested output is JSON, return a single valid JSON object.
            Prefer a shorter complete answer over a long truncated answer.
            """;

    private final ChatModel chatModel;
    private final OpenAiCompatibleLlmProperties properties;

    SpringAiLlmClient(ChatModel chatModel, OpenAiCompatibleLlmProperties properties) {
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
        AgentTraceContext.recordModelRequest(properties.getModel(), request);

        long startedAt = System.currentTimeMillis();
        ChatResponse response = callModel(request, options, startedAt, 1);
        String content = responseText(response);
        ChatOptions effectiveOptions = options;

        if (!hasText(content) && shouldRetryBlankLength(response, options)) {
            ChatOptions retryOptions = retryOptions(options);
            ChatRequest retryRequest = compactRetryRequest(request);
            log.warn("LLM response blank because output reached length limit; retrying compactly: model={}, agent={}, subtask={}, originalOutputBudget={}, retryOutputBudget={}, sentMaxTokens={}, generationMetadata={}",
                    properties.getModel(),
                    agentLogValue(request),
                    subtaskLogValue(request),
                    options.getMaxTokens(),
                    retryOptions.getMaxTokens(),
                    SEND_MAX_TOKENS,
                    generationMetadata(response));
            response = callModel(retryRequest, retryOptions, startedAt, 2);
            content = responseText(response);
            effectiveOptions = retryOptions;
        }

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            log.warn("LLM response empty: model={}, agent={}, subtask={}, latencyMs={}, reason=response/result/output null, generationMetadata={}",
                    properties.getModel(),
                    agentLogValue(request),
                    subtaskLogValue(request),
                    System.currentTimeMillis() - startedAt,
                    generationMetadata(response));
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        if (!hasText(content)) {
            Usage usage = usage(response);
            log.warn("LLM response blank: model={}, agent={}, subtask={}, latencyMs={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                    properties.getModel(),
                    agentLogValue(request),
                    subtaskLogValue(request),
                    System.currentTimeMillis() - startedAt,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    generationMetadata(response));
            throw new IllegalStateException("Spring AI returned an empty chat message");
        }

        Usage usage = usage(response);
        AgentTraceContext.recordModelResponse(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens()
        );
        log.info("LLM response completed: model={}, agent={}, subtask={}, latencyMs={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                properties.getModel(),
                agentLogValue(request),
                subtaskLogValue(request),
                System.currentTimeMillis() - startedAt,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                generationMetadata(response));
        if (SEND_MAX_TOKENS && usage != null && usage.getCompletionTokens() != null && usage.getCompletionTokens() >= effectiveOptions.getMaxTokens()) {
            log.warn("LLM response reached maxTokens: model={}, agent={}, subtask={}, maxTokens={}, promptTokens={}, completionTokens={}, generationMetadata={}",
                    properties.getModel(),
                    agentLogValue(request),
                    subtaskLogValue(request),
                    effectiveOptions.getMaxTokens(),
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    generationMetadata(response));
        }
        return content.trim();
    }

    private ChatResponse callModel(ChatRequest request, ChatOptions options, long startedAt, int attempt) {
        log.info("LLM request started: model={}, agent={}, subtask={}, attempt={}, messages={}, temperature={}, outputBudget={}, sentMaxTokens={}",
                properties.getModel(),
                agentLogValue(request),
                subtaskLogValue(request),
                attempt,
                request.getMessages().size(),
                options.getTemperature(),
                outputBudgetLogValue(options),
                SEND_MAX_TOKENS);
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(options.getTemperature());
        if (SEND_MAX_TOKENS) {
            optionsBuilder.maxTokens(options.getMaxTokens());
        }
        OpenAiChatOptions springAiOptions = optionsBuilder.build();
        try {
            return chatModel.call(new Prompt(toSpringMessages(request.getMessages()), springAiOptions));
        } catch (RuntimeException ex) {
            log.error("LLM request failed: model={}, agent={}, subtask={}, attempt={}, latencyMs={}, exceptionType={}, message={}",
                    properties.getModel(),
                    agentLogValue(request),
                    subtaskLogValue(request),
                    attempt,
                    System.currentTimeMillis() - startedAt,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex);
            throw ex;
        }
    }

    private boolean shouldRetryBlankLength(ChatResponse response, ChatOptions options) {
        if (response == null || response.getResult() == null) {
            return false;
        }
        String finishReason = response.getResult().getMetadata() == null
                ? null
                : response.getResult().getMetadata().getFinishReason();
        if (finishReason != null && finishReason.toUpperCase(Locale.ROOT).contains("LENGTH")) {
            return true;
        }
        Usage usage = usage(response);
        return usage != null
                && usage.getCompletionTokens() != null
                && usage.getCompletionTokens() >= options.getMaxTokens();
    }

    private ChatOptions retryOptions(ChatOptions options) {
        int retryMaxTokens = Math.min(
                MAX_RETRY_TOKENS,
                Math.max(options.getMaxTokens() + RETRY_TOKEN_HEADROOM, (int) Math.ceil(options.getMaxTokens() * 1.5))
        );
        return new ChatOptions(options.getTemperature(), retryMaxTokens);
    }

    private ChatRequest compactRetryRequest(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(COMPACT_RETRY_INSTRUCTION));
        messages.addAll(request.getMessages());
        return new ChatRequest(messages, request.getOptions(), request.getAgentName(), request.getSubtaskName());
    }

    private String agentLogValue(ChatRequest request) {
        if (request != null && hasText(request.getAgentName())) {
            return request.getAgentName();
        }
        return AgentTraceContext.current()
                .map(trace -> trace.getAgentName() == null ? null : trace.getAgentName().name())
                .filter(this::hasText)
                .orElse("unknown");
    }

    private String subtaskLogValue(ChatRequest request) {
        return request != null && hasText(request.getSubtaskName()) ? request.getSubtaskName() : "default";
    }

    private String outputBudgetLogValue(ChatOptions options) {
        if (SEND_MAX_TOKENS) {
            return "provider-max-tokens:" + options.getMaxTokens();
        }
        return "local-only:" + options.getMaxTokens();
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private Usage usage(ChatResponse response) {
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
