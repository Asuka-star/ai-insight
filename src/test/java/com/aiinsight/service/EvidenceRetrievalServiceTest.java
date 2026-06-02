package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRetrievalServiceTest {

    @Test
    void retrievesTopEvidenceChunksByKeywordScore() {
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceChunks().add(new EvidenceChunk("S1-C1", "S1", 1, "价格页", "https://example.com/pricing", "团队版和企业版套餐价格信息"));
        run.getEvidenceChunks().add(new EvidenceChunk("S2-C1", "S2", 1, "功能页", "https://example.com/features", "文档协同和权限管理能力"));

        var results = new EvidenceRetrievalService().retrieve(run, "价格 套餐", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(results.get(0).getScore()).isPositive();
    }

    @Test
    void hybridRetrievalBoostsOfficialPricingChunksForPricingDimensions() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk officialPricing = new EvidenceChunk("S1-C1", "S1", 1, "Notion Pricing", "https://www.notion.com/pricing", "Business plan pricing and Enterprise contact sales details.");
        officialPricing.setContentKind("pricing");
        officialPricing.setSourceAuthority("FIRST_PARTY_OFFICIAL");
        officialPricing.setSourceQuality("HIGH");
        EvidenceChunk thirdPartyPricing = new EvidenceChunk("S2-C1", "S2", 1, "Notion pricing comparison", "https://example-blog.com/notion-pricing", "Notion pricing comparison and commentary.");
        thirdPartyPricing.setContentKind("pricing");
        thirdPartyPricing.setSourceAuthority("THIRD_PARTY_GENERAL");
        thirdPartyPricing.setSourceQuality("MEDIUM");
        run.getEvidenceChunks().add(thirdPartyPricing);
        run.getEvidenceChunks().add(officialPricing);

        var results = new EvidenceRetrievalService().retrieve(run, "Notion pricing", "Notion", "pricing and plans", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(results.get(0).getScore()).isGreaterThan(results.get(1).getScore());
    }

    @Test
    void hybridRetrievalRecallsPermissionChunksWithSemanticKeywordExpansion() {
        AnalysisRun run = new AnalysisRun();
        EvidenceChunk permissionChunk = new EvidenceChunk("S3-C1", "S3", 1, "Confluence admin controls", "https://support.atlassian.com/confluence", "SAML SSO, SCIM, audit logs, role based admin controls.");
        permissionChunk.setContentKind("permission");
        permissionChunk.setSourceAuthority("FIRST_PARTY_DOCS");
        permissionChunk.setSourceQuality("HIGH");
        run.getEvidenceChunks().add(permissionChunk);

        var results = new EvidenceRetrievalService().retrieve(run, "企业权限", "Confluence", "权限协作", 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChunkKey()).isEqualTo("S3-C1");
        assertThat(results.get(0).getScore()).isPositive();
    }
}
