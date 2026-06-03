package com.aiinsight.dto;

import com.aiinsight.model.schema.ResearchCollectionPlan;
import com.aiinsight.model.schema.ResearchSubtask;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ResearchCollectionEvent {

    private UUID runId;
    private String type;
    private String message;
    private Instant occurredAt = Instant.now();
    private int totalSubtasks;
    private int succeededSubtasks;
    private int failedSubtasks;
    private int candidateUrlCount;
    private int acceptedEvidenceCount;
    private int coverageGapCount;
    private int repairTargetCount;
    private ResearchCollectionPlan plan;

    public static ResearchCollectionEvent of(UUID runId, String type, String message, ResearchCollectionPlan plan) {
        ResearchCollectionEvent event = new ResearchCollectionEvent();
        event.setRunId(runId);
        event.setType(type);
        event.setMessage(message);
        event.setPlan(plan);
        if (plan != null) {
            event.setTotalSubtasks(plan.getSubtasks() == null ? 0 : plan.getSubtasks().size());
            event.setSucceededSubtasks((int) (plan.getSubtasks() == null ? 0 : plan.getSubtasks().stream()
                    .filter(subtask -> subtask.getStatus() != null && "SUCCEEDED".equals(subtask.getStatus().name()))
                    .count()));
            event.setFailedSubtasks((int) (plan.getSubtasks() == null ? 0 : plan.getSubtasks().stream()
                    .filter(subtask -> subtask.getStatus() != null && "FAILED".equals(subtask.getStatus().name()))
                    .count()));
            event.setCandidateUrlCount(plan.getCandidateUrls() == null ? 0 : plan.getCandidateUrls().size());
            event.setAcceptedEvidenceCount(plan.getSubtasks() == null ? 0 : plan.getSubtasks().stream()
                    .mapToInt(ResearchSubtask::getAcceptedEvidenceCount)
                    .sum());
            event.setCoverageGapCount(plan.getCoverageGaps() == null ? 0 : plan.getCoverageGaps().size());
            event.setRepairTargetCount(plan.getRepairTargets() == null ? 0 : plan.getRepairTargets().size());
        }
        return event;
    }
}
