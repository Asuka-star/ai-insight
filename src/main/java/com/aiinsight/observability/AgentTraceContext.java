package com.aiinsight.observability;

import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.model.run.AgentTrace;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public final class AgentTraceContext {

    private static final ThreadLocal<AgentTrace> CURRENT = new ThreadLocal<>();
    private static final double TOKEN_ESTIMATE_DIVISOR = 2.0;
    private static final Object USAGE_LOCK = new Object();
    private static final Map<AgentTrace, PromptUsageAccumulator> PROMPT_USAGE = new WeakHashMap<>();

    private AgentTraceContext() {
    }

    public static void start(AgentTrace trace) {
        CURRENT.set(trace);
    }

    public static Optional<AgentTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        AgentTrace trace = CURRENT.get();
        return () -> {
            AgentTrace previous = CURRENT.get();
            if (trace != null) {
                CURRENT.set(trace);
            }
            try {
                return supplier.get();
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        };
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void recordModelRequest(String modelName, ChatRequest request) {
        current().ifPresent(trace -> {
            synchronized (trace) {
                trace.setModelName(modelName);
                trace.setFallbackUsed(false);
                trace.setFallbackReason(null);
                trace.setPrompt(appendBlock(trace.getPrompt(), formatMessages(request.getMessages())));
                PromptUsageAccumulator accumulator = promptUsage(trace);
                accumulator.pendingEstimates.addLast(estimateTokens(formatMessages(request.getMessages())));
                trace.setPromptTokens(accumulator.totalPromptTokens());
                refreshTotalTokens(trace);
            }
        });
    }

    public static void recordModelResponse(String rawModelOutput, Integer promptTokens, Integer completionTokens) {
        current().ifPresent(trace -> {
            synchronized (trace) {
                trace.setRawModelOutput(appendBlock(trace.getRawModelOutput(), rawModelOutput));
                PromptUsageAccumulator accumulator = promptUsage(trace);
                if (promptTokens != null && promptTokens > 0) {
                    if (!accumulator.pendingEstimates.isEmpty()) {
                        accumulator.pendingEstimates.removeFirst();
                    }
                    accumulator.actualPromptTokens += promptTokens;
                }
                trace.setPromptTokens(accumulator.totalPromptTokens());
                trace.setCompletionTokens(add(
                        trace.getCompletionTokens(),
                        completionTokens == null ? estimateTokens(rawModelOutput) : completionTokens
                ));
                refreshTotalTokens(trace);
            }
        });
    }

    public static void recordOutputSummary(String outputSummary) {
        recordProcessSummary(outputSummary);
    }

    public static void recordProcessSummary(String processSummary) {
        current().ifPresent(trace -> {
            synchronized (trace) {
                trace.setProcessSnapshot(appendBlock(trace.getProcessSnapshot(), processSummary));
            }
        });
    }

    public static void recordFallback(String modelName, String output) {
        current().ifPresent(trace -> {
            synchronized (trace) {
                boolean llmAttempted = trace.getPrompt() != null && !trace.getPrompt().isBlank();
                boolean hasRawModelOutput = trace.getRawModelOutput() != null && !trace.getRawModelOutput().isBlank();
                boolean hasCompletionTokens = trace.getCompletionTokens() != null && trace.getCompletionTokens() > 0;
                trace.setModelName(modelName);
                trace.setFallbackUsed(true);
                if (trace.getPrompt() == null || trace.getPrompt().isBlank()) {
                    trace.setPrompt("LLM unavailable; used deterministic fallback.");
                    trace.setPromptTokens(0);
                } else if (trace.getPromptTokens() == null) {
                    trace.setPromptTokens(estimateTokens(trace.getPrompt()));
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
                if (!hasRawModelOutput) {
                    trace.setRawModelOutput(output);
                }
                if (!hasCompletionTokens) {
                    trace.setCompletionTokens(estimateTokens(output));
                }
                refreshTotalTokens(trace);
            }
        });
    }

    public static void recordError(Throwable ex) {
        current().ifPresent(trace -> {
            synchronized (trace) {
                trace.setErrorMessage(ex.getMessage());
            }
        });
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

    private static Integer add(Integer current, Integer delta) {
        if (delta == null) {
            return current;
        }
        return (current == null ? 0 : current) + delta;
    }

    private static void refreshTotalTokens(AgentTrace trace) {
        if (trace.getPromptTokens() != null && trace.getCompletionTokens() != null) {
            trace.setTotalTokens(trace.getPromptTokens() + trace.getCompletionTokens());
        }
    }

    private static PromptUsageAccumulator promptUsage(AgentTrace trace) {
        synchronized (USAGE_LOCK) {
            return PROMPT_USAGE.computeIfAbsent(trace, ignored -> new PromptUsageAccumulator());
        }
    }

    private static String appendBlock(String existing, String next) {
        if (next == null || next.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return next;
        }
        return existing + "\n\n--- LLM call ---\n\n" + next;
    }

    private static final class PromptUsageAccumulator {
        private final Deque<Integer> pendingEstimates = new ArrayDeque<>();
        private int actualPromptTokens;

        private int totalPromptTokens() {
            return actualPromptTokens + pendingEstimates.stream().mapToInt(Integer::intValue).sum();
        }
    }
}
