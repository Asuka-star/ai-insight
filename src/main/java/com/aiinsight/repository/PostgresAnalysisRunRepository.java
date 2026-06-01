package com.aiinsight.repository;

import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.run.AnalysisRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
                create table if not exists deleted_analysis_run (
                    id uuid primary key,
                    deleted_at timestamptz not null
                )
                """);
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
        jdbcTemplate.execute("""
                create table if not exists analysis_artifact (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    type varchar(64),
                    title text,
                    version integer not null,
                    citation_count integer not null,
                    created_at timestamptz,
                    artifact_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_analysis_artifact_run_type
                on analysis_artifact (run_id, type, version)
                """);
        jdbcTemplate.execute("""
                create table if not exists agent_step (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    agent_name varchar(64),
                    status varchar(32),
                    started_at timestamptz,
                    completed_at timestamptz,
                    step_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_agent_step_run_agent
                on agent_step (run_id, agent_name, started_at)
                """);
        jdbcTemplate.execute("""
                create table if not exists agent_trace (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    step_id uuid,
                    agent_name varchar(64),
                    status varchar(32),
                    model_name text,
                    fallback_used boolean,
                    total_tokens integer,
                    latency_ms bigint,
                    created_at timestamptz,
                    trace_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_agent_trace_run_agent
                on agent_trace (run_id, agent_name, created_at)
                """);
        jdbcTemplate.execute("""
                create table if not exists evidence_source (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    citation_key varchar(32),
                    title text,
                    url text,
                    source_type varchar(64),
                    collection_status varchar(64),
                    freshness varchar(64),
                    retrieved_at timestamptz,
                    source_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create unique index if not exists idx_evidence_source_run_citation
                on evidence_source (run_id, citation_key)
                """);
        jdbcTemplate.execute("""
                create table if not exists evidence_chunk (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    chunk_key varchar(128),
                    source_citation_key varchar(32),
                    chunk_index integer not null,
                    title text,
                    url text,
                    created_at timestamptz,
                    chunk_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_evidence_chunk_run_source
                on evidence_chunk (run_id, source_citation_key, chunk_index)
                """);
        jdbcTemplate.execute("""
                create table if not exists review_finding (
                    id uuid primary key,
                    run_id uuid not null references analysis_run(id) on delete cascade,
                    severity varchar(32),
                    category varchar(128),
                    artifact_id uuid,
                    claim_id text,
                    citation_key varchar(32),
                    paragraph_index integer,
                    finding_payload jsonb not null
                )
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_review_finding_run_category
                on review_finding (run_id, category, severity)
                """);
    }

    @Override
    @Transactional
    public AnalysisRun save(AnalysisRun run) {
        run.touch();
        int affectedRows = jdbcTemplate.update("""
                        insert into analysis_run (id, status, original_prompt, run_payload, created_at, updated_at)
                        select ?, ?, ?, ?::jsonb, ?, ?
                        where not exists (select 1 from deleted_analysis_run where id = ?)
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
                Timestamp.from(run.getUpdatedAt()),
                run.getId()
        );
        if (affectedRows == 0) {
            return run;
        }
        refreshDetailTables(run);
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
    public boolean existsById(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from analysis_run where id = ?)",
                Boolean.class,
                id
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Collection<AnalysisRun> findAll() {
        return jdbcTemplate.query(
                "select run_payload from analysis_run order by updated_at desc",
                (rs, rowNum) -> toRun(rs)
        );
    }

    @Override
    public Collection<AnalysisRunSummary> findSummaries() {
        return jdbcTemplate.query("""
                        select
                            ar.id,
                            ar.status,
                            ar.original_prompt,
                            coalesce(nullif(ar.run_payload #>> '{clarificationDraft,industry}', ''),
                                     nullif(ar.run_payload #>> '{requirement,industry}', '')) as industry,
                            coalesce(ar.run_payload #> '{clarificationDraft,competitors}',
                                     ar.run_payload #> '{requirement,competitors}',
                                     '[]'::jsonb)::text as competitors_json,
                            coalesce(nullif(ar.run_payload #>> '{clarificationDraft,outputGoal}', ''),
                                     nullif(ar.run_payload #>> '{requirement,outputGoal}', '')) as output_goal,
                            (select count(*) from evidence_source es where es.run_id = ar.id) as evidence_count,
                            (select count(*) from analysis_artifact aa where aa.run_id = ar.id) as artifact_count,
                            (select count(*) from review_finding rf where rf.run_id = ar.id) as finding_count,
                            (select count(*) from agent_step ast where ast.run_id = ar.id) as step_count,
                            ar.created_at,
                            ar.updated_at
                        from analysis_run ar
                        order by ar.updated_at desc
                        """,
                (rs, rowNum) -> toSummary(rs)
        );
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        jdbcTemplate.update("""
                        insert into deleted_analysis_run (id, deleted_at)
                        values (?, ?)
                        on conflict (id) do nothing
                        """,
                id,
                Timestamp.from(Instant.now())
        );
        deleteDetails(id);
        jdbcTemplate.update("delete from analysis_run where id = ?", id);
    }

    private String originalPrompt(AnalysisRun run) {
        if (run.getRequirement() == null) {
            return "";
        }
        return run.getRequirement().getOriginalPrompt();
    }

    private void refreshDetailTables(AnalysisRun run) {
        UUID runId = run.getId();
        // analysis_run.run_payload 是恢复运行态的权威快照；明细表是只读投影，
        // 用于后续审计、筛选、看板和前端分页查询。保存时整批刷新，避免投影漂移。
        deleteDetails(runId);
        insertArtifacts(run);
        insertSteps(run);
        insertTraces(run);
        insertEvidenceSources(run);
        insertEvidenceChunks(run);
        insertReviewFindings(run);
    }

    private void deleteDetails(UUID runId) {
        jdbcTemplate.update("delete from review_finding where run_id = ?", runId);
        jdbcTemplate.update("delete from evidence_chunk where run_id = ?", runId);
        jdbcTemplate.update("delete from evidence_source where run_id = ?", runId);
        jdbcTemplate.update("delete from agent_trace where run_id = ?", runId);
        jdbcTemplate.update("delete from agent_step where run_id = ?", runId);
        jdbcTemplate.update("delete from analysis_artifact where run_id = ?", runId);
    }

    private void insertArtifacts(AnalysisRun run) {
        for (var artifact : run.getArtifacts()) {
            jdbcTemplate.update("""
                            insert into analysis_artifact
                                (id, run_id, type, title, version, citation_count, created_at, artifact_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    artifact.getId(),
                    run.getId(),
                    varchar(enumName(artifact.getType()), 64),
                    artifact.getTitle(),
                    artifact.getVersion(),
                    artifact.getCitationKeys() == null ? 0 : artifact.getCitationKeys().size(),
                    timestamp(artifact.getCreatedAt()),
                    toJson(artifact)
            );
        }
    }

    private void insertSteps(AnalysisRun run) {
        for (var step : run.getSteps()) {
            jdbcTemplate.update("""
                            insert into agent_step
                                (id, run_id, agent_name, status, started_at, completed_at, step_payload)
                            values (?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    step.getId(),
                    run.getId(),
                    varchar(enumName(step.getAgentName()), 64),
                    varchar(enumName(step.getStatus()), 32),
                    timestamp(step.getStartedAt()),
                    timestamp(step.getCompletedAt()),
                    toJson(step)
            );
        }
    }

    private void insertTraces(AnalysisRun run) {
        for (var trace : run.getTraces()) {
            jdbcTemplate.update("""
                            insert into agent_trace
                                (id, run_id, step_id, agent_name, status, model_name, fallback_used,
                                 total_tokens, latency_ms, created_at, trace_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    trace.getId(),
                    run.getId(),
                    trace.getStepId(),
                    varchar(enumName(trace.getAgentName()), 64),
                    varchar(enumName(trace.getStatus()), 32),
                    trace.getModelName(),
                    trace.getFallbackUsed(),
                    trace.getTotalTokens(),
                    trace.getLatencyMs(),
                    timestamp(trace.getCreatedAt()),
                    toJson(trace)
            );
        }
    }

    private void insertEvidenceSources(AnalysisRun run) {
        for (var source : run.getEvidenceSources()) {
            jdbcTemplate.update("""
                            insert into evidence_source
                                (id, run_id, citation_key, title, url, source_type, collection_status,
                                 freshness, retrieved_at, source_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    source.getId(),
                    run.getId(),
                    varchar(source.getCitationKey(), 32),
                    source.getTitle(),
                    source.getUrl(),
                    varchar(source.getSourceType(), 64),
                    varchar(source.getCollectionStatus(), 64),
                    varchar(source.getFreshness(), 64),
                    timestamp(source.getRetrievedAt()),
                    toJson(source)
            );
        }
    }

    private void insertEvidenceChunks(AnalysisRun run) {
        for (var chunk : run.getEvidenceChunks()) {
            jdbcTemplate.update("""
                            insert into evidence_chunk
                                (id, run_id, chunk_key, source_citation_key, chunk_index, title, url,
                                 created_at, chunk_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    chunk.getId(),
                    run.getId(),
                    varchar(chunk.getChunkKey(), 128),
                    varchar(chunk.getSourceCitationKey(), 32),
                    chunk.getChunkIndex(),
                    chunk.getTitle(),
                    chunk.getUrl(),
                    timestamp(chunk.getCreatedAt()),
                    toJson(chunk)
            );
        }
    }

    private void insertReviewFindings(AnalysisRun run) {
        for (var finding : run.getReviewFindings()) {
            jdbcTemplate.update("""
                            insert into review_finding
                                (id, run_id, severity, category, artifact_id, claim_id, citation_key,
                                 paragraph_index, finding_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    finding.getId(),
                    run.getId(),
                    varchar(enumName(finding.getSeverity()), 32),
                    varchar(finding.getCategory(), 128),
                    finding.getArtifactId(),
                    finding.getClaimId(),
                    varchar(finding.getCitationKey(), 32),
                    finding.getParagraphIndex(),
                    toJson(finding)
            );
        }
    }

    private String varchar(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize repository payload", ex);
        }
    }

    private AnalysisRun toRun(ResultSet rs) throws SQLException {
        try {
            return objectMapper.readValue(rs.getString("run_payload"), AnalysisRun.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize analysis run payload", ex);
        }
    }

    private AnalysisRunSummary toSummary(ResultSet rs) throws SQLException {
        return new AnalysisRunSummary(
                rs.getObject("id", UUID.class),
                AnalysisStatus.valueOf(rs.getString("status")),
                rs.getString("industry"),
                toStringList(rs.getString("competitors_json")),
                rs.getString("output_goal"),
                rs.getString("original_prompt"),
                rs.getInt("evidence_count"),
                rs.getInt("artifact_count"),
                rs.getInt("finding_count"),
                rs.getInt("step_count"),
                timestampValue(rs, "created_at"),
                timestampValue(rs, "updated_at")
        );
    }

    private List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize summary competitors", ex);
        }
    }

    private Instant timestampValue(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
