package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SourceCollectionService {

    private static final int SNIPPET_LENGTH = 220;

    private final WebPageFetchService webPageFetchService;

    public List<EvidenceSource> collect(AnalysisRun run, boolean recollecting) {
        List<EvidenceSource> sources = new ArrayList<>();
        int index = 1;
        for (String url : run.getRequirement().getSourceUrls()) {
            EvidenceSource source = fromUrl("S" + index, url);
            if (source != null) {
                sources.add(source);
                index++;
            }
        }
        if (sources.isEmpty()) {
            index = appendSeedEvidence(run, sources, index);
        }
        if (recollecting) {
            appendSupplementalEvidence(run, sources, index);
        }
        return sources;
    }

    private EvidenceSource fromUrl(String citationKey, String url) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!page.isUsable() || !StringUtils.hasText(page.getRawText())) {
            return null;
        }
        return new EvidenceSource(
                citationKey,
                page.getTitle(),
                page.getUrl(),
                "public_web_page",
                snippet(page.getRawText()),
                page.getRawText(),
                page.getComplianceNote()
        );
    }

    private int appendSeedEvidence(AnalysisRun run, List<EvidenceSource> sources, int index) {
        for (String competitor : run.getRequirement().getCompetitors()) {
            String snippet = competitor + " 强调协作、知识沉淀、权限管理和 AI 辅助内容生成能力。";
            sources.add(new EvidenceSource(
                    "S" + index,
                    competitor + " 官方产品资料",
                    "https://example.com/" + competitor.toLowerCase().replace(" ", "-"),
                    "seed_official_site",
                    snippet,
                    snippet,
                    "MVP seed evidence used because no user-provided public source URL was available."
            ));
            index++;
        }
        return index;
    }

    private void appendSupplementalEvidence(AnalysisRun run, List<EvidenceSource> sources, int index) {
        for (String competitor : run.getRequirement().getCompetitors()) {
            String pricingSnippet = competitor + " 的价格页用于补充免费版、团队版、企业版等套餐信息，价格细节仍以页面原文为准。";
            sources.add(new EvidenceSource(
                    "S" + index,
                    competitor + " 价格页资料",
                    "https://example.com/" + competitor.toLowerCase().replace(" ", "-") + "/pricing",
                    "seed_pricing_page",
                    pricingSnippet,
                    pricingSnippet,
                    "MVP supplemental seed evidence used to simulate Reviewer-requested recollection."
            ));
            index++;
            String reviewSnippet = competitor + " 的用户评价用于补充上手成本、协作体验和 AI 功能满意度等信息。";
            sources.add(new EvidenceSource(
                    "S" + index,
                    competitor + " 用户评价资料",
                    "https://example.com/reviews/" + competitor.toLowerCase().replace(" ", "-"),
                    "seed_public_review",
                    reviewSnippet,
                    reviewSnippet,
                    "MVP supplemental seed evidence used to simulate Reviewer-requested recollection."
            ));
            index++;
        }
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH) + "...";
    }
}
