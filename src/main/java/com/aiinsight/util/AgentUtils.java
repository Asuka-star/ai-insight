package com.aiinsight.util;

import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.run.AnalysisArtifact;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Shared utility methods across all agent nodes.
 * Eliminates duplicated private helpers (nullToEmpty, abbreviate, sanitizeCitationText, etc.)
 * that were previously copy-pasted into ClarifierNode, ResearcherNode, ExtractorNode,
 * AnalystNode, WriterNode, ReviewerNode, and WorkflowNodeExecutor.
 */
public final class AgentUtils {

    private AgentUtils() {
    }

    /** Compiled pattern matching evidence citation references like {@code [S1]}, {@code [S12]}. */
    public static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    // ───────────────────────── String utilities ─────────────────────────

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Returns trimmed value when non-blank, otherwise fallback.
     * Unifies the slightly different implementations across nodes
     * (ExtractorNode trimmed, WriterNode did not).
     */
    public static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static String textOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    /**
     * Normalizes whitespace, then truncates to maxLength with "..." suffix.
     * The WriterNode variant previously skipped whitespace normalization and
     * returned a domain-specific fallback; callers that need that behaviour
     * should guard with {@link #hasText} before calling.
     */
    public static String abbreviate(String value, int maxLength) {
        String normalized = nullToEmpty(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public static String normalizeLower(String value) {
        return nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeUpper(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    public static boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null
                && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    public static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text != null && pattern != null
                    && text.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    // ───────────────────── Citation utilities ─────────────────────

    /** Collects all citation keys (S1, S2, ...) currently registered on the run. */
    public static Set<String> knownCitationKeys(AnalysisRun run) {
        return run.getEvidenceSources().stream()
                .map(EvidenceSource::getCitationKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Replaces unknown {@code [Sx]} references in text with "证据不足".
     * Used by Analyst (matrix/SWOT), Writer (report), and any downstream node
     * that renders citation-bearing Markdown.
     */
    public static String sanitizeCitationText(AnalysisRun run, String text) {
        if (!hasText(text)) {
            return "";
        }
        Set<String> known = knownCitationKeys(run);
        Matcher matcher = CITATION_PATTERN.matcher(text);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (known.contains(key)) {
                matcher.appendReplacement(sanitized, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(sanitized, "证据不足");
            }
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    // ───────────────────── Artifact utilities ─────────────────────

    /** Returns the most recent artifact of the given type, searching from the tail. */
    public static Optional<AnalysisArtifact> latestArtifact(List<AnalysisArtifact> artifacts, ArtifactType type) {
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            AnalysisArtifact artifact = artifacts.get(i);
            if (artifact.getType() == type) {
                return Optional.of(artifact);
            }
        }
        return Optional.empty();
    }

    // ───────────────────── Review utilities ─────────────────────

    public static long countBySeverity(List<ReviewFinding> findings, ReviewSeverity severity) {
        return findings.stream()
                .filter(finding -> finding.getSeverity() == severity)
                .count();
    }
}
