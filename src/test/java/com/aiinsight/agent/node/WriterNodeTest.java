package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.ClaimType;
import com.aiinsight.model.enums.ConfidenceLevel;
import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.schema.AnalysisClaim;
import com.aiinsight.service.fallback.FallbackReportDraftFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WriterNodeTest {

    @Test
    void writerPromptLabelsInternalDocumentsAndWarnsAgainstPublicEvidenceWording() {
        AtomicReference<String> promptCapture = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                promptCapture.set(request.getMessages().get(1).getContent());
                return "# Report\n\nUser-provided notes show permission governance matters [S1].";
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor"),
                List.of("permission governance"),
                List.of("user_document"),
                List.of()
        ));
        EvidenceSource source = new EvidenceSource(
                "S1",
                "Uploaded interview notes",
                "user-document://s1",
                "user_document",
                "USER_PROVIDED",
                "INTERNAL_ONLY",
                "INTERNAL_ONLY",
                "NONE",
                "Enterprise buyers care about permission governance.",
                "Enterprise buyers care about permission governance.",
                "Uploaded by user."
        );
        source.setSourceAuthority("INTERNAL_ONLY");
        run.getEvidenceSources().add(source);
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("Enterprise buyers care about permission governance.");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        assertThat(promptCapture.get())
                .contains("authority=INTERNAL_ONLY")
                .contains("quality=INTERNAL_ONLY")
                .contains("只能写成“用户提供资料/内部资料显示”")
                .contains("不要写成“公开资料显示”“市场证据显示”或“外部验证显示”");
    }
}
