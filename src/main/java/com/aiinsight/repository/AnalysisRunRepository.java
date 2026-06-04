package com.aiinsight.repository;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.model.run.EmbeddingCacheEntry;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;

import java.time.Duration;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRunRepository {

    AnalysisRun save(AnalysisRun run);

    Optional<AnalysisRun> findById(UUID id);

    boolean existsById(UUID id);

    Collection<AnalysisRun> findAll();

    Collection<AnalysisRunSummary> findSummaries();

    default Optional<List<EvidenceChunk>> retrieveEvidenceByVector(UUID runId,
                                                                   List<Double> queryEmbedding,
                                                                   String embeddingModel,
                                                                   int topK) {
        return Optional.empty();
    }

    // 全局 RAG 相关方法默认 no-op，方便测试仓库或未来的轻量仓库实现只关心 run 本身。
    // Postgres 实现会把全局用户资源单独落到 global_evidence_* 表，供所有分析任务共享。
    default void saveGlobalEvidence(EvidenceSource source, List<EvidenceChunk> chunks) {
    }

    default void deleteGlobalEvidence(String globalUrl) {
    }

    default boolean globalEvidenceExists(String globalUrl) {
        return true;
    }

    default List<EvidenceChunk> findGlobalEvidenceChunks(int limit) {
        return List.of();
    }

    default Optional<List<EvidenceChunk>> retrieveGlobalEvidenceByVector(List<Double> queryEmbedding,
                                                                         String embeddingModel,
                                                                         int topK) {
        return Optional.empty();
    }

    default Map<String, EmbeddingCacheEntry> findCachedEmbeddings(Collection<String> inputHashes,
                                                                 String embeddingModel,
                                                                 int dimensions) {
        return Map.of();
    }

    default void saveCachedEmbedding(EmbeddingCacheEntry entry) {
    }

    default int deleteExpiredEmbeddingCache(Duration ttl) {
        return 0;
    }

    void deleteById(UUID id);
}
