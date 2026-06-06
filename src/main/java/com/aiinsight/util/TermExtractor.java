package com.aiinsight.util;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TermExtractor {

    private TermExtractor() {
    }

    public record TermOptions(
            int minTermLength,
            Set<String> stopWords,
            boolean enableCrossLingualAliases,
            boolean enableChineseBigram
    ) {
        public TermOptions {
            minTermLength = Math.max(1, minTermLength);
            stopWords = stopWords == null ? Set.of() : Set.copyOf(stopWords);
        }

        public static TermOptions basic(int minTermLength) {
            return new TermOptions(minTermLength, Set.of(), false, true);
        }

        public static TermOptions support(int minTermLength, Set<String> stopWords) {
            return new TermOptions(minTermLength, stopWords, false, true);
        }

        public static TermOptions supportWithAliases(int minTermLength, Set<String> stopWords) {
            return new TermOptions(minTermLength, stopWords, true, true);
        }
    }

    public static Set<String> extract(String text, TermOptions options) {
        TermOptions termOptions = options == null ? TermOptions.basic(3) : options;
        Set<String> terms = new LinkedHashSet<>();
        if (!AgentUtils.hasText(text)) {
            return terms;
        }
        String normalized = AgentUtils.normalizeLower(text)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= termOptions.minTermLength() && !termOptions.stopWords().contains(part)) {
                terms.add(part);
            }
        }
        if (termOptions.enableChineseBigram()) {
            String chineseOnly = normalized.replaceAll("[^\\p{IsHan}]", "");
            for (int i = 0; i < chineseOnly.length() - 1; i++) {
                String term = chineseOnly.substring(i, i + 2);
                if (!termOptions.stopWords().contains(term)) {
                    terms.add(term);
                }
            }
        }
        if (termOptions.enableCrossLingualAliases()) {
            addCrossLingualSupportTerms(text, terms);
        }
        return terms;
    }

    private static void addCrossLingualSupportTerms(String text, Set<String> terms) {
        String normalized = AgentUtils.normalizeLower(text);
        addAliasesIfContains(normalized, terms, List.of("权限", "permission", "permissions"), "permission", "permissions", "admin");
        addAliasesIfContains(normalized, terms, List.of("审计", "audit", "auditing"), "audit", "auditing");
        addAliasesIfContains(normalized, terms, List.of("治理", "governance"), "governance");
        addAliasesIfContains(normalized, terms, List.of("企业", "enterprise"), "enterprise");
        addAliasesIfContains(normalized, terms, List.of("控制", "controls", "control"), "controls", "control");
        addAliasesIfContains(normalized, terms, List.of("管理员", "admin", "administrator"), "admin", "administrator");
        addAliasesIfContains(normalized, terms, List.of("官方", "official"), "official");
        addAliasesIfContains(normalized, terms, List.of("文档", "docs", "documentation"), "docs", "documentation");
        addAliasesIfContains(normalized, terms, List.of("搜索", "search"), "search");
        addAliasesIfContains(normalized, terms, List.of("ai", "人工智能", "智能"), "ai");
        addAliasesIfContains(normalized, terms, List.of("能力", "capability", "capabilities"), "capability", "capabilities");
        addAliasesIfContains(normalized, terms, List.of("路线图", "roadmap", "规划", "planning"), "roadmap", "planning");
        addAliasesIfContains(normalized, terms, List.of("优先", "prioritize", "priority"), "prioritize", "priority");
    }

    private static void addAliasesIfContains(String normalized, Set<String> terms, List<String> needles, String... aliases) {
        if (needles.stream().anyMatch(normalized::contains)) {
            terms.addAll(List.of(aliases));
        }
    }
}
