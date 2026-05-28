package com.aiinsight.repository;

import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresAnalysisRunRepositoryTest {

    @Test
    void saveTruncatesProjectionFieldsBeforeWritingShortVarcharColumns() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PostgresAnalysisRunRepository repository = new PostgresAnalysisRunRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        AnalysisRun run = new AnalysisRun();
        run.getEvidenceSources().add(new EvidenceSource(
                "S" + "1".repeat(64),
                "long source",
                "https://example.test/source",
                "source_type_" + "x".repeat(80),
                "collection_status_" + "x".repeat(80),
                "freshness_" + "x".repeat(80),
                "snippet",
                "raw",
                "note"
        ));
        run.getEvidenceChunks().add(new EvidenceChunk(
                "chunk-" + "x".repeat(180),
                "S" + "2".repeat(64),
                1,
                "long chunk",
                "https://example.test/chunk",
                "chunk text"
        ));
        ReviewFinding finding = new ReviewFinding(
                ReviewSeverity.MEDIUM,
                "review_category_" + "x".repeat(180),
                "message",
                "recommendation"
        );
        finding.setCitationKey("S" + "3".repeat(64));
        run.getReviewFindings().add(finding);

        repository.save(run);

        assertProjectionArgLengths(jdbcTemplate, "insert into evidence_source", 2, 32, 5, 64, 6, 64, 7, 64);
        assertProjectionArgLengths(jdbcTemplate, "insert into evidence_chunk", 2, 128, 3, 32);
        assertProjectionArgLengths(jdbcTemplate, "insert into review_finding", 3, 128, 6, 32);
    }

    @Test
    void findSummariesMapsJsonScopeAndProjectionCounts() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PostgresAnalysisRunRepository repository = new PostgresAnalysisRunRepository(
                jdbcTemplate,
                new ObjectMapper().findAndRegisterModules()
        );
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-05-27T10:15:30Z");
        Instant updatedAt = Instant.parse("2026-05-28T02:30:00Z");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<AnalysisRunSummary> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(runId);
            when(rs.getString("status")).thenReturn(AnalysisStatus.SUCCEEDED.name());
            when(rs.getString("industry")).thenReturn("AI 编程助手");
            when(rs.getString("competitors_json")).thenReturn("[\"Cursor\",\"GitHub Copilot\"]");
            when(rs.getString("output_goal")).thenReturn("选型参考");
            when(rs.getString("original_prompt")).thenReturn("分析 AI 编程助手");
            when(rs.getInt("evidence_count")).thenReturn(12);
            when(rs.getInt("artifact_count")).thenReturn(5);
            when(rs.getInt("finding_count")).thenReturn(3);
            when(rs.getInt("step_count")).thenReturn(7);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updatedAt));
            return List.of(mapper.mapRow(rs, 0));
        });

        Collection<AnalysisRunSummary> summaries = repository.findSummaries();

        assertThat(summaries)
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.getId()).isEqualTo(runId);
                    assertThat(summary.getStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
                    assertThat(summary.getIndustry()).isEqualTo("AI 编程助手");
                    assertThat(summary.getCompetitors()).containsExactly("Cursor", "GitHub Copilot");
                    assertThat(summary.getOutputGoal()).isEqualTo("选型参考");
                    assertThat(summary.getOriginalPrompt()).isEqualTo("分析 AI 编程助手");
                    assertThat(summary.getEvidenceCount()).isEqualTo(12);
                    assertThat(summary.getArtifactCount()).isEqualTo(5);
                    assertThat(summary.getFindingCount()).isEqualTo(3);
                    assertThat(summary.getStepCount()).isEqualTo(7);
                    assertThat(summary.getCreatedAt()).isEqualTo(createdAt);
                    assertThat(summary.getUpdatedAt()).isEqualTo(updatedAt);
                });
    }

    private void assertProjectionArgLengths(JdbcTemplate jdbcTemplate,
                                            String sqlFragment,
                                            int firstArgIndex,
                                            int firstMaxLength,
                                            int... remainingIndexAndLengthPairs) {
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, atLeastOnce()).update(contains(sqlFragment), argsCaptor.capture());
        List<Object[]> calls = argsCaptor.getAllValues();
        assertThat(calls).isNotEmpty();
        Object[] args = calls.get(calls.size() - 1);
        assertArgLength(args, firstArgIndex, firstMaxLength);
        for (int i = 0; i < remainingIndexAndLengthPairs.length; i += 2) {
            assertArgLength(args, remainingIndexAndLengthPairs[i], remainingIndexAndLengthPairs[i + 1]);
        }
    }

    private void assertArgLength(Object[] args, int index, int maxLength) {
        assertThat(args[index]).isInstanceOf(String.class);
        assertThat((String) args[index]).hasSizeLessThanOrEqualTo(maxLength);
    }
}
