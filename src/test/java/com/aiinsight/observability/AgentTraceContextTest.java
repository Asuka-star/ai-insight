package com.aiinsight.observability;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.model.run.AgentTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        assertThat(trace.getOutputSnapshot()).isNull();
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

    @Test
    void wrappedParallelTasksAggregateTokenUsageIntoCurrentTrace() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        CompletableFuture<Void> first = CompletableFuture.supplyAsync(AgentTraceContext.wrap(() -> {
            AgentTraceContext.recordModelRequest(
                    "mimo-v2.5-pro",
                    new ChatRequest(List.of(ChatMessage.user("first")), null)
            );
            AgentTraceContext.recordModelResponse("first-output", 10, 20);
            return null;
        }));
        CompletableFuture<Void> second = CompletableFuture.supplyAsync(AgentTraceContext.wrap(() -> {
            AgentTraceContext.recordModelRequest(
                    "mimo-v2.5-pro",
                    new ChatRequest(List.of(ChatMessage.user("second")), null)
            );
            AgentTraceContext.recordModelResponse("second-output", 30, 40);
            return null;
        }));
        CompletableFuture.allOf(first, second).join();

        assertThat(trace.getPromptTokens()).isEqualTo(40);
        assertThat(trace.getCompletionTokens()).isEqualTo(60);
        assertThat(trace.getTotalTokens()).isEqualTo(100);
        assertThat(trace.getModelName()).isEqualTo("mimo-v2.5-pro");
        assertThat(trace.getPrompt()).contains("first").contains("second");
        assertThat(trace.getRawModelOutput()).contains("first-output").contains("second-output");
    }

    @Test
    void promptTokensAreEstimatedAtRequestTimeAndReplacedByActualUsage() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        AgentTraceContext.recordModelRequest(
                "mimo-v2.5-pro",
                new ChatRequest(List.of(ChatMessage.user("first prompt text")), null)
        );
        Integer requestEstimate = trace.getPromptTokens();

        AgentTraceContext.recordModelResponse("first-output", 30, 20);

        assertThat(requestEstimate).isPositive();
        assertThat(trace.getPromptTokens()).isEqualTo(30);
        assertThat(trace.getCompletionTokens()).isEqualTo(20);
        assertThat(trace.getTotalTokens()).isEqualTo(50);
        assertThat(trace.getOutputSnapshot()).isNull();
    }

    @Test
    void failedParallelRequestKeepsPromptEstimateInTrace() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);

        CompletableFuture<Void> failed = CompletableFuture.supplyAsync(AgentTraceContext.wrap(() -> {
            AgentTraceContext.recordModelRequest(
                    "mimo-v2.5-pro",
                    new ChatRequest(List.of(ChatMessage.user("failed prompt text")), null)
            );
            return null;
        }));
        CompletableFuture<Void> succeeded = CompletableFuture.supplyAsync(AgentTraceContext.wrap(() -> {
            AgentTraceContext.recordModelRequest(
                    "mimo-v2.5-pro",
                    new ChatRequest(List.of(ChatMessage.user("successful prompt text")), null)
            );
            AgentTraceContext.recordModelResponse("successful-output", 40, 60);
            return null;
        }));
        CompletableFuture.allOf(failed, succeeded).join();

        assertThat(trace.getPromptTokens()).isGreaterThan(40);
        assertThat(trace.getCompletionTokens()).isEqualTo(60);
        assertThat(trace.getTotalTokens()).isEqualTo(trace.getPromptTokens() + 60);
        assertThat(trace.getPrompt()).contains("failed prompt text", "successful prompt text");
    }

    @Test
    void processSummaryDoesNotChangeTokenUsageOrBusinessOutput() {
        AgentTrace trace = new AgentTrace();
        AgentTraceContext.start(trace);
        AgentTraceContext.recordModelResponse("model-output", 11, 22);

        AgentTraceContext.recordProcessSummary("Parallel subtasks succeeded.");

        assertThat(trace.getPromptTokens()).isEqualTo(11);
        assertThat(trace.getCompletionTokens()).isEqualTo(22);
        assertThat(trace.getTotalTokens()).isEqualTo(33);
        assertThat(trace.getRawModelOutput()).isEqualTo("model-output");
        assertThat(trace.getOutputSnapshot()).isNull();
        assertThat(trace.getProcessSnapshot()).contains("Parallel subtasks");
    }
}
