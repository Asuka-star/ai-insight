package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.SurveyQuestion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SurveyResultImportServiceTest {

    @Test
    void buildsCsvTemplateFromQuestionnaire() {
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement(
                "Analyze Cursor.",
                "AI coding tools",
                List.of("Cursor"),
                List.of("AI search"),
                List.of("survey"),
                List.of()
        ));
        run.getResearchPackage().getResearchPlan().setQuestionnaire(questionnaire());

        String dsl = new String(new SurveyResultImportService().buildQuestionnaireDslText(run), StandardCharsets.UTF_8);

        assertThat(dsl).contains("AI 搜索是否重要？[单选题](维度：AI search)");
        assertThat(dsl).contains("非常重要\n一般");
        assertThat(dsl).contains("权限治理是否影响采购？[单选题](维度：governance)");
        assertThat(dsl).doesNotContain("受访者角色");
    }

    @Test
    void importsCsvRowsIntoSurveyResultBatch() {
        String csv = """
                提交时间,受访者角色,AI 搜索是否重要？,权限治理是否影响采购？,补充反馈
                2026-06-01,产品经理,非常重要,影响,希望保留引用证据
                2026-06-02,研发负责人,非常重要,影响,需要审计日志
                2026-06-03,IT 管理员,一般,不影响,预算也很关键
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "survey-results.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        SurveyResultBatch batch = new SurveyResultImportService().importResults(questionnaire(), file);

        assertThat(batch.responseCount()).isEqualTo(3);
        assertThat(batch.rawText()).contains("Sample size: 3");
        assertThat(batch.rawText()).contains("Respondent segments: 产品经理=1; 研发负责人=1; IT 管理员=1");
        assertThat(batch.rawText()).contains("Q: AI 搜索是否重要？");
        assertThat(batch.rawText()).contains("非常重要=2");
        assertThat(batch.rawText()).contains("希望保留引用证据");
    }

    private Questionnaire questionnaire() {
        Questionnaire questionnaire = new Questionnaire();
        questionnaire.setTitle("AI 编码工具问卷");
        questionnaire.getQuestions().add(new SurveyQuestion("AI search", "AI 搜索是否重要？", List.of("非常重要", "一般")));
        questionnaire.getQuestions().add(new SurveyQuestion("governance", "权限治理是否影响采购？", List.of("影响", "不影响")));
        questionnaire.getQuestions().add(new SurveyQuestion("pricing", "价格是否是主要顾虑？", List.of("是", "否")));
        return questionnaire;
    }
}
