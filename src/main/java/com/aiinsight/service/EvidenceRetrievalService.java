package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EvidenceRetrievalService {

    private static final int DEFAULT_TOP_K = 5;

    public List<EvidenceChunk> retrieve(AnalysisRun run, String query, Integer topK) {
        int limit = topK == null || topK <= 0 ? DEFAULT_TOP_K : topK;
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return run.getEvidenceChunks().stream()
                    .limit(limit)
                    .map(this::copy)
                    .toList();
        }
        return run.getEvidenceChunks().stream()
                .map(chunk -> scoredCopy(chunk, queryTerms))
                .filter(chunk -> chunk.getScore() > 0)
                .sorted(Comparator.comparingDouble(EvidenceChunk::getScore).reversed())
                .limit(limit)
                .toList();
    }

    private EvidenceChunk scoredCopy(EvidenceChunk chunk, Set<String> queryTerms) {
        EvidenceChunk copy = copy(chunk);
        String haystack = (chunk.getTitle() + " " + chunk.getText()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : queryTerms) {
            if (haystack.contains(term)) {
                score += term.length() <= 2 ? 0.5 : 1.0;
            }
        }
        copy.setScore(score);
        return copy;
    }

    private EvidenceChunk copy(EvidenceChunk chunk) {
        EvidenceChunk copy = new EvidenceChunk(
                chunk.getChunkKey(),
                chunk.getSourceCitationKey(),
                chunk.getChunkIndex(),
                chunk.getTitle(),
                chunk.getUrl(),
                chunk.getText()
        );
        copy.setId(chunk.getId());
        copy.setScore(chunk.getScore());
        copy.setCreatedAt(chunk.getCreatedAt());
        return copy;
    }

    private Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(query)) {
            return terms;
        }
        String normalized = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        terms.addAll(chineseBigrams(normalized));
        return terms;
    }

    private List<String> chineseBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        String chineseOnly = text.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i < chineseOnly.length() - 1; i++) {
            bigrams.add(chineseOnly.substring(i, i + 2));
        }
        return bigrams;
    }
}
