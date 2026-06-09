package com.aiinsight.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.observability.AgentTraceContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiLlmClientTest {

    @Test
    void recordsDisplayModelInTraceWhileSendingEndpointIdToProvider() {
        RecordingChatModel chatModel = new RecordingChatModel(List.of(response("clarified", "STOP", 12, 4)));
        DoubaoLlmProperties properties = new DoubaoLlmProperties();
        properties.setEndpointId("ep-clarifier-test");
        properties.setDisplayModel("Doubao-Seed-2.0-lite");
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, properties);
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        try {
            String result = client.complete(new ChatRequest(
                    List.of(ChatMessage.user("Clarify scope.")),
                    ChatOptions.clarifier()
            ));

            assertThat(result).isEqualTo("clarified");
            assertThat(trace.getModelName()).isEqualTo("Doubao-Seed-2.0-lite");
            assertThat(((OpenAiChatOptions) chatModel.prompts.get(0).getOptions()).getModel())
                    .isEqualTo("ep-clarifier-test");
        } finally {
            AgentTraceContext.clear();
        }
    }

    @Test
    void retriesBlankLengthResponseWithCompactPromptAndServerDefaultTokens() {
        RecordingChatModel chatModel = new RecordingChatModel(List.of(
                response("", "LENGTH", 120, 700),
                response("{\"ok\":true}", "STOP", 130, 20)
        ));
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, new XiaomiLlmProperties());

        String result = client.complete(new ChatRequest(
                List.of(ChatMessage.user("Return JSON.")),
                ChatOptions.clarifier()
        ));

        assertThat(result).isEqualTo("{\"ok\":true}");
        assertThat(chatModel.prompts).hasSize(2);
        assertThat(chatModel.prompts.get(1).getInstructions().get(0).getText()).contains("Return only the final answer");
        assertThat(maxTokens(chatModel.prompts.get(0))).isNull();
        assertThat(maxTokens(chatModel.prompts.get(1))).isNull();
    }

    @Test
    void doesNotRetryBlankStopResponse() {
        RecordingChatModel chatModel = new RecordingChatModel(List.of(response("", "STOP", 120, 0)));
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, new XiaomiLlmProperties());

        assertThatThrownBy(() -> client.complete(new ChatRequest(
                List.of(ChatMessage.user("Return JSON.")),
                ChatOptions.clarifier()
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty chat message");

        assertThat(chatModel.prompts).hasSize(1);
    }

    private static Integer maxTokens(Prompt prompt) {
        return ((OpenAiChatOptions) prompt.getOptions()).getMaxTokens();
    }

    private static ChatResponse response(String text, String finishReason, int promptTokens, int completionTokens) {
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text), generationMetadata)),
                responseMetadata
        );
    }

    private static class RecordingChatModel implements ChatModel {

        private final List<ChatResponse> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        RecordingChatModel(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return responses.get(prompts.size() - 1);
        }
    }
}
