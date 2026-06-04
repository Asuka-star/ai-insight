package com.aiinsight.model.run;

import com.aiinsight.model.enums.AgentName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class ReviewRepairDelta {

    private AgentName agentName;
    private boolean changed;
    private int findingsBefore;
    private int findingsAfter;
    private int evidenceSourcesBefore;
    private int evidenceSourcesAfter;
    private int claimsBefore;
    private int claimsAfter;
    private int artifactsBefore;
    private int artifactsAfter;
    private String claimsFingerprintBefore;
    private String claimsFingerprintAfter;
    private String reportFingerprintBefore;
    private String reportFingerprintAfter;
    private String evidenceFingerprintBefore;
    private String evidenceFingerprintAfter;
    private String profileFingerprintBefore;
    private String profileFingerprintAfter;
    private String factFingerprintBefore;
    private String factFingerprintAfter;
    private Instant recordedAt = Instant.now();

    public boolean findingsDidNotImprove(int currentFindings) {
        return findingsBefore > 0 && currentFindings >= findingsBefore;
    }

    public boolean evidenceUnchanged() {
        return same(evidenceFingerprintBefore, evidenceFingerprintAfter)
                && evidenceSourcesBefore == evidenceSourcesAfter;
    }

    public boolean extractedStateUnchanged() {
        return same(profileFingerprintBefore, profileFingerprintAfter)
                && same(factFingerprintBefore, factFingerprintAfter);
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
