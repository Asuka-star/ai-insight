package com.aiinsight.agent.research;

import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;

import java.util.List;

public record ResearchAgentResult(
        ResearchAgentPlan plan,
        List<EvidenceSource> evidenceSources,
        List<EvidenceChunk> evidenceChunks,
        List<String> missingEvidenceTypes,
        List<ResearchObservation> observations,
        ResearchAgentDecision decision,
        String traceMarkdown
) {
}
