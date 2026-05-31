package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.ArtifactType;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.service.fallback.FallbackExtractionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractorNodeTest {

    @Test
    void acceptsJsonResponseWithTrailingCommentary() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        ```json
                        {
                          "profiles": [
                            {
                              "productName": "Cursor",
                              "companyName": "Cursor",
                              "positioning": "AI code editor",
                              "targetUsers": ["Developers"],
                              "features": [
                                {"name":"Composer","description":"Multi-file code editing","evidenceIds":["S1"]}
                              ],
                              "pricing": {
                                "strategySummary": "Pro plan is available",
                                "hasFreePlan": true,
                                "plans": [
                                  {"name":"Pro","priceText":"$20/month","billingCycle":"monthly","targetSegment":"Developers","includedFeatures":["Composer"],"evidenceIds":["S1"]}
                                ],
                                "evidenceIds": ["S1"]
                              },
                              "personas": [
                                {"name":"Developer","segment":"Software engineering","companySize":"Any","jobsToBeDone":["Edit code"],"painPoints":["Context switching"],"buyingConcerns":["Price"],"evidenceIds":["S1"]}
                              ],
                              "strengths": ["Multi-file editing"],
                              "weaknesses": ["Enterprise controls need verification"],
                              "evidenceIds": ["S1"]
                            }
                          ]
                        }
                        ```
                        以上 JSON 已按证据编号整理。
                        """;
            }
        };
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor",
                "AI coding tools",
                List.of("Cursor"),
                List.of("features"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product page",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "Cursor Composer supports multi-file code edits. Cursor Pro costs $20/month.",
                "test evidence"
        ));

        new ExtractorNode(llmClient, new FallbackExtractionFactory()).execute(run);

        assertThat(run.getRecommendedActions()).noneMatch(action -> action.contains("LLM Schema"));
        assertThat(run.getCompetitorProfiles()).hasSize(1);
        assertThat(run.getCompetitorProfiles().get(0).getFeatureTree().getRoots())
                .extracting(node -> node.getName())
                .containsExactly("Composer");
        assertThat(run.getArtifacts())
                .filteredOn(artifact -> artifact.getType() == ArtifactType.COMPETITOR_PROFILE)
                .last()
                .satisfies(artifact -> assertThat(artifact.getContent()).contains("Composer", "$20/month"));
    }
}
