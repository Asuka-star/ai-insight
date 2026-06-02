package com.aiinsight.repository;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.model.run.EvidenceChunk;

import java.util.List;
import java.util.Collection;
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

    void deleteById(UUID id);
}
