package com.aiinsight.service;

import com.aiinsight.model.run.EvidenceSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceChunkServiceTest {

    @Test
    void chunksEvidenceSourceAndKeepsCitationBinding() {
        EvidenceSource source = new EvidenceSource(
                "S1",
                "Notion product page",
                "https://www.notion.so/product",
                "public_web_page",
                "Notion AI docs",
                "Notion provides docs, wiki, project management and AI collaboration features for teams.",
                "robots.txt checked: allowed for public fetch."
        );

        var chunks = new EvidenceChunkService().chunk(List.of(source));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getChunkKey()).isEqualTo("S1-C1");
        assertThat(chunks.get(0).getSourceCitationKey()).isEqualTo("S1");
        assertThat(chunks.get(0).getText()).contains("AI collaboration");
    }

    @Test
    void buildsSectionAwareChunksWithSourceMetadata() {
        EvidenceSource source = new EvidenceSource(
                "S2",
                "Notion Pricing",
                "https://www.notion.com/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Business plan pricing",
                """
                        Pricing
                        Business plan includes private teamspaces and advanced permissions.

                        FAQ
                        What is included in Enterprise?
                        Enterprise includes SAML SSO, SCIM, audit log, and contact sales pricing.
                        """,
                "test evidence"
        );
        source.setSourceAuthority("FIRST_PARTY_OFFICIAL");

        var chunks = new EvidenceChunkService().chunk(List.of(source));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).getHeadingPath()).contains("Notion Pricing", "Pricing");
        assertThat(chunks.get(0).getContentKind()).isEqualTo("pricing");
        assertThat(chunks.get(0).getSourceAuthority()).isEqualTo("FIRST_PARTY_OFFICIAL");
        assertThat(chunks.get(0).getSourceQuality()).isEqualTo("HIGH");
        assertThat(chunks.get(0).getTextHash()).hasSize(64);
        assertThat(chunks)
                .anySatisfy(chunk -> {
                    assertThat(chunk.getHeadingPath()).contains("What is included in Enterprise?");
                    assertThat(chunk.getText()).contains("SAML SSO", "SCIM", "contact sales pricing");
                });
    }
}
