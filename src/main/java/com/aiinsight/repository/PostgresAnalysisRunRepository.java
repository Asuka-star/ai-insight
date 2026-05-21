package com.aiinsight.repository;

import com.aiinsight.model.run.AnalysisRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresAnalysisRunRepository implements AnalysisRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresAnalysisRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureSchema() {
        jdbcTemplate.execute("""
                create table if not exists analysis_run (
                    id uuid primary key,
                    status varchar(32) not null,
                    original_prompt text,
                    run_payload jsonb not null,
                    created_at timestamptz not null,
                    updated_at timestamptz not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_analysis_run_updated_at
                on analysis_run (updated_at desc)
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_analysis_run_status
                on analysis_run (status)
                """);
    }

    @Override
    public AnalysisRun save(AnalysisRun run) {
        run.touch();
        jdbcTemplate.update("""
                        insert into analysis_run (id, status, original_prompt, run_payload, created_at, updated_at)
                        values (?, ?, ?, ?::jsonb, ?, ?)
                        on conflict (id) do update set
                            status = excluded.status,
                            original_prompt = excluded.original_prompt,
                            run_payload = excluded.run_payload,
                            updated_at = excluded.updated_at
                        """,
                run.getId(),
                run.getStatus().name(),
                originalPrompt(run),
                toJson(run),
                Timestamp.from(run.getCreatedAt()),
                Timestamp.from(run.getUpdatedAt())
        );
        return run;
    }

    @Override
    public Optional<AnalysisRun> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select run_payload from analysis_run where id = ?",
                    (rs, rowNum) -> toRun(rs),
                    id
            ));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Collection<AnalysisRun> findAll() {
        return jdbcTemplate.query(
                "select run_payload from analysis_run order by updated_at desc",
                (rs, rowNum) -> toRun(rs)
        );
    }

    private String originalPrompt(AnalysisRun run) {
        if (run.getRequirement() == null) {
            return "";
        }
        return run.getRequirement().getOriginalPrompt();
    }

    private String toJson(AnalysisRun run) {
        try {
            return objectMapper.writeValueAsString(run);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize analysis run " + run.getId(), ex);
        }
    }

    private AnalysisRun toRun(ResultSet rs) throws SQLException {
        try {
            return objectMapper.readValue(rs.getString("run_payload"), AnalysisRun.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize analysis run payload", ex);
        }
    }
}
