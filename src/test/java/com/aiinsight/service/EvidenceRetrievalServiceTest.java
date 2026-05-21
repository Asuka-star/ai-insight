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
}
