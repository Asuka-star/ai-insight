package com.aiinsight.service;

import com.aiinsight.exception.DocumentIngestionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@Service
public class DocumentTextExtractor {

    private static final int MIN_TEXT_LENGTH = 80;

    public ExtractedDocumentText extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentIngestionException("上传文件为空。");
        }
        String filename = filename(file);
        String extension = extension(filename);
        String text = switch (extension) {
            case "txt", "md", "markdown" -> readUtf8(file);
            case "pdf" -> readPdf(file);
            case "docx" -> readDocx(file);
            default -> throw new DocumentIngestionException(
                    "不支持的文档类型：" + extension + "。当前支持 txt、md、pdf、docx。");
        };
        text = normalizeText(text);
        if (text.length() < MIN_TEXT_LENGTH) {
            throw new DocumentIngestionException("上传文件没有足够的可抽取文本。");
        }
        return new ExtractedDocumentText(
                titleFromFilename(filename),
                mediaType(file.getContentType()),
                filename,
                text,
                Map.of("extension", extension)
        );
    }

    private String readUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new DocumentIngestionException("读取上传文件失败。", ex);
        }
    }

    private String readPdf(MultipartFile file) {
        try (InputStream input = file.getInputStream();
            PDDocument document = PDDocument.load(input)) {
            if (document.isEncrypted()) {
                throw new DocumentIngestionException("暂不支持加密 PDF 文件。");
            }
            return new PDFTextStripper().getText(document);
        } catch (DocumentIngestionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentIngestionException("抽取 PDF 文本失败。", ex);
        }
    }

    private String readDocx(MultipartFile file) {
        try (InputStream input = file.getInputStream();
             XWPFDocument document = new XWPFDocument(input);
            XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException ex) {
            throw new DocumentIngestionException("抽取 DOCX 文本失败。", ex);
        }
    }

    private String normalizeText(String value) {
        return value == null
                ? ""
                : value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String filename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            return "uploaded-document.txt";
        }
        return filename.replace('\\', '/').replaceAll("^.*/", "").trim();
    }

    private String extension(String filename) {
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

    private String mediaType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
    }
}
