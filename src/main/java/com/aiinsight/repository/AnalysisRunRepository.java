package com.aiinsight.repository;

import com.aiinsight.model.run.AnalysisRun;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRunRepository {

    AnalysisRun save(AnalysisRun run);

    Optional<AnalysisRun> findById(UUID id);

    Collection<AnalysisRun> findAll();
}
