package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SourceCollectionService {

    private static final int SNIPPET_LENGTH = 220;
    // Built-in catalog entries are public reference anchors, not live fetch results.
    // User-provided sourceUrls still go through WebPageFetchService and robots handling.
    private static final Map<String, PublicSourceCatalog> PUBLIC_SOURCE_CATALOG = Map.of(
            "notion", new PublicSourceCatalog(
                    "https://www.notion.com/product",
                    "https://www.notion.com/pricing",
                    "https://www.notion.com/help",
                    "Notion 官方公开页面可用于验证文档、知识库、项目协作和 AI 工作流能力。",
                    "Notion 官方价格页可用于验证 Free、Plus、Business、Enterprise 等套餐线索。",
                    "Notion 帮助中心可用于补充用户上手、权限、协作和 AI 功能使用反馈。"
            ),
            "飞书文档", new PublicSourceCatalog(
                    "https://docs.feishu.cn/",
                    "https://www.feishu.cn/",
                    "https://www.feishu.cn/content/feishu-documents-new-revision-mode-meet-professional-needs-boost-collaboration-efficiency",
                    "飞书文档官方页面可用于验证文档、表格、思维笔记和多人协作能力。",
                    "飞书官网可作为商业版本和企业服务信息的入口，具体价格需以后续页面或人工资料为准。",
                    "飞书官网内容页可补充修订模式、专业写作和协作场景反馈。"
            ),
            "confluence", new PublicSourceCatalog(
                    "https://www.atlassian.com/software/confluence",
                    "https://www.atlassian.com/software/confluence/pricing",
                    "https://www.atlassian.com/software/confluence/resources",
                    "Atlassian Confluence 官方页面可用于验证团队工作空间、知识沉淀和远程协作能力。",
                    "Atlassian Confluence 官方价格页可用于验证 Free、Standard、Premium、Enterprise 等套餐线索。",
                    "Atlassian Confluence 资源页可补充团队协作、知识管理和使用场景材料。"
            ),
            "airtable", new PublicSourceCatalog(
                    "https://www.airtable.com/product",
                    "https://www.airtable.com/pricing",
                    "https://support.airtable.com/docs/en/airtable-plans",
                    "Airtable 官方产品页可用于验证数据表、自动化、界面和协作能力。",
                    "Airtable 官方价格页可用于验证 Free、Team、Business、Enterprise 等套餐线索。",
                    "Airtable 支持文档可补充套餐能力、限制和上手使用信息。"
            ),
            "语雀", new PublicSourceCatalog(
                    "https://www.yuque.com/",
                    "https://www.yuque.com/",
                    "https://www.yuque.com/yuque/help",
                    "语雀官网可用于验证知识库、文档协作和团队知识管理能力。",
                    "语雀官网可作为商业版本信息入口，具体价格需以后续页面或人工资料为准。",
                    "语雀帮助中心可补充上手、权限、知识库组织和协作使用信息。"
            ),
            "腾讯文档", new PublicSourceCatalog(
                    "https://docs.qq.com/",
                    "https://docs.qq.com/",
                    "https://docs.qq.com/",
                    "腾讯文档官网可用于验证在线文档、表格、收集表和多人协作能力。",
                    "腾讯文档官网可作为版本能力和商业信息入口，具体价格需以后续页面或人工资料为准。",
                    "腾讯文档官网可补充协作体验、模板和团队使用场景信息。"
            )
    );

    private final WebPageFetchService webPageFetchService;

    public List<EvidenceSource> collect(AnalysisRun run, boolean recollecting) {
        List<EvidenceSource> sources = new ArrayList<>();
        int index = 1;
        // Preserve user-supplied material first so explicit evidence keeps the lowest citation keys.
        for (UserProvidedEvidence evidence : run.getUserProvidedEvidence()) {
            sources.add(fromUserProvidedEvidence("S" + index, evidence));
            index++;
        }
        for (String url : run.getRequirement().getSourceUrls()) {
            EvidenceSource source = fromUrl("S" + index, url);
            if (source != null) {
                sources.add(source);
                index++;
            } else {
                run.getRecommendedActions().add("公开 URL 采集失败，已跳过并尝试使用内置公开来源候选补齐：" + url);
            }
        }
        index = appendCatalogEvidence(run, sources, index, recollecting);
        if (recollecting) {
            appendSupplementalEvidence(run, sources, index);
        }
        return sources;
    }

    public EvidenceSource fromUserProvidedEvidence(String citationKey, UserProvidedEvidence evidence) {
        String sourceType = StringUtils.hasText(evidence.getSourceType()) ? evidence.getSourceType() : "note";
        String url = StringUtils.hasText(evidence.getUrl())
                ? evidence.getUrl()
                : "user-evidence://" + evidence.getId();
        String complianceNote = evidence.isSensitive()
                ? "User-provided sensitive source. Treat as internal-only evidence and avoid public redistribution."
                : "User-provided source. Use only for this analysis run.";
        return new EvidenceSource(
                citationKey,
                evidence.getTitle(),
                url,
                "user_" + sourceType,
                "USER_PROVIDED",
                evidence.isSensitive() ? "INTERNAL_ONLY" : "USER_PROVIDED",
                snippet(evidence.getContent()),
                evidence.getContent(),
                complianceNote
        );
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
                page.getStatus(),
                "LIVE_FETCHED",
                snippet(page.getRawText()),
                page.getRawText(),
                page.getComplianceNote()
        );
    }

    private int appendCatalogEvidence(AnalysisRun run, List<EvidenceSource> sources, int index, boolean recollecting) {
        for (String competitor : run.getRequirement().getCompetitors()) {
            PublicSourceCatalog catalog = catalogFor(competitor);
            if (catalog == null) {
                if (!hasCompetitorCoverage(sources, competitor)) {
                    sources.add(seedSource("S" + index, competitor, "official_site"));
                    index++;
                }
                continue;
            }
            if (!hasCompetitorCoverage(sources, competitor)) {
                sources.add(fromCatalogCandidate(
                        "S" + index,
                        competitor + " 官方产品资料",
                        catalog.officialUrl(),
                        "catalog_reference_official_site",
                        catalog.officialSnippet()
                ));
                index++;
            }
            if (shouldCollectPricing(run, recollecting) && !hasSourceTypeForCompetitor(sources, competitor, "pricing")) {
                sources.add(fromCatalogCandidate(
                        "S" + index,
                        competitor + " 价格页资料",
                        catalog.pricingUrl(),
                        "catalog_reference_pricing_page",
                        catalog.pricingSnippet()
                ));
                index++;
            }
            if (shouldCollectFeedback(run, recollecting) && !hasSourceTypeForCompetitor(sources, competitor, "feedback")) {
                sources.add(fromCatalogCandidate(
                        "S" + index,
                        competitor + " 使用反馈资料",
                        catalog.feedbackUrl(),
                        "catalog_reference_usage_feedback",
                        catalog.feedbackSnippet()
                ));
                index++;
            }
        }
        return index;
    }

    private void appendSupplementalEvidence(AnalysisRun run, List<EvidenceSource> sources, int index) {
        for (String competitor : run.getRequirement().getCompetitors()) {
            PublicSourceCatalog catalog = catalogFor(competitor);
            // Supplemental evidence mirrors Reviewer recollection needs, but catalog_reference
            // compliance notes make clear that freshness must be verified before submission.
            String pricingSnippet = catalog == null
                    ? competitor + " 的价格页用于补充免费版、团队版、企业版等套餐信息，价格细节仍以页面原文为准。"
                    : catalog.pricingSnippet();
            if (!hasSourceTypeForCompetitor(sources, competitor, "pricing")) {
                sources.add(catalog == null
                        ? seedSource("S" + index, competitor, "pricing_page")
                        : fromCatalogCandidate("S" + index, competitor + " 价格页资料", catalog.pricingUrl(),
                        "catalog_reference_pricing_page", pricingSnippet));
                index++;
            }
            String reviewSnippet = catalog == null
                    ? competitor + " 的用户评价用于补充上手成本、协作体验和 AI 功能满意度等信息。"
                    : catalog.feedbackSnippet();
            if (!hasSourceTypeForCompetitor(sources, competitor, "feedback")) {
                sources.add(catalog == null
                        ? seedSource("S" + index, competitor, "public_review")
                        : fromCatalogCandidate("S" + index, competitor + " 用户评价资料", catalog.feedbackUrl(),
                        "catalog_reference_usage_feedback", reviewSnippet));
                index++;
            }
        }
    }

    private EvidenceSource fromCatalogCandidate(String citationKey,
                                                String title,
                                                String url,
                                                String fallbackSourceType,
                                                String fallbackSnippet) {
        WebPageFetchService.FetchedPage page;
        try {
            page = webPageFetchService.fetch(url);
        } catch (RuntimeException ex) {
            page = WebPageFetchService.FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage());
        }
        if (page.isUsable() && StringUtils.hasText(page.getRawText())) {
            return new EvidenceSource(
                    citationKey,
                    page.getTitle(),
                    page.getUrl(),
                    "public_web_page",
                    page.getStatus(),
                    "LIVE_FETCHED",
                    snippet(page.getRawText()),
                    page.getRawText(),
                    page.getComplianceNote()
            );
        }
        String complianceNote = "Live fetch status=" + page.getStatus()
                + ". Falling back to built-in public source catalog reference. "
                + "The URL is an official public entry point; verify page freshness before final submission. "
                + page.getComplianceNote();
        return new EvidenceSource(
                citationKey,
                title,
                url,
                fallbackSourceType,
                page.getStatus(),
                "CATALOG_REFERENCE",
                fallbackSnippet,
                fallbackSnippet,
                complianceNote
        );
    }

    private EvidenceSource seedSource(String citationKey, String competitor, String evidenceType) {
        String sourceType = switch (evidenceType) {
            case "pricing_page" -> "seed_pricing_page";
            case "public_review" -> "seed_public_review";
            default -> "seed_official_site";
        };
        String suffix = switch (evidenceType) {
            case "pricing_page" -> "/pricing";
            case "public_review" -> "/reviews";
            default -> "";
        };
        String title = switch (evidenceType) {
            case "pricing_page" -> competitor + " 价格页资料";
            case "public_review" -> competitor + " 用户评价资料";
            default -> competitor + " 官方产品资料";
        };
        String content = switch (evidenceType) {
            case "pricing_page" -> competitor + " 的价格页用于补充免费版、团队版、企业版等套餐信息，价格细节仍以页面原文为准。";
            case "public_review" -> competitor + " 的用户评价用于补充上手成本、协作体验和 AI 功能满意度等信息。";
            default -> competitor + " 强调协作、知识沉淀、权限管理和 AI 辅助内容生成能力。";
        };
        return new EvidenceSource(
                citationKey,
                title,
                fallbackUrl(competitor) + suffix,
                sourceType,
                "SEED_FALLBACK",
                "SYNTHETIC_SEED",
                content,
                content,
                "MVP seed fallback used because no user-provided URL or built-in public catalog entry was available."
        );
    }

    private boolean shouldCollectPricing(AnalysisRun run, boolean recollecting) {
        return recollecting
                || mentionsAny(run.getRequirement().getSourcePreferences(), "pricing", "价格", "定价")
                || mentionsAny(run.getRequirement().getDimensions(), "pricing", "价格", "定价", "商业模式");
    }

    private boolean shouldCollectFeedback(AnalysisRun run, boolean recollecting) {
        return recollecting
                || mentionsAny(run.getRequirement().getSourcePreferences(), "review", "评价", "反馈", "访谈", "问卷")
                || mentionsAny(run.getRequirement().getDimensions(), "review", "评价", "反馈", "用户");
    }

    private boolean hasCompetitorCoverage(List<EvidenceSource> sources, String competitor) {
        return sources.stream().anyMatch(source -> sourceMatchesCompetitor(source, competitor));
    }

    private boolean hasSourceTypeForCompetitor(List<EvidenceSource> sources, String competitor, String typeKeyword) {
        return sources.stream().anyMatch(source ->
                sourceMatchesCompetitor(source, competitor)
                        && containsIgnoreCase(source.getSourceType(), typeKeyword));
    }

    private boolean sourceMatchesCompetitor(EvidenceSource source, String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return false;
        }
        PublicSourceCatalog catalog = catalogFor(competitor);
        Set<String> needles = new LinkedHashSet<>();
        needles.add(competitor);
        if (catalog != null) {
            needles.add(host(catalog.officialUrl()));
            needles.add(host(catalog.pricingUrl()));
            needles.add(host(catalog.feedbackUrl()));
        }
        return needles.stream().anyMatch(needle ->
                containsIgnoreCase(source.getTitle(), needle)
                        || containsIgnoreCase(source.getUrl(), needle));
    }

    private boolean mentionsAny(List<String> values, String... patterns) {
        return values.stream().anyMatch(value -> {
            for (String pattern : patterns) {
                if (containsIgnoreCase(value, pattern)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean containsIgnoreCase(String text, String pattern) {
        return text != null && pattern != null && text.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private String host(String url) {
        String normalized = url.replaceFirst("^https?://", "");
        int slash = normalized.indexOf('/');
        return slash >= 0 ? normalized.substring(0, slash) : normalized;
    }

    private PublicSourceCatalog catalogFor(String competitor) {
        if (!StringUtils.hasText(competitor)) {
            return null;
        }
        String normalized = competitor.toLowerCase(Locale.ROOT);
        if (normalized.contains("notion")) {
            return PUBLIC_SOURCE_CATALOG.get("notion");
        }
        if (competitor.contains("飞书")) {
            return PUBLIC_SOURCE_CATALOG.get("飞书文档");
        }
        if (normalized.contains("confluence")) {
            return PUBLIC_SOURCE_CATALOG.get("confluence");
        }
        if (normalized.contains("airtable")) {
            return PUBLIC_SOURCE_CATALOG.get("airtable");
        }
        if (competitor.contains("语雀")) {
            return PUBLIC_SOURCE_CATALOG.get("语雀");
        }
        if (competitor.contains("腾讯文档")) {
            return PUBLIC_SOURCE_CATALOG.get("腾讯文档");
        }
        return null;
    }

    private String fallbackUrl(String competitor) {
        return "seed-evidence://" + competitor.toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    private String snippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH) + "...";
    }

    private record PublicSourceCatalog(String officialUrl,
                                       String pricingUrl,
                                       String feedbackUrl,
                                       String officialSnippet,
                                       String pricingSnippet,
                                       String feedbackSnippet) {
    }
}
