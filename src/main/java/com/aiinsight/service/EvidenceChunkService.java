package com.aiinsight.service;

import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.model.run.EvidenceSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EvidenceChunkService {

    private static final int TARGET_CHUNK_SIZE = 950;
    private static final int MAX_CHUNK_SIZE = 1_200;
    private static final int CHUNK_OVERLAP = 140;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?;\\u3002\\uff01\\uff1f\\uff1b])\\s+");

    public List<EvidenceChunk> chunk(List<EvidenceSource> sources) {
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (EvidenceSource source : sources) {
            chunks.addAll(chunk(source));
        }
        return chunks;
    }

    private List<EvidenceChunk> chunk(EvidenceSource source) {
        List<EvidenceChunk> chunks = new ArrayList<>();
        String text = sourceText(source);
        if (!StringUtils.hasText(text)) {
            return chunks;
        }
        List<Section> sections = sections(source, text);
        int index = 1;
        for (Section section : sections) {
            for (String chunkText : splitSectionText(section.text())) {
                chunks.add(chunk(source, section.headingPath(), chunkText, index));
                index++;
            }
        }
        return chunks;
    }

    private EvidenceChunk chunk(EvidenceSource source, List<String> headingPath, String text, int index) {
        EvidenceChunk chunk = new EvidenceChunk(
                source.getCitationKey() + "-C" + index,
                source.getCitationKey(),
                index,
                source.getTitle(),
                source.getUrl(),
                normalizeInline(text)
        );
        chunk.setHeadingPath(headingPath);
        chunk.setContentKind(contentKind(source, headingPath, text));
        chunk.setSourceType(source.getSourceType());
        chunk.setSourceAuthority(source.getSourceAuthority());
        chunk.setSourceQuality(source.getSourceQuality());
        chunk.setTextHash(sha256(chunk.getText()));
        return chunk;
    }

    private String sourceText(EvidenceSource source) {
        String rawText = source.getRawText();
        if (StringUtils.hasText(rawText)) {
            return normalizeBlock(rawText);
        }
        return source.getSnippet() == null ? "" : normalizeBlock(source.getSnippet());
    }

    private List<Section> sections(EvidenceSource source, String text) {
        List<String> baseHeading = List.of(defaultHeading(source));
        String[] lines = text.split("\\R+");
        if (lines.length <= 1) {
            return List.of(new Section(baseHeading, text));
        }
        List<Section> sections = new ArrayList<>();
        List<String> headingPath = baseHeading;
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : lines) {
            String line = normalizeInline(rawLine);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (looksLikeHeading(line)) {
                flushSection(sections, headingPath, buffer);
                headingPath = mergeHeading(baseHeading, line);
                continue;
            }
            if (looksLikeFaqQuestion(line)) {
                flushSection(sections, headingPath, buffer);
                headingPath = mergeHeading(baseHeading, line);
                continue;
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n");
            }
            buffer.append(line);
        }
        flushSection(sections, headingPath, buffer);
        if (sections.isEmpty()) {
            return List.of(new Section(baseHeading, text));
        }
        return sections;
    }

    private void flushSection(List<Section> sections, List<String> headingPath, StringBuilder buffer) {
        String text = buffer.toString().trim();
        if (StringUtils.hasText(text)) {
            sections.add(new Section(headingPath, text));
            buffer.setLength(0);
        }
    }

    private List<String> splitSectionText(String text) {
        String normalized = normalizeInline(text);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        if (normalized.length() <= MAX_CHUNK_SIZE) {
            return List.of(normalized);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(normalized)) {
            String cleaned = sentence.trim();
            if (!StringUtils.hasText(cleaned)) {
                continue;
            }
            if (cleaned.length() > MAX_CHUNK_SIZE) {
                flushText(chunks, current);
                chunks.addAll(splitLongText(cleaned));
                continue;
            }
            if (!current.isEmpty() && current.length() + cleaned.length() + 1 > TARGET_CHUNK_SIZE) {
                flushText(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append(" ");
            }
            current.append(cleaned);
        }
        flushText(chunks, current);
        return chunks;
    }

    private void flushText(List<String> chunks, StringBuilder current) {
        String text = current.toString().trim();
        if (StringUtils.hasText(text)) {
            chunks.add(text);
            current.setLength(0);
        }
    }

    private List<String> splitLongText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + TARGET_CHUNK_SIZE);
            chunks.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            start = Math.max(0, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    private List<String> mergeHeading(List<String> baseHeading, String heading) {
        Set<String> path = new LinkedHashSet<>(baseHeading);
        path.add(heading);
        return new ArrayList<>(path).stream()
                .filter(StringUtils::hasText)
                .limit(4)
                .toList();
    }

    private boolean looksLikeHeading(String line) {
        String normalized = normalizeInline(line);
        if (normalized.startsWith("#")) {
            return true;
        }
        if (normalized.length() > 90 || normalized.split("\\s+").length > 12) {
            return false;
        }
        if (normalized.matches(".*[.!?;\\u3002\\uff01\\uff1f\\uff1b]$")) {
            return false;
        }
        if (normalized.matches("^[0-9]+[.)]\\s+.+")) {
            return true;
        }
        if (normalized.endsWith(":") || normalized.endsWith("\uff1a")) {
            return true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "pricing", "plans", "features", "security", "permissions", "integrations",
                "faq", "enterprise", "customers", "release notes", "changelog",
                "\u4ef7\u683c", "\u5b9a\u4ef7", "\u5957\u9910", "\u529f\u80fd",
                "\u5b89\u5168", "\u6743\u9650", "\u96c6\u6210", "\u5ba2\u6237", "\u5e38\u89c1\u95ee\u9898");
    }

    private boolean looksLikeFaqQuestion(String line) {
        String normalized = normalizeInline(line).toLowerCase(Locale.ROOT);
        return normalized.endsWith("?")
                || normalized.endsWith("\uff1f")
                || normalized.startsWith("q:")
                || normalized.startsWith("question:");
    }

    private String contentKind(EvidenceSource source, List<String> headingPath, String text) {
        String searchable = normalizeInline(String.join(" ",
                source.getSourceType(),
                source.getTitle(),
                source.getUrl(),
                String.join(" ", headingPath),
                text
        )).toLowerCase(Locale.ROOT);
        if (containsAny(searchable, "pricing", "price", "plans", "billing", "free plan", "\u4ef7\u683c", "\u5b9a\u4ef7", "\u5957\u9910", "\u4ed8\u8d39")) {
            return "pricing";
        }
        if (containsAny(searchable, "security", "compliance", "privacy", "saml", "sso", "scim", "\u5b89\u5168", "\u5408\u89c4", "\u9690\u79c1")) {
            return "security";
        }
        if (containsAny(searchable, "permission", "admin", "role", "rbac", "\u6743\u9650", "\u89d2\u8272", "\u7ba1\u7406\u5458")) {
            return "permission";
        }
        if (containsAny(searchable, " ai ", "artificial intelligence", "copilot", "assistant", "\u667a\u80fd", "\u751f\u6210\u5f0f", "\u5927\u6a21\u578b")) {
            return "ai_feature";
        }
        if (containsAny(searchable, "integration", "api", "webhook", "\u96c6\u6210", "\u63a5\u53e3")) {
            return "integration";
        }
        if (containsAny(searchable, "release notes", "changelog", "updates", "\u66f4\u65b0", "\u53d1\u5e03")) {
            return "release_note";
        }
        if (containsAny(searchable, "customer", "case study", "story", "\u5ba2\u6237", "\u6848\u4f8b")) {
            return "customer_story";
        }
        if (containsAny(searchable, "review", "reddit", "g2.", "capterra", "\u8bc4\u4ef7", "\u53e3\u7891")) {
            return "public_review";
        }
        if (containsAny(searchable, "faq", "question", "\u5e38\u89c1\u95ee\u9898")) {
            return "faq";
        }
        return "general_product";
    }

    private String defaultHeading(EvidenceSource source) {
        if (StringUtils.hasText(source.getTitle())) {
            return normalizeInline(source.getTitle());
        }
        if (StringUtils.hasText(source.getUrl())) {
            return source.getUrl();
        }
        return source.getCitationKey();
    }

    private String normalizeBlock(String value) {
        return value == null
                ? ""
                : value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String normalizeInline(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String text, String... patterns) {
        return Arrays.stream(patterns).anyMatch(text::contains);
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

    private record Section(List<String> headingPath, String text) {
    }
}
