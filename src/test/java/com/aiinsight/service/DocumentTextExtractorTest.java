package com.aiinsight.service;

import com.aiinsight.exception.DocumentIngestionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsMarkdownTextAndMetadata() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "research-note.md",
                "text/markdown",
                """
                        # Research note

                        This document summarizes enterprise buyer needs around permission governance,
                        AI search, pricing controls, implementation risk, support expectations, and auditability.
                        """.getBytes(StandardCharsets.UTF_8)
        );

        ExtractedDocumentText extracted = extractor.extract(file);

        assertThat(extracted.title()).isEqualTo("research-note");
        assertThat(extracted.originalFilename()).isEqualTo("research-note.md");
        assertThat(extracted.mediaType()).isEqualTo("text/markdown");
        assertThat(extracted.text()).contains("permission governance", "AI search");
        assertThat(extracted.metadata()).containsEntry("extension", "md");
    }

    @Test
    void extractsPdfTextAndMetadata() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "research-brief.pdf",
                "application/pdf",
                pdfBytes("""
                        Enterprise buyers need SAML SSO, SCIM provisioning, audit logs, permission governance,
                        predictable pricing controls, and AI search with citations to trusted workspace documents.
                        """)
        );

        ExtractedDocumentText extracted = extractor.extract(file);

        assertThat(extracted.title()).isEqualTo("research-brief");
        assertThat(extracted.mediaType()).isEqualTo("application/pdf");
        assertThat(extracted.text()).contains("SAML SSO", "AI search");
        assertThat(extracted.metadata()).containsEntry("extension", "pdf");
    }

    @Test
    void extractsDocxTextAndMetadata() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "survey-summary.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes("""
                        Survey summary says users value AI search, permission governance, auditability,
                        pricing transparency, integration quality, and clear implementation support.
                        """)
        );

        ExtractedDocumentText extracted = extractor.extract(file);

        assertThat(extracted.title()).isEqualTo("survey-summary");
        assertThat(extracted.mediaType()).contains("wordprocessingml.document");
        assertThat(extracted.text()).contains("permission governance", "pricing transparency");
        assertThat(extracted.metadata()).containsEntry("extension", "docx");
    }

    @Test
    void rejectsUnsupportedDocumentType() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "research.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not parsed yet".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentIngestionException.class)
                .hasMessageContaining("不支持的文档类型");
    }

    @Test
    void rejectsDocumentsWithoutEnoughText() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "short.txt",
                "text/plain",
                "tiny".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> extractor.extract(file))
                .isInstanceOf(DocumentIngestionException.class)
                .hasMessageContaining("可抽取文本");
    }

    private byte[] pdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 11);
                content.newLineAtOffset(40, 740);
                for (String line : text.strip().split("\\R")) {
                    content.showText(line.trim());
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docxBytes(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }
}
