package com.aiinsight.workflow.node;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisArtifact;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.domain.ArtifactType;
import com.aiinsight.workflow.AgentNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
// Revision 表示“根据质检结果修订”的动作。
// 之后接 DAG 时，它会成为 Reviewer 打回 Writer 后的一个条件节点。
public class RevisionNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.REVISION;
    }

    @Override
    public String title() {
        return "根据复核结果修订报告";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        AnalysisArtifact draft = latestArtifact(run, ArtifactType.REPORT_DRAFT);
        if (draft == null) {
            return run;
        }
        // 当前先用规则结果决定修订说明；真实版本会让 Writer 基于 ReviewIssue 生成新报告版本。
        String revisionNote = run.getReviewFindings().isEmpty()
                ? "\n\n## 复核结论\n\nReviewer 未发现高风险引用缺失，报告可进入人工确认。"
                : "\n\n## 复核修订\n\n已将未绑定引用的机会点降级为待验证假设，并建议补采价格页、更新日志和客户评价来源。";
        AnalysisArtifact revised = new AnalysisArtifact(
                ArtifactType.FINAL_REPORT,
                "可溯源竞品分析报告",
                draft.getContent() + revisionNote,
                draft.getCitationKeys()
        );
        revised.setVersion(draft.getVersion() + 1);
        run.getArtifacts().add(revised);
        run.getRecommendedActions().add("查看最终报告的引用覆盖情况，并决定是否补采更多信息源。");
        return run;
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
