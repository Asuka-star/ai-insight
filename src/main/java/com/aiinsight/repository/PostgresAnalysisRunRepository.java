package com.aiinsight.repository;

import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.model.enums.AnalysisStatus;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class PostgresAnalysisRunRepository implements AnalysisRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private boolean vectorSchemaAvailable;

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
        ensureVectorSchema();
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
        jdbcTemplate.execute("alter table review_finding add column if not exists fact_id text");
        jdbcTemplate.execute("alter table review_finding add column if not exists chunk_key varchar(128)");
        jdbcTemplate.execute("""
                create index if not exists idx_review_finding_run_category
                on review_finding (run_id, category, severity)
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_review_finding_run_fact
                on review_finding (run_id, fact_id)
                """);
        jdbcTemplate.execute("""
                create index if not exists idx_review_finding_run_chunk
                on review_finding (run_id, chunk_key)
                """);
    }

    private void ensureVectorSchema() {
        try {
            jdbcTemplate.execute("create extension if not exists vector");
            jdbcTemplate.execute("""
                    create table if not exists evidence_chunk_embedding (
                        chunk_id uuid primary key references evidence_chunk(id) on delete cascade,
                        run_id uuid not null references analysis_run(id) on delete cascade,
                        source_citation_key varchar(32),
                        chunk_key varchar(128),
                        embedding vector,
                        embedding_model varchar(128),
                        embedding_text_hash varchar(128),
                        embedded_at timestamptz
                    )
                    """);
            jdbcTemplate.execute("""
                    create index if not exists idx_evidence_chunk_embedding_run
                    on evidence_chunk_embedding (run_id)
                    """);
            vectorSchemaAvailable = true;
        } catch (RuntimeException ex) {
            vectorSchemaAvailable = false;
            log.warn("pgvector schema is unavailable; evidence embeddings will stay in JSON payload only: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
        }
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
    public Optional<List<EvidenceChunk>> retrieveEvidenceByVector(UUID runId,
                                                                  List<Double> queryEmbedding,
                                                                  String embeddingModel,
                                                                  int topK) {
        if (!vectorSchemaAvailable || queryEmbedding == null || queryEmbedding.isEmpty()
                || embeddingModel == null || embeddingModel.isBlank()) {
            return Optional.empty();
        }
        int limit = Math.max(1, Math.min(topK, 100));
        String queryVector = embeddingLiteral(queryEmbedding);
        try {
            List<EvidenceChunk> chunks = jdbcTemplate.query("""
                            select
                                ec.chunk_payload,
                                ece.embedding::text as embedding_text,
                                1 - (ece.embedding <=> ?::vector) as semantic_score
                            from evidence_chunk_embedding ece
                            join evidence_chunk ec on ec.id = ece.chunk_id
                            where ece.run_id = ?
                              and ece.embedding_model = ?
                              and vector_dims(ece.embedding) = ?
                            order by ece.embedding <=> ?::vector
                            limit ?
                            """,
                    (rs, rowNum) -> toVectorChunk(rs, embeddingModel),
                    queryVector,
                    runId,
                    embeddingModel,
                    queryEmbedding.size(),
                    queryVector,
                    limit
            );
            return Optional.of(chunks);
        } catch (RuntimeException ex) {
            vectorSchemaAvailable = false;
            log.warn("pgvector evidence retrieval failed; in-memory retrieval fallback remains available: runId={}, exceptionType={}, message={}",
                    runId,
                    ex.getClass().getName(),
                    ex.getMessage());
            return Optional.empty();
        }
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
        insertEvidenceChunkEmbeddings(run);
        insertReviewFindings(run);
    }

    private void deleteDetails(UUID runId) {
        jdbcTemplate.update("delete from review_finding where run_id = ?", runId);
        if (vectorSchemaAvailable) {
            jdbcTemplate.update("delete from evidence_chunk_embedding where run_id = ?", runId);
        }
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

    private void insertEvidenceChunkEmbeddings(AnalysisRun run) {
        if (!vectorSchemaAvailable) {
            return;
        }
        TransactionStatus transactionStatus = currentTransactionStatus();
        Object savepoint = createSavepoint(transactionStatus);
        try {
            for (var chunk : run.getEvidenceChunks()) {
                if (chunk.getEmbedding() == null || chunk.getEmbedding().isEmpty()) {
                    continue;
                }
                jdbcTemplate.update("""
                                insert into evidence_chunk_embedding
                                    (chunk_id, run_id, source_citation_key, chunk_key, embedding,
                                     embedding_model, embedding_text_hash, embedded_at)
                                values (?, ?, ?, ?, ?::vector, ?, ?, ?)
                        """,
                        chunk.getId(),
                        run.getId(),
                        varchar(chunk.getSourceCitationKey(), 32),
                        varchar(chunk.getChunkKey(), 128),
                        embeddingLiteral(chunk.getEmbedding()),
                        varchar(chunk.getEmbeddingModel(), 128),
                        chunk.getTextHash(),
                        timestamp(chunk.getEmbeddedAt())
                );
            }
            releaseSavepoint(transactionStatus, savepoint);
        } catch (RuntimeException ex) {
            rollbackToSavepoint(transactionStatus, savepoint);
            vectorSchemaAvailable = false;
            log.warn("Failed to write pgvector evidence projection; JSON payload remains authoritative: runId={}, exceptionType={}, message={}",
                    run.getId(),
                    ex.getClass().getName(),
                    ex.getMessage());
        }
    }

    private TransactionStatus currentTransactionStatus() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        try {
            return TransactionAspectSupport.currentTransactionStatus();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Object createSavepoint(TransactionStatus transactionStatus) {
        if (transactionStatus == null) {
            return null;
        }
        try {
            return transactionStatus.createSavepoint();
        } catch (RuntimeException ex) {
            log.warn("Unable to create pgvector projection savepoint; projection failure may roll back the save transaction: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
            return null;
        }
    }

    private void rollbackToSavepoint(TransactionStatus transactionStatus, Object savepoint) {
        if (transactionStatus == null || savepoint == null) {
            return;
        }
        try {
            transactionStatus.rollbackToSavepoint(savepoint);
        } catch (RuntimeException rollbackEx) {
            log.warn("Unable to roll back pgvector projection savepoint: exceptionType={}, message={}",
                    rollbackEx.getClass().getName(),
                    rollbackEx.getMessage());
        }
    }

    private void releaseSavepoint(TransactionStatus transactionStatus, Object savepoint) {
        if (transactionStatus == null || savepoint == null) {
            return;
        }
        try {
            transactionStatus.releaseSavepoint(savepoint);
        } catch (RuntimeException ex) {
            log.debug("Unable to release pgvector projection savepoint: exceptionType={}, message={}",
                    ex.getClass().getName(),
                    ex.getMessage());
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
                                (id, run_id, severity, category, artifact_id, claim_id, fact_id, chunk_key,
                                 citation_key, paragraph_index, finding_payload)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    finding.getId(),
                    run.getId(),
                    varchar(enumName(finding.getSeverity()), 32),
                    varchar(finding.getCategory(), 128),
                    finding.getArtifactId(),
                    finding.getClaimId(),
                    finding.getFactId(),
                    varchar(finding.getChunkKey(), 128),
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

    private String embeddingLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(value -> value == null || !Double.isFinite(value)
                        ? "0.0"
                        : String.format(Locale.ROOT, "%.9f", value))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<Double> parseEmbeddingLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return List.of();
        }
        String normalized = literal.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split(",")).stream()
                .map(value -> Double.parseDouble(value.trim()))
                .toList();
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

    private EvidenceChunk toVectorChunk(ResultSet rs, String embeddingModel) throws SQLException {
        try {
            EvidenceChunk chunk = objectMapper.readValue(rs.getString("chunk_payload"), EvidenceChunk.class);
            chunk.setEmbedding(parseEmbeddingLiteral(rs.getString("embedding_text")));
            chunk.setEmbeddingModel(embeddingModel);
            chunk.setScore(rs.getDouble("semantic_score"));
            return chunk;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize vector evidence chunk payload", ex);
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
