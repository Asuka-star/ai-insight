package com.aiinsight.agent.node;

import com.aiinsight.model.run.EvidenceSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvidenceSupportTest {

    @Test
    void rootDomainHandlesMultiPartSuffixes() {
        assertThat(AgentEvidenceSupport.rootDomain("docs.vendor.com.uk")).isEqualTo("vendor");
        assertThat(AgentEvidenceSupport.rootDomain("research.publisher.ac.cn")).isEqualTo("publisher");
    }

    @Test
    void securityRiskSignalIncludesChineseSingleSignOn() {
        assertThat(AgentEvidenceSupport.sourceTextHasRiskSignal("企业版支持单点登录和审计能力", "SECURITY")).isTrue();
    }

    @Test
    void evidenceSourceTextIncludesSourceType() {
        EvidenceSource source = new EvidenceSource(
                "S1",
                "Cursor plans",
                "https://example.test/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Plan details",
                "Raw content",
                "Compliance note"
        );

        assertThat(AgentEvidenceSupport.evidenceSourceText(source)).contains("pricing_page");
    }
}
