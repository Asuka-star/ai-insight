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

import java.util.List;

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
        AgentTraceContext.recordModelRequest(properties.getModel(), request);
        OpenAiChatOptions springAiOptions = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .build();
        ChatResponse response = chatModel.call(new Prompt(toSpringMessages(request.getMessages()), springAiOptions));

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Spring AI returned an empty chat response");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Spring AI returned an empty chat message");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        AgentTraceContext.recordModelResponse(
                content,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens()
        );
        return content.trim();
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
