package com.aiinsight.repository;

import com.aiinsight.model.enums.ReviewSeverity;
import com.aiinsight.model.review.ReviewFinding;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
