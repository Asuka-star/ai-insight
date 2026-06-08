package com.aiinsight.service;

import com.aiinsight.exception.DocumentIngestionException;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import com.aiinsight.model.run.UserProvidedEvidence;
import com.aiinsight.repository.AnalysisRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class DocumentIngestionService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_EXTRACTED_TEXT_LENGTH = 200_000;
    private static final int MAX_DOCUMENTS_PER_RUN = 10;
    private static final int SNIPPET_LENGTH = 220;

    private final DocumentTextExtractor documentTextExtractor;
    private final EvidenceChunkService evidenceChunkService;
    private final EvidenceEmbeddingService evidenceEmbeddingService;
    private final AnalysisRunRepository repository;
    private final AnalysisEventBroker eventBroker;
    private final Executor documentIngestionExecutor;
    private final PiiDesensitizer piiDesensitizer;

    @Autowired
    public DocumentIngestionService(DocumentTextExtractor documentTextExtractor,
                                    EvidenceChunkService evidenceChunkService,
                                    EvidenceEmbeddingService evidenceEmbeddingService,
                                    AnalysisRunRepository repository,
                                    AnalysisEventBroker eventBroker,
                                    @Qualifier("documentIngestionTaskExecutor") Executor documentIngestionExecutor,
                                    PiiDesensitizer piiDesensitizer) {
        this.documentTextExtractor = documentTextExtractor;
        this.evidenceChunkService = evidenceChunkService;
        this.evidenceEmbeddingService = evidenceEmbeddingService;
        this.repository = repository;
        this.eventBroker = eventBroker;
        this.documentIngestionExecutor = documentIngestionExecutor == null ? Runnable::run : documentIngestionExecutor;
        this.piiDesensitizer = piiDesensitizer == null ? new PiiDesensitizer() : piiDesensitizer;
    }

    public DocumentIngestionService(DocumentTextExtractor documentTextExtractor,
                                    EvidenceChunkService evidenceChunkService,
                                    EvidenceEmbeddingService evidenceEmbeddingService) {
        this(documentTextExtractor, evidenceChunkService, evidenceEmbeddingService, null, null, Runnable::run, null);
    }

    public AnalysisRun ingest(AnalysisRun run,
                              MultipartFile file,
                              String citationKey,
                              String title,
                              String sourceType,
                              boolean sensitive,
                              String notes) {
        return ingest(run, file, citationKey, title, sourceType, sensitive, notes, false);
    }

    public AnalysisRun ingest(AnalysisRun run,
                              MultipartFile file,
                              String citationKey,
                              String title,
                              String sourceType,
                              boolean sensitive,
                              String notes,
                              boolean globalResource) {
        validateRun(run);
        validateFile(file);
        UploadedDocument upload = uploadedDocument(file);
        EvidenceSource source = buildProcessingSource(
                citationKey,
                upload.filename(),
                title,
                sourceType,
                sensitive,
                notes,
                globalResource
        );
        run.getEvidenceSources().add(source);
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户文件 " + citationKey + " 正在解析、切片和向量化，完成后可重跑 RESEARCHER 或 EXTRACTOR。");

        // 测试和少数同步调用没有仓储注入时直接处理；生产路径先保存 PROCESSING 占位，
        // 再由后台线程解析文件，前端可据此展示处理进度并阻止过早重跑。
        if (repository == null) {
            process(run, citationKey, upload, title, sourceType, sensitive, notes, globalResource);
            return run;
        }
        repository.save(run);
        publish(run, "document_ingestion_started", "用户文件 " + citationKey + " 已进入处理队列。");
        documentIngestionExecutor.execute(() -> processFromRepository(
                run.getId(),
                citationKey,
                upload,
                title,
                sourceType,
                sensitive,
                notes,
                globalResource
        ));
        return run;
    }

    public boolean managesPersistence() {
        return repository != null;
    }

    private void processFromRepository(UUID runId,
                                       String citationKey,
                                       UploadedDocument upload,
                                       String title,
                                       String sourceType,
                                       boolean sensitive,
                                       String notes,
        boolean globalResource) {
        try {
            // 后台线程重新读取最新 run，避免沿用上传请求里的旧对象快照。
            AnalysisRun run = repository.findById(runId)
                    .orElseThrow(() -> new DocumentIngestionException("分析任务不存在，无法处理上传文档。"));
            process(run, citationKey, upload, title, sourceType, sensitive, notes, globalResource);
            repository.save(run);
            publish(run, "document_ingestion_completed", "用户文件 " + citationKey + " 已完成解析与向量化。");
        } catch (RuntimeException ex) {
            repository.findById(runId).ifPresent(run -> {
                markFailed(run, citationKey, ex);
                repository.save(run);
                publish(run, "document_ingestion_failed", "用户文件 " + citationKey + " 处理失败：" + ex.getMessage());
            });
        }
    }

    private void process(AnalysisRun run,
                         String citationKey,
                         UploadedDocument upload,
                         String title,
                         String sourceType,
                         boolean sensitive,
                         String notes,
                         boolean globalResource) {
        // 处理过程分阶段写回 run：前端资源包会读取 ingestionStage/ingestionMessage，
        // 用户也能看到文件究竟卡在解析、切片还是向量化。
        markStage(run, citationKey, "PARSING", "正在解析文档文本");
        ExtractedDocumentText extracted = documentTextExtractor.extract(upload.filename(), upload.contentType(), upload.bytes());
        String rawText = truncateIfNeeded(run, extracted.text());

        // PII 脱敏：对上传文档的原始文本进行敏感信息检测与替换
        PiiDesensitizer.DesensitizeResult piiResult = piiDesensitizer.desensitizeWithFindings(rawText);
        if (piiResult.hasFindings()) {
            rawText = piiResult.getText();
            markStage(run, citationKey, "PII_DESENSITIZED",
                    "检测到 PII（" + String.join("、", piiResult.getFindings()) + "），已自动脱敏。");
        }

        EvidenceSource finalSource = buildSource(citationKey, extracted, title, sourceType, sensitive, notes, rawText);
        finalSource.setGlobalResource(globalResource);
        EvidenceSource source = sourceByCitationKey(run, citationKey);
        if (source == null) {
            run.getEvidenceSources().add(finalSource);
            source = finalSource;
        } else {
            applySourceData(source, finalSource);
        }

        markStage(run, citationKey, "CHUNKING", "正在切分 RAG 片段");
        List<EvidenceChunk> chunks = evidenceChunkService.chunk(List.of(source));
        run.getEvidenceChunks().removeIf(chunk -> citationKey.equals(chunk.getSourceCitationKey()));
        List<EvidenceChunk> readyChunks;
        if (sensitive) {
            readyChunks = chunks;
        } else {
            markStage(run, citationKey, "EMBEDDING", "正在生成向量索引");
            readyChunks = evidenceEmbeddingService.embedChunks(chunks);
        }
        run.getEvidenceChunks().addAll(readyChunks);
        applySourceData(source, finalSource);
        markReady(source);
        if (globalResource) {
            saveGlobalEvidence(source, readyChunks);
        }
        attachDocumentAsUserEvidence(run, source, sensitive);
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户文件 " + citationKey + " 已加入证据链。可重跑 RESEARCHER 或 EXTRACTOR 刷新后续输出。");
    }

    private void validateRun(AnalysisRun run) {
        if (run == null) {
            throw new DocumentIngestionException("缺少分析会话，无法加入文件。");
        }
        long documentCount = run.getEvidenceSources().stream()
                .filter(source -> source.getUrl() != null && source.getUrl().startsWith("user-document://"))
                .count();
        if (documentCount >= MAX_DOCUMENTS_PER_RUN) {
            throw new DocumentIngestionException("当前会话的文件数量已达上限。");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentIngestionException("上传文件为空。");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new DocumentIngestionException("上传文件过大，最大支持 10 MB。");
        }
    }

    private UploadedDocument uploadedDocument(MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            if (!StringUtils.hasText(filename)) {
                filename = "uploaded-document.txt";
            }
            filename = filename.replace('\\', '/').replaceAll("^.*/", "").trim();
            return new UploadedDocument(filename, file.getContentType(), file.getBytes());
        } catch (IOException ex) {
            throw new DocumentIngestionException("读取上传文件失败。", ex);
        }
    }

    private EvidenceSource buildProcessingSource(String citationKey,
                                                 String filename,
                                                 String title,
                                                 String sourceType,
                                                 boolean sensitive,
                                                 String notes,
                                                 boolean globalResource) {
        String documentUrl = documentUrl(citationKey);
        String effectiveTitle = StringUtils.hasText(title) ? title.trim() : titleFromFilename(filename);
        EvidenceSource source = new EvidenceSource(
                citationKey,
                effectiveTitle,
                documentUrl,
                normalizeSourceType(sourceType, filename),
                STATUS_PROCESSING,
                "USER_PROVIDED",
                "PENDING",
                "NONE",
                "文档正在解析、切片和向量化。",
                "",
                processingComplianceNote(sensitive, filename, notes)
        );
        source.setSourceAuthority(sensitive ? "INTERNAL_ONLY" : "USER_PROVIDED");
        source.setCanonicalHost("user-document");
        source.setPublisherName("用户上传文件");
        source.setContentLanguage("");
        source.setCacheHit(false);
        source.setRetrievedAt(Instant.now());
        source.setIngestionStatus(STATUS_PROCESSING);
        source.setIngestionStage("QUEUED");
        source.setIngestionMessage("等待解析文档");
        source.setIngestionStartedAt(Instant.now());
        source.setGlobalResource(globalResource);
        return source;
    }

    private EvidenceSource buildSource(String citationKey,
                                       ExtractedDocumentText extracted,
                                       String title,
                                       String sourceType,
                                       boolean sensitive,
                                       String notes,
                                       String rawText) {
        String normalizedSourceType = normalizeSourceType(sourceType, extracted.originalFilename());
        String effectiveTitle = StringUtils.hasText(title) ? title.trim() : extracted.title();
        EvidenceSource source = new EvidenceSource(
                citationKey,
                effectiveTitle,
                documentUrl(citationKey),
                normalizedSourceType,
                "USER_PROVIDED",
                sensitive ? "INTERNAL_ONLY" : "USER_PROVIDED",
                sensitive ? "INTERNAL_ONLY" : "MEDIUM",
                "NONE",
                snippet(rawText),
                rawText,
                complianceNote(sensitive, extracted, notes)
        );
        source.setSourceAuthority(sensitive ? "INTERNAL_ONLY" : "USER_PROVIDED");
        source.setCanonicalHost("user-document");
        source.setPublisherName("用户上传文件");
        source.setContentLanguage("");
        source.setCacheHit(false);
        source.setRetrievedAt(Instant.now());
        return source;
    }

    private void applySourceData(EvidenceSource target, EvidenceSource source) {
        // 保留原 EvidenceSource 对象引用，只覆盖可变字段；这样 PROCESSING 占位能平滑变成 READY。
        target.setTitle(source.getTitle());
        target.setUrl(source.getUrl());
        target.setSourceType(source.getSourceType());
        target.setSourceAuthority(source.getSourceAuthority());
        target.setCanonicalHost(source.getCanonicalHost());
        target.setPublisherName(source.getPublisherName());
        target.setContentLanguage(source.getContentLanguage());
        target.setCollectionStatus(source.getCollectionStatus());
        target.setFreshness(source.getFreshness());
        target.setSourceQuality(source.getSourceQuality());
        target.setFailureReason(source.getFailureReason());
        target.setContentHash(source.getContentHash());
        target.setCacheHit(source.isCacheHit());
        target.setSnippet(source.getSnippet());
        target.setRawText(source.getRawText());
        target.setComplianceNote(source.getComplianceNote());
        target.setRetrievedAt(source.getRetrievedAt());
        target.setGlobalResource(source.isGlobalResource());
    }

    private void markStage(AnalysisRun run, String citationKey, String stage, String message) {
        EvidenceSource source = sourceByCitationKey(run, citationKey);
        if (source != null) {
            markStage(source, stage, message);
            persistProgress(run, "document_ingestion_progress", "用户文件 " + citationKey + "：" + message);
        }
    }

    private void markStage(EvidenceSource source, String stage, String message) {
        source.setIngestionStatus(STATUS_PROCESSING);
        source.setIngestionStage(stage);
        source.setIngestionMessage(message);
        source.setCollectionStatus(STATUS_PROCESSING);
        source.setSourceQuality("PENDING");
    }

    private void markReady(EvidenceSource source) {
        source.setIngestionStatus(STATUS_READY);
        source.setIngestionStage("READY");
        source.setIngestionMessage("文档已完成解析、切片和向量化");
        source.setIngestionCompletedAt(Instant.now());
    }

    private void markFailed(AnalysisRun run, String citationKey, RuntimeException ex) {
        EvidenceSource source = sourceByCitationKey(run, citationKey);
        if (source == null) {
            return;
        }
        source.setIngestionStatus(STATUS_FAILED);
        source.setIngestionStage(STATUS_FAILED);
        source.setIngestionMessage(ex.getMessage());
        source.setIngestionCompletedAt(Instant.now());
        source.setCollectionStatus(STATUS_FAILED);
        source.setSourceQuality("UNUSABLE");
        source.setFailureReason(ex.getMessage());
        source.setSnippet("文档处理失败：" + ex.getMessage());
        run.getRecommendedActions().add("用户文件 " + citationKey + " 处理失败：" + ex.getMessage());
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
    }

    private EvidenceSource sourceByCitationKey(AnalysisRun run, String citationKey) {
        return run.getEvidenceSources().stream()
                .filter(source -> citationKey.equals(source.getCitationKey()))
                .findFirst()
                .orElse(null);
    }

    private void attachDocumentAsUserEvidence(AnalysisRun run, EvidenceSource source, boolean sensitive) {
        String url = source.getUrl();
        boolean exists = run.getUserProvidedEvidence().stream()
                .anyMatch(evidence -> Objects.equals(url, evidence.getUrl()));
        if (exists) {
            return;
        }
        // 即使文档是全局资源，也要作为当前 run 的 userProvidedEvidence 进入 Researcher 输入；
        // 这样信息采集 agent 和结构化抽取 agent 都能在本任务内感知用户刚上传的内容。
        run.getUserProvidedEvidence().add(new UserProvidedEvidence(
                source.getTitle(),
                source.getSourceType(),
                source.getRawText(),
                url,
                sensitive
        ));
    }

    private void saveGlobalEvidence(EvidenceSource source, List<EvidenceChunk> chunks) {
        if (repository == null || source == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        // 全局资源使用“标题 + 文本”的稳定 hash 作为 URL，当前 run 仍保留 user-document://Sx
        // 便于用户在本任务里删除；全局库则用 global-document://... 供后续任务检索。
        String globalUrl = globalDocumentUrl(source);
        EvidenceSource globalSource = copyGlobalSource(source, globalUrl);
        List<EvidenceChunk> globalChunks = chunks.stream()
                .map(chunk -> copyGlobalChunk(chunk, globalUrl))
                .toList();
        repository.saveGlobalEvidence(globalSource, globalChunks);
    }

    private EvidenceSource copyGlobalSource(EvidenceSource source, String globalUrl) {
        EvidenceSource globalSource = new EvidenceSource(
                source.getCitationKey(),
                source.getTitle(),
                globalUrl,
                source.getSourceType(),
                source.getCollectionStatus(),
                source.getFreshness(),
                source.getSourceQuality(),
                source.getFailureReason(),
                source.getSnippet(),
                source.getRawText(),
                source.getComplianceNote()
        );
        globalSource.setSourceAuthority(source.getSourceAuthority());
        globalSource.setCanonicalHost("global-document");
        globalSource.setPublisherName(source.getPublisherName());
        globalSource.setContentLanguage(source.getContentLanguage());
        globalSource.setContentHash(source.getContentHash());
        globalSource.setCacheHit(source.isCacheHit());
        globalSource.setRetrievedAt(source.getRetrievedAt());
        globalSource.setIngestionStatus(source.getIngestionStatus());
        globalSource.setIngestionStage(source.getIngestionStage());
        globalSource.setIngestionMessage(source.getIngestionMessage());
        globalSource.setIngestionStartedAt(source.getIngestionStartedAt());
        globalSource.setIngestionCompletedAt(source.getIngestionCompletedAt());
        globalSource.setGlobalResource(true);
        return globalSource;
    }

    private EvidenceChunk copyGlobalChunk(EvidenceChunk chunk, String globalUrl) {
        // 全局 chunk 保留原文本、hash 和向量，但 URL 改成 global-document://，
        // 后续检索服务再把它“本地化”为当前 run 的 citationKey。
        EvidenceChunk copy = new EvidenceChunk(
                chunk.getChunkKey(),
                chunk.getSourceCitationKey(),
                chunk.getChunkIndex(),
                chunk.getTitle(),
                globalUrl,
                chunk.getText()
        );
        copy.setHeadingPath(chunk.getHeadingPath());
        copy.setContentKind(chunk.getContentKind());
        copy.setSourceType(chunk.getSourceType());
        copy.setSourceAuthority(chunk.getSourceAuthority());
        copy.setSourceQuality(chunk.getSourceQuality());
        copy.setTextHash(chunk.getTextHash());
        copy.setEmbeddingModel(chunk.getEmbeddingModel());
        copy.setEmbeddedAt(chunk.getEmbeddedAt());
        copy.setEmbedding(chunk.getEmbedding() == null ? List.of() : new ArrayList<>(chunk.getEmbedding()));
        copy.setScore(chunk.getScore());
        copy.setCreatedAt(chunk.getCreatedAt());
        return copy;
    }

    private String normalizeSourceType(String sourceType, String filename) {
        String normalized = sourceType == null ? "" : sourceType.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) {
            normalized = extension(filename);
        }
        return switch (normalized) {
            case "interview", "interview_note", "user_interview" -> "user_interview";
            case "survey", "survey_summary", "user_survey" -> "user_survey";
            case "note", "user_note" -> "user_note";
            case "pricing", "pricing_document" -> "user_pricing_document";
            case "brief", "product_brief" -> "user_product_brief";
            case "pdf" -> "user_document_pdf";
            case "docx" -> "user_document_docx";
            case "md", "markdown" -> "user_document_markdown";
            case "txt", "" -> "user_document_text";
            default -> normalized.startsWith("user_") ? normalized : "user_" + normalized.replaceAll("[^a-z0-9_]+", "_");
        };
    }

    private String extension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String titleFromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        String title = dot > 0 ? filename.substring(0, dot) : filename;
        return StringUtils.hasText(title) ? title : "上传文件";
    }

    private String truncateIfNeeded(AnalysisRun run, String text) {
        if (text == null || text.length() <= MAX_EXTRACTED_TEXT_LENGTH) {
            return text == null ? "" : text;
        }
        run.getRecommendedActions().add("上传文件文本已截断到 "
                + MAX_EXTRACTED_TEXT_LENGTH + " 字符以内。");
        return text.substring(0, MAX_EXTRACTED_TEXT_LENGTH);
    }

    private String processingComplianceNote(boolean sensitive, String filename, String notes) {
        String base = sensitive
                ? "用户上传敏感文件（internal-only），处理完成前不会进入公开证据。"
                : "用户上传文件正在处理中。";
        String noteText = StringUtils.hasText(notes) ? " 用户备注：" + notes.trim() : "";
        return base + " 原文件名=" + filename + "。" + noteText;
    }

    private String complianceNote(boolean sensitive, ExtractedDocumentText extracted, String notes) {
        String base = sensitive
                ? "用户上传敏感文件（internal-only），仅作为内部证据使用，避免对外传播。"
                : "用户上传文件，仅用于当前分析会话。";
        String metadata = " 原文件名=" + extracted.originalFilename() + "；文件类型=" + extracted.mediaType() + "。";
        String noteText = StringUtils.hasText(notes) ? " 用户备注：" + notes.trim() : "";
        return base + metadata + noteText;
    }

    private String snippet(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, SNIPPET_LENGTH).trim() + "...";
    }

    private String documentUrl(String citationKey) {
        return "user-document://" + citationKey.toLowerCase(Locale.ROOT);
    }

    private String globalDocumentUrl(EvidenceSource source) {
        // 这里的 hash 规则必须和 AnalysisWorkflowService.globalDocumentUrl 保持一致；
        // 删除资源时会用同样的规则反推出全局库里的 global_url。
        String hashInput = String.join("\n",
                source.getTitle() == null ? "" : source.getTitle(),
                source.getRawText() == null ? "" : source.getRawText()
        );
        return "global-document://" + sha256(hashInput);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append("%02x".formatted(b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void publish(AnalysisRun run, String type, String message) {
        if (eventBroker != null) {
            eventBroker.publish(run, type, message);
        }
    }

    private void persistProgress(AnalysisRun run, String type, String message) {
        if (repository != null) {
            repository.save(run);
            publish(run, type, message);
        }
    }

    private record UploadedDocument(String filename, String contentType, byte[] bytes) {
    }
}
