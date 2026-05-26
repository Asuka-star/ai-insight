package com.aiinsight.observability;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.model.run.AgentTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTraceContextTest {

    @AfterEach
    void clearTrace() {
        AgentTraceContext.clear();
    }

    @Test
    void fallbackAfterParseFailureKeepsRawModelOutputAndUsage() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        AgentTraceContext.recordModelRequest(
                "mimo-v2.5-pro",
                new ChatRequest(List.of(ChatMessage.user("Return compact JSON.")), null)
        );
        AgentTraceContext.recordModelResponse("not-json-model-output", 20, 7);

        AgentTraceContext.recordFallback("deterministic-reviewer-fallback", "fallback-review-output");

        assertThat(trace.getFallbackUsed()).isTrue();
        assertThat(trace.getModelName()).isEqualTo("deterministic-reviewer-fallback");
        assertThat(trace.getRawModelOutput()).isEqualTo("not-json-model-output");
        assertThat(trace.getCompletionTokens()).isEqualTo(7);
        assertThat(trace.getTotalTokens()).isEqualTo(27);
        assertThat(trace.getOutputSnapshot()).startsWith("Fallback output:");
    }

    @Test
    void fallbackWithoutModelResponseStoresFallbackOutputAndEstimatedUsage() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        AgentTraceContext.recordFallback("deterministic-writer-fallback", "fallback-output");

        assertThat(trace.getFallbackUsed()).isTrue();
        assertThat(trace.getRawModelOutput()).isEqualTo("fallback-output");
        assertThat(trace.getPromptTokens()).isZero();
        assertThat(trace.getCompletionTokens()).isPositive();
        assertThat(trace.getTotalTokens()).isEqualTo(trace.getCompletionTokens());
    }
}
