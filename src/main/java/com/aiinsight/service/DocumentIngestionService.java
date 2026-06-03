package com.aiinsight.service;

import com.aiinsight.exception.DocumentIngestionException;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentIngestionService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_EXTRACTED_TEXT_LENGTH = 200_000;
    private static final int MAX_DOCUMENTS_PER_RUN = 10;
    private static final int SNIPPET_LENGTH = 220;

    private final DocumentTextExtractor documentTextExtractor;
    private final EvidenceChunkService evidenceChunkService;
    private final EvidenceEmbeddingService evidenceEmbeddingService;

    public DocumentIngestionService(DocumentTextExtractor documentTextExtractor,
                                    EvidenceChunkService evidenceChunkService,
                                    EvidenceEmbeddingService evidenceEmbeddingService) {
        this.documentTextExtractor = documentTextExtractor;
        this.evidenceChunkService = evidenceChunkService;
        this.evidenceEmbeddingService = evidenceEmbeddingService;
    }

    public AnalysisRun ingest(AnalysisRun run,
                              MultipartFile file,
                              String citationKey,
                              String title,
                              String sourceType,
                              boolean sensitive,
                              String notes) {
        validateRun(run);
        validateFile(file);
        ExtractedDocumentText extracted = documentTextExtractor.extract(file);
        String rawText = truncateIfNeeded(run, extracted.text());
        EvidenceSource source = buildSource(citationKey, extracted, title, sourceType, sensitive, notes, rawText);
        run.getEvidenceSources().add(source);

        List<EvidenceChunk> chunks = evidenceChunkService.chunk(List.of(source));
        run.getEvidenceChunks().addAll(sensitive
                ? chunks
                : evidenceEmbeddingService.embedChunks(chunks));
        run.getResearchPackage().setSources(new ArrayList<>(run.getEvidenceSources()));
        run.getResearchPackage().setCollectedAt(Instant.now());
        run.getRecommendedActions().add("用户文件 " + citationKey
                + " 已加入证据链。可重跑 RESEARCHER 或 EXTRACTOR 刷新后续输出。");
        return run;
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

    private EvidenceSource buildSource(String citationKey,
                                       ExtractedDocumentText extracted,
                                       String title,
                                       String sourceType,
                                       boolean sensitive,
                                       String notes,
                                       String rawText) {
        String normalizedSourceType = normalizeSourceType(sourceType, extracted.originalFilename());
        String effectiveTitle = StringUtils.hasText(title) ? title.trim() : extracted.title();
        String documentUrl = "user-document://" + citationKey.toLowerCase(Locale.ROOT);
        String complianceNote = complianceNote(sensitive, extracted, notes);
        EvidenceSource source = new EvidenceSource(
                citationKey,
                effectiveTitle,
                documentUrl,
                normalizedSourceType,
                "USER_PROVIDED",
                sensitive ? "INTERNAL_ONLY" : "USER_PROVIDED",
                sensitive ? "INTERNAL_ONLY" : "MEDIUM",
                "NONE",
                snippet(rawText),
                rawText,
                complianceNote
        );
        source.setSourceAuthority(sensitive ? "INTERNAL_ONLY" : "USER_PROVIDED");
        source.setCanonicalHost("user-document");
        source.setPublisherName("用户上传文件");
        source.setContentLanguage("");
        source.setCacheHit(false);
        source.setRetrievedAt(Instant.now());
        return source;
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

    private String truncateIfNeeded(AnalysisRun run, String text) {
        if (text == null || text.length() <= MAX_EXTRACTED_TEXT_LENGTH) {
            return text == null ? "" : text;
        }
        run.getRecommendedActions().add("上传文件文本已截断到 "
                + MAX_EXTRACTED_TEXT_LENGTH + " 字符以内。");
        return text.substring(0, MAX_EXTRACTED_TEXT_LENGTH);
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
}
