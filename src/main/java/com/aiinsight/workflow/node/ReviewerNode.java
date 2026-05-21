package com.aiinsight.workflow.node;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisArtifact;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.domain.ArtifactType;
import com.aiinsight.llm.ChatMessage;
import com.aiinsight.llm.ChatOptions;
import com.aiinsight.llm.ChatRequest;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.service.CitationCoverageEvaluator;
import com.aiinsight.workflow.AgentNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
// Reviewer 是可信度防线：先跑确定性规则，再让 LLM 做更语义化的质检。
// 未来的 DAG 条件边会根据 reviewFindings 决定是通过、修订报告还是打回采集。
public class ReviewerNode implements AgentNode {

    private final CitationCoverageEvaluator citationCoverageEvaluator;
    private final LlmClient llmClient;

    @Override
    public AgentName name() {
        return AgentName.REVIEWER;
    }

    @Override
    public String title() {
        return "复核事实一致性与引用覆盖";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        AnalysisArtifact draft = latestArtifact(run, ArtifactType.REPORT_DRAFT);
        run.getReviewFindings().clear();
        if (draft != null) {
            // 规则结果进入结构化 finding，不能只存在于 LLM 文本回复里。
            run.getReviewFindings().addAll(citationCoverageEvaluator.evaluate(draft.getContent()));
        }
        String content = llmClient.isAvailable() && draft != null
                ? reviewWithLlm(run, draft)
                : fallbackReviewContent(run);
        run.getArtifacts().add(new AnalysisArtifact(ArtifactType.REVIEW_FINDINGS, "Reviewer 复核结果", content, List.of()));
        return run;
    }

    private String reviewWithLlm(AnalysisRun run, AnalysisArtifact draft) {
        // 把规则引擎发现的问题一并喂给 LLM，让语义质检在确定性检查基础上补充判断。
        String prompt = """
                你是竞品分析小组中的 Reviewer Agent。请审查报告草稿的事实一致性、引用覆盖、结论过度推断和竞品覆盖不足问题。

                输出要求:
                1. 输出 Markdown。
                2. 按 HIGH / MEDIUM / LOW 分组列出问题。
                3. 每个问题给出“问题、证据、修订建议”。
                4. 如果某段没有引用或证据不足，请明确指出。
                5. 不要替 Writer 重写全文，只做质检。

                已知证据:
                %s

                规则引擎已检出:
                %s

                报告草稿:
                %s
                """.formatted(
                run.getEvidenceSources().stream()
                        .map(source -> "[%s] %s: %s".formatted(source.getCitationKey(), source.getTitle(), source.getSnippet()))
                        .collect(Collectors.joining("\n")),
                fallbackReviewContent(run),
                draft.getContent()
        );
        return llmClient.complete(new ChatRequest(
                List.of(
                        ChatMessage.system("你是严格的事实核查和引用覆盖 Reviewer Agent。"),
                        ChatMessage.user(prompt)
                ),
                ChatOptions.deterministic()
        ));
    }

    private String fallbackReviewContent(AnalysisRun run) {
        return run.getReviewFindings().isEmpty()
                ? "Reviewer 未发现高风险问题。"
                : run.getReviewFindings().stream()
                .map(finding -> "- [%s] %s: %s".formatted(finding.getSeverity(), finding.getCategory(), finding.getMessage()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private AnalysisArtifact latestArtifact(AnalysisRun run, ArtifactType type) {
        List<AnalysisArtifact> artifacts = run.getArtifacts();
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            if (artifacts.get(i).getType() == type) {
                return artifacts.get(i);
            }
        }
        return null;
    }
}
