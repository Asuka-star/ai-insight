package com.aiinsight.agent.node;

import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.model.enums.ArtifactType;
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

    @Test
    void writerPromptLabelsGlobalUserResourcesAsUserProvided() {
        AtomicReference<String> promptCapture = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                promptCapture.set(request.getMessages().get(1).getContent());
                return "# Report\n\n用户资源包显示企业买家关注权限治理 [S1].";
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
                "Workspace note",
                "global-document://workspace-note",
                "user_document_markdown",
                "USER_PROVIDED",
                "USER_PROVIDED",
                "MEDIUM",
                "NONE",
                "Enterprise buyers care about permission governance.",
                "Enterprise buyers care about permission governance.",
                "来自用户资源包/用户上传文档。"
        );
        source.setSourceAuthority("USER_PROVIDED");
        source.setGlobalResource(true);
        run.getEvidenceSources().add(source);
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.OPPORTUNITY);
        claim.setContent("Enterprise buyers care about permission governance.");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        assertThat(promptCapture.get())
                .contains("type=user_document_markdown | authority=USER_PROVIDED | quality=MEDIUM | status=USER_PROVIDED")
                .contains("如果证据 authority/quality 为 USER_PROVIDED 或 INTERNAL_ONLY");
    }

    @Test
    void writerPromptUsesSingleReusableReportStructure() {
        AtomicReference<String> promptCapture = new AtomicReference<>();
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                promptCapture.set(request.getMessages().get(1).getContent());
                return """
                        # Report

                        ## 一句话结论
                        基于当前证据，先聚焦可验证能力 [S1]。

                        ## 建议优先级
                        | 建议 | 理由 | 证据 | 置信度 | 下一步 |
                        | --- | --- | --- | --- | --- |
                        | 验证核心能力 | 有官方资料支撑 | [S1] | MEDIUM | PoC |

                        ## 结论与建议
                        - 首选借鉴：验证核心能力 [S1]。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI tools",
                "developer tools",
                List.of("Tool A", "Tool B"),
                List.of("workflow", "security"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Tool A docs",
                "https://example.test/tool-a",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Tool A documents workflow capabilities.",
                "Tool A documents workflow capabilities.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Tool A documents workflow capabilities.");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        assertThat(promptCapture.get())
                .contains("报告必须采用单一骨架")
                .contains("全文只能有一个对比矩阵章节")
                .contains("标题统一为“竞品能力矩阵”")
                .contains("以上章节标题都必须出现")
                .contains("必须保留且必须显式输出“机会与风险摘要（SWOT）”章节")
                .contains("机会与风险摘要（SWOT）")
                .contains("不要再输出长篇四象限“SWOT 分析”章节")
                .contains("不要把 SWOT 合并进风险与证据缺口")
                .contains("不要重复展开风险清单或行动计划")
                .doesNotContain("必须追加\"竞品横向矩阵\"")
                .doesNotContain("必须追加\"SWOT 分析\"");
    }

    @Test
    void fallbackReportUsesSingleNonDuplicatedStructure() {
        WriterNode writer = new WriterNode(new LlmClient() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public String complete(ChatRequest request) {
                throw new IllegalStateException("LLM unavailable");
            }
        }, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI tools",
                "developer tools",
                List.of("Tool A", "Tool B"),
                List.of("workflow"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Tool A docs",
                "https://example.test/tool-a",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Tool A documents workflow capabilities.",
                "Tool A documents workflow capabilities.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Tool A 在工作流能力上有官方资料支撑。");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setDimension("workflow");
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .contains("## 竞品能力矩阵")
                .contains("## 机会与风险摘要（SWOT）")
                .contains("## 风险与证据缺口")
                .contains("## 下一步验证计划")
                .contains("## 结论与建议")
                .doesNotContain("## 竞品横向矩阵")
                .doesNotContain("## SWOT 分析")
                .doesNotContain("## 机会与风险（SWOT 摘要）")
                .doesNotContain("风险边界：")
                .doesNotContain("下一步行动：");
    }

    @Test
    void writerAddsMissingSwotSectionBeforeRiskGap() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # 竞品分析报告

                        ## 一句话结论
                        Tool A 在工作流能力上有官方资料支撑 [S1]。

                        ## 竞品能力矩阵
                        | 竞品 | 判断 | 证据 |
                        | --- | --- | --- |
                        | Tool A | 工作流能力有资料支撑 | [S1] |

                        ## 风险与证据缺口
                        仍需补充更多用户实测证据。

                        ## 结论与建议
                        - 最终决策口径：以矩阵为主。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI tools",
                "developer tools",
                List.of("Tool A"),
                List.of("workflow"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Tool A docs",
                "https://example.test/tool-a",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Tool A documents workflow capabilities.",
                "Tool A documents workflow capabilities.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Tool A 在工作流能力上有官方资料支撑。");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report).contains("## 机会与风险摘要（SWOT）");
        assertThat(report.indexOf("## 机会与风险摘要（SWOT）"))
                .isLessThan(report.indexOf("## 风险与证据缺口"));
        assertThat(report).doesNotContain("## SWOT 分析");
    }

    @Test
    void writerNormalizesTabSeparatedPriorityTable() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # 竞品分析报告

                        ## 建议优先级
                        建议\t理由\t证据\t置信度\t下一步
                        1. 优先借鉴团队协作功能\t团队上下文和权限控制是企业刚需。\t[S1]\tHIGH\t梳理权限与审计策略。
                        待验证：\t3. 借鉴 Agent Skills 工作流思想\t该信息主要来自第三方指南。\t[S1]\tMEDIUM

                        ## 风险与证据缺口
                        仍需补充更多用户实测证据。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI tools",
                "developer tools",
                List.of("Tool A"),
                List.of("workflow"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Tool A docs",
                "https://example.test/tool-a",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Tool A documents workflow capabilities.",
                "Tool A documents workflow capabilities.",
                "test evidence"
        ));

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .contains("| 建议 | 理由 | 证据 | 置信度 | 下一步 |")
                .contains("| --- | --- | --- | --- | --- |")
                .contains("| 1. 优先借鉴团队协作功能 | 团队上下文和权限控制是企业刚需。 | [S1] | HIGH | 梳理权限与审计策略。 |")
                .contains("| 3. 待验证：借鉴 Agent Skills 工作流思想 | 该信息主要来自第三方指南。 | [S1] | MEDIUM |  |")
                .doesNotContain("建议\t理由\t证据")
                .doesNotContain("待验证：\t");
    }

    @Test
    void writerNormalizesSpaceSeparatedPriorityTable() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # 竞品分析报告

                        ## 建议优先级
                        建议 理由 证据 置信度 下一步
                        1. 优先借鉴团队协作功能  团队上下文和权限控制是企业刚需。  [S1]  HIGH  梳理权限与审计策略。
                        待验证：  3. 借鉴 Agent Skills 工作流思想  该信息主要来自第三方指南。  [S1]  MEDIUM

                        ## 风险与证据缺口
                        仍需补充更多用户实测证据。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI tools",
                "developer tools",
                List.of("Tool A"),
                List.of("workflow"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Tool A docs",
                "https://example.test/tool-a",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Tool A documents workflow capabilities.",
                "Tool A documents workflow capabilities.",
                "test evidence"
        ));

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .contains("| 建议 | 理由 | 证据 | 置信度 | 下一步 |")
                .contains("| 1. 优先借鉴团队协作功能 | 团队上下文和权限控制是企业刚需。 | [S1] | HIGH | 梳理权限与审计策略。 |")
                .contains("| 3. 待验证：借鉴 Agent Skills 工作流思想 | 该信息主要来自第三方指南。 | [S1] | MEDIUM |  |")
                .doesNotContain("建议 理由 证据 置信度 下一步");
    }

    @Test
    void writerRemovesLeakedSupportStatusCountsFromReport() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # Report

                        基于当前有限的公开资料和文档证据（SUPPORTED=4, PARTIAL=1, UNVERIFIED=3），建议继续补证。

                        证据状态：SUPPORTED=4，PARTIAL=1，UNVERIFIED=3
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor"),
                List.of("evidence status"),
                List.of("official_site"),
                List.of()
        ));

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .doesNotContain("SUPPORTED=")
                .doesNotContain("PARTIAL=")
                .doesNotContain("UNVERIFIED=")
                .contains("基于当前有限的公开资料和文档证据，建议继续补证。");
    }

    @Test
    void writerRemovesLeakedSupportStatusLabelsFromReport() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # Report

                        基于当前公开资料和文档证据（证据状态：SUPPORTED、PARTIAL、UNVERIFIED均有涉及），建议继续补证。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor"),
                List.of("evidence status"),
                List.of("official_site"),
                List.of()
        ));

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .doesNotContain("SUPPORTED")
                .doesNotContain("PARTIAL")
                .doesNotContain("UNVERIFIED")
                .contains("基于当前公开资料和文档证据，建议继续补证。");
    }

    @Test
    void writerAddsDecisionSummaryWhenReportOmitsIt() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # Report

                        ## 建议优先级
                        Cursor 的 Agent 工作流值得优先评估 [S1]。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor", "Claude Code"),
                List.of("agent workflow"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor product",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor agent workflow supports planning, building, fixing and reviewing changes.",
                "Cursor agent workflow supports planning, building, fixing and reviewing changes.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.RECOMMENDATION);
        claim.setContent("优先评估 Cursor 的 Agent 工作流，作为 AI Insight 后续版本的自动化研发流程参考。");
        claim.setConfidence(ConfidenceLevel.HIGH);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report)
                .contains("## 结论与建议")
                .contains("首选借鉴：优先评估 Cursor 的 Agent 工作流")
                .contains("[S1]")
                .doesNotContain("风险边界：")
                .doesNotContain("下一步行动：");
    }

    @Test
    void writerAddsCitationToUncitedCompetitiveJudgmentFromEligibleClaim() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return "# Report\n\nCursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。";
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor", "Claude Code"),
                List.of("core capabilities"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "AI coding tools overview",
                "https://example.test/ai-coding",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor and Claude Code have distinct core capability paths for AI coding assistants.",
                "Cursor and Claude Code have distinct core capability paths for AI coding assistants.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
        assertThat(report).contains("路径分明[S1]。");
    }

    @Test
    void writerKeepsOrderedListStructureWhenDowngradingUnverifiedLine() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        # Report

                        ## 关键洞察
                        待验证：2. Cursor 与 Claude Code 在效能提升上更适合大团队。
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor", "Claude Code"),
                List.of("developer productivity"),
                List.of("official_site"),
                List.of()
        ));

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report).contains("2. 待验证：Cursor 与 Claude Code 在效能提升上更适合大团队。");
        assertThat(report).doesNotContain("待验证：2.");
    }

    @Test
    void writerAddsCitationInsideMarkdownTableRows() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        | 维度 | 判断 |
                        | --- | --- |
                        | 核心能力 | Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。 |
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor", "Claude Code"),
                List.of("core capabilities"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "AI coding tools overview",
                "https://example.test/ai-coding",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor and Claude Code have distinct core capability paths for AI coding assistants.",
                "Cursor and Claude Code have distinct core capability paths for AI coding assistants.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明。");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        assertThat(latestReport(run))
                .contains("| 核心能力 | Cursor 与 Claude Code 在 AI 编程助手的核心能力上路径分明[S1]。 |");
    }

    @Test
    void writerDoesNotAttachCitationsToMarkdownTableHeaders() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        | 维度 | Cursor | Claude Code | 判断置信度 |
                        | --- | --- | --- | --- |
                        | 目标用户 | Cursor 更偏向企业工程团队，Claude Code 覆盖个人和团队。 | Claude Code 覆盖个人开发者和团队。 | HIGH |
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor", "Claude Code"),
                List.of("target users"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "AI coding tools target users",
                "https://example.test/ai-coding-target-users",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor and Claude Code target different developer teams.",
                "Cursor and Claude Code target different developer teams.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Cursor 与 Claude Code 在目标用户覆盖上存在差异。");
        claim.setConfidence(ConfidenceLevel.HIGH);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = latestReport(run);
        assertThat(report).contains("| 维度 | Cursor | Claude Code | 判断置信度 |");
        assertThat(report).doesNotContain("判断置信度[S1]");
    }

    @Test
    void writerDowngradesMarkdownTableCellWithoutBreakingColumns() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return """
                        | 建议 | 理由 | 证据 | 置信度 | 下一步 |
                        | --- | --- | --- | --- | --- |
                        | 考虑引入类似 Claude Code 的灵活定价与广泛用户覆盖模式 | 其提供免费计划及清晰的团队订阅价格，适合优先采用。 | [S8] 官方定价页明确列出免费计划及团队计划。 | HIGH | 复核定价页 |
                        """;
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Claude Code"),
                List.of("pricing"),
                List.of("pricing_page"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S8",
                "Claude pricing",
                "https://claude.com/pricing",
                "pricing_page",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Claude pricing lists plans.",
                "Claude pricing lists Pro, Max and Team plans.",
                "test evidence"
        ));

        writer.execute(run);

        String report = latestReport(run);
        String row = report.lines()
                .filter(line -> line.contains("Claude Code 的灵活定价"))
                .findFirst()
                .orElseThrow();
        assertThat(row).startsWith("| 待验证：考虑引入");
        assertThat(row).contains("| 复核定价页 |");
        assertThat(row.chars().filter(ch -> ch == '|').count()).isEqualTo(6);
        assertThat(report).doesNotContain("待验证：|");
    }

    @Test
    void writerDowngradesCitedImpactClaimWithoutClaimSupport() {
        LlmClient llmClient = new LlmClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String complete(ChatRequest request) {
                return "# Report\n\nCursor 的集成方式能直接提升开发者工作流效率。 [S1]";
            }
        };
        WriterNode writer = new WriterNode(llmClient, new FallbackReportDraftFactory());
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze AI coding tools",
                "developer tools",
                List.of("Cursor"),
                List.of("integration"),
                List.of("official_site"),
                List.of()
        ));
        run.getEvidenceSources().add(new EvidenceSource(
                "S1",
                "Cursor docs",
                "https://example.test/cursor",
                "official_site",
                "FETCHED",
                "LIVE_FETCHED",
                "HIGH",
                "NONE",
                "Cursor provides IDE integration and CLI support.",
                "Cursor provides IDE integration and CLI support.",
                "test evidence"
        ));
        AnalysisClaim claim = new AnalysisClaim();
        claim.setType(ClaimType.COMPARISON);
        claim.setContent("Cursor provides IDE integration and CLI support.");
        claim.setConfidence(ConfidenceLevel.MEDIUM);
        claim.setSupportStatus("SUPPORTED");
        claim.setRecommendedPlacement("MATRIX");
        claim.setEligibleForMainReport(true);
        claim.setEvidenceIds(List.of("S1"));
        run.getClaims().add(claim);

        writer.execute(run);

        String report = run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
        assertThat(report).contains("待验证：Cursor 的集成方式能直接提升开发者工作流效率[S1]。");
    }

    private String latestReport(AnalysisRun run) {
        return run.getArtifacts().stream()
                .filter(artifact -> artifact.getType() == ArtifactType.REPORT_DRAFT)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContent();
    }
}
