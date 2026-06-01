package com.aiinsight.repository;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.dto.AnalysisRunSummary;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRunRepository {

    AnalysisRun save(AnalysisRun run);

    Optional<AnalysisRun> findById(UUID id);

    boolean existsById(UUID id);

    Collection<AnalysisRun> findAll();

    Collection<AnalysisRunSummary> findSummaries();

    void deleteById(UUID id);
}
