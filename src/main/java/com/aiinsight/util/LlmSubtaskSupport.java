package com.aiinsight.util;

import com.aiinsight.observability.AgentTraceContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared types and helpers for parallel LLM subtask execution.
 * <p>
 * ResearcherNode, AnalystNode, and ReviewerNode each previously defined
 * their own private {@code LlmSubtask}, {@code LlmSubtaskResult}, and
 * {@code recordParallel*Trace} methods. This class consolidates them.
 */
@Slf4j
public final class LlmSubtaskSupport {

    private LlmSubtaskSupport() {
    }

    /** Functional interface for a single LLM subtask that may throw checked exceptions. */
    @FunctionalInterface
    public interface LlmSubtask<T> {
        T run() throws Exception;
    }

    /**
     * Outcome of a single parallel LLM subtask.
     * <p>
     * The {@link #succeeded()} method applies strict validation:
     * <ul>
     *   <li>{@code List} values must be non-empty</li>
     *   <li>{@code String} values must be non-blank</li>
     *   <li>All other types must be non-null</li>
     * </ul>
     * This unifies the previously divergent succeeded() logic across
     * ResearcherNode (null-check only), AnalystNode (list/string-aware),
     * and ReviewerNode (non-generic).
     */
    public record LlmSubtaskResult<T>(String name, T value, String errorMessage) {

        public boolean succeeded() {
            if (value instanceof List<?> list) {
                return !list.isEmpty() && errorMessage == null;
            }
            if (value instanceof String text) {
                return text != null && !text.isBlank() && errorMessage == null;
            }
            return value != null && errorMessage == null;
        }
    }

    /**
     * Executes a subtask, catching any exception and wrapping it in a result.
     *
     * @param agentLabel human-readable agent name for log messages (e.g. "Researcher", "Analyst")
     * @param name       subtask identifier (e.g. "claims", "questionnaire")
     * @param subtask    the callable to execute
     */
    public static <T> LlmSubtaskResult<T> runSubtask(String agentLabel, String name, LlmSubtask<T> subtask) {
        try {
            return new LlmSubtaskResult<>(name, subtask.run(), null);
        } catch (Exception ex) {
            log.warn("{} LLM subtask failed: name={}, exceptionType={}, message={}",
                    agentLabel, name, ex.getClass().getName(), ex.getMessage());
            return new LlmSubtaskResult<>(name, null, ex.getMessage());
        }
    }

    /**
     * Records a process-summary trace for a batch of parallel subtask results.
     *
     * @param label   trace label prefix (e.g. "Parallel Researcher LLM subtasks")
     * @param results the subtask results to summarize
     */
    public static void recordSubtaskTrace(String label, List<LlmSubtaskResult<?>> results) {
        String summary = results.stream()
                .map(result -> "%s=%s%s".formatted(
                        result.name(),
                        result.succeeded() ? "succeeded" : "failed",
                        result.succeeded() ? "" : " (" + result.errorMessage() + ")"
                ))
                .collect(Collectors.joining("\n"));
        AgentTraceContext.recordProcessSummary(label + ":\n" + summary);
    }
}
