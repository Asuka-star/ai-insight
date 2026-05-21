package com.aiinsight.workflow.node;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisArtifact;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.domain.ArtifactType;
import com.aiinsight.workflow.AgentNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClarifierNode implements AgentNode {

    @Override
    public AgentName name() {
        return AgentName.CLARIFIER;
    }

    @Override
    public String title() {
        return "澄清分析范围";
    }

    @Override
    public AnalysisRun execute(AnalysisRun run) {
        var requirement = run.getRequirement();
        String content = """
                ## 分析范围

                行业: %s
                竞品: %s
                维度: %s
                信息源偏好: %s
                """.formatted(
                requirement.getIndustry(),
                String.join(", ", requirement.getCompetitors()),
                String.join(", ", requirement.getDimensions()),
                String.join(", ", requirement.getSourcePreferences())
        );
        run.getArtifacts().add(new AnalysisArtifact(ArtifactType.CLARIFICATION_BRIEF, "分析范围确认", content, List.of()));
        run.getRecommendedActions().add("确认竞品、分析维度和信息源范围，必要时补充排除项。");
        return run;
    }
}
