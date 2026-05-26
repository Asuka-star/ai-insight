package com.aiinsight.observability;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.model.run.AgentTrace;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public final class AgentTraceContext {

    private static final ThreadLocal<AgentTrace> CURRENT = new ThreadLocal<>();
    private static final double TOKEN_ESTIMATE_DIVISOR = 2.0;

    private AgentTraceContext() {
    }

    public static void start(AgentTrace trace) {
        CURRENT.set(trace);
    }

    public static Optional<AgentTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void recordModelRequest(String modelName, ChatRequest request) {
        current().ifPresent(trace -> {
            trace.setModelName(modelName);
            trace.setFallbackUsed(false);
            trace.setFallbackReason(null);
            trace.setPrompt(formatMessages(request.getMessages()));
            trace.setPromptTokens(estimateTokens(trace.getPrompt()));
        });
    }

    public static void recordModelResponse(String rawModelOutput, Integer promptTokens, Integer completionTokens) {
        current().ifPresent(trace -> {
            trace.setRawModelOutput(rawModelOutput);
            trace.setOutputSnapshot(summarize(rawModelOutput));
            if (promptTokens != null) {
                trace.setPromptTokens(promptTokens);
            }
            if (completionTokens != null) {
                trace.setCompletionTokens(completionTokens);
            } else {
                trace.setCompletionTokens(estimateTokens(rawModelOutput));
            }
            if (trace.getPromptTokens() != null && trace.getCompletionTokens() != null) {
                trace.setTotalTokens(trace.getPromptTokens() + trace.getCompletionTokens());
            }
        });
    }

    public static void recordFallback(String modelName, String output) {
        current().ifPresent(trace -> {
            boolean llmAttempted = trace.getPrompt() != null && !trace.getPrompt().isBlank()
                    && trace.getPromptTokens() != null && trace.getPromptTokens() > 0;
            trace.setModelName(modelName);
            trace.setFallbackUsed(true);
            if (trace.getPrompt() == null || trace.getPrompt().isBlank()) {
                trace.setPrompt("LLM unavailable; used deterministic fallback.");
                trace.setPromptTokens(0);
            }
            trace.setFallbackReason(llmAttempted
                    ? "LLM 已调用，但响应为空、异常或不可解析；当前节点改用规则兜底产物。"
                    : "LLM 未调用或不可用；当前节点直接使用规则兜底产物。");
            log.warn("Agent fallback recorded: agent={}, stepId={}, fallbackModel={}, llmAttempted={}, promptTokens={}, outputChars={}",
                    trace.getAgentName(),
                    trace.getStepId(),
                    modelName,
                    llmAttempted,
                    trace.getPromptTokens(),
                    output == null ? 0 : output.length());
            trace.setRawModelOutput(output);
            trace.setOutputSnapshot(summarize(output));
            trace.setCompletionTokens(estimateTokens(output));
            trace.setTotalTokens(trace.getPromptTokens() + trace.getCompletionTokens());
        });
    }

    public static void recordError(Throwable ex) {
        current().ifPresent(trace -> trace.setErrorMessage(ex.getMessage()));
    }

    private static String formatMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(message -> "%s: %s".formatted(message.getRole(), message.getContent()))
                .collect(Collectors.joining("\n\n"));
    }

    private static Integer estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int codePoints = text.codePointCount(0, text.length());
        return Math.max(1, (int) Math.ceil(codePoints / TOKEN_ESTIMATE_DIVISOR));
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 280) {
            return normalized;
        }
        return normalized.substring(0, 280) + "...";
    }
}
