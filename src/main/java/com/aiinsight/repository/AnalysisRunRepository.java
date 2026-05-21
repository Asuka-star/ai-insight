package com.aiinsight.repository;

import com.aiinsight.domain.AnalysisRun;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class AnalysisRunRepository {

    private final ConcurrentMap<UUID, AnalysisRun> runs = new ConcurrentHashMap<>();

    public AnalysisRun save(AnalysisRun run) {
        run.touch();
        runs.put(run.getId(), run);
        return run;
    }

    public Optional<AnalysisRun> findById(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    public Collection<AnalysisRun> findAll() {
        return runs.values();
    }
}
