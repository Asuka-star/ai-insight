package com.aiinsight.model.schema;

import com.aiinsight.model.enums.ResearchSubtaskPriority;
import com.aiinsight.model.enums.ResearchSubtaskStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ResearchSubtask {

    private UUID id = UUID.randomUUID();
    private UUID runId;
    private String competitorName;
    private String dimension;
    private List<String> queries = new ArrayList<>();
    private List<String> sourcePreferences = new ArrayList<>();
    private ResearchSubtaskStatus status = ResearchSubtaskStatus.PENDING;
    private ResearchSubtaskPriority priority = ResearchSubtaskPriority.NORMAL_SEARCH;
    private int attempt;
    private int candidateUrlCount;
    private int fetchedPageCount;
    private int acceptedEvidenceCount;
    private String failureReason;
    private long searchLatencyMs;
    private long fetchLatencyMs;
    private long ragLatencyMs;
    private Instant startedAt;
    private Instant finishedAt;
}
