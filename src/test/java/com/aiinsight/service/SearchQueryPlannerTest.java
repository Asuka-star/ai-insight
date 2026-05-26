package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryPlannerTest {

    private final SearchQueryPlanner planner = new SearchQueryPlanner();

    @Test
    void includesAuthoritativeSourcesByDefault() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "分析 AI 编程助手",
                "AI 编程助手",
                List.of("Cursor"),
                List.of("代码生成", "团队协作"),
                List.of("public_reviews"),
                List.of()
        ));

        List<String> queries = planner.plan(run, false);

        assertThat(queries).anyMatch(query -> query.contains("official site product documentation"));
        assertThat(queries).anyMatch(query -> query.contains("official pricing plans"));
        assertThat(queries).anyMatch(query -> query.contains("official release notes changelog"));
        assertThat(queries).anyMatch(query -> query.contains("official technical blog"));
        assertThat(queries).anyMatch(query -> query.contains("independent user reviews customer feedback"));
    }
}
