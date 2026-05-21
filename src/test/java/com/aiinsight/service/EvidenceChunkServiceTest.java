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
}
