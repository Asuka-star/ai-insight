package com.aiinsight.service;

import com.aiinsight.exception.DocumentIngestionException;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.schema.Questionnaire;
import com.aiinsight.model.schema.SurveyQuestion;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SurveyResultImportService {

    private static final Pattern METADATA_HEADER_PATTERN = Pattern.compile(
            "(?i)^(id|序号|编号|提交时间|提交日期|提交者|答题时间|开始时间|结束时间|耗时|ip|来源|openid|unionid|昵称|姓名|手机|邮箱|email|phone)$");
    private static final Pattern SEGMENT_HEADER_PATTERN = Pattern.compile("(?i).*(角色|岗位|部门|人群|群体|segment|role|persona|respondent).*");
    private static final Pattern OPEN_TEXT_HEADER_PATTERN = Pattern.compile("(?i).*(备注|建议|原因|补充|其他|反馈|说明|comment|feedback|note).*");
    private static final int MAX_FINDINGS = 12;
    private static final int MAX_VALUES_PER_QUESTION = 8;

    public byte[] buildQuestionnaireDslText(AnalysisRun run) {
        Questionnaire questionnaire = questionnaireOrNull(run);
        List<String> blocks = new ArrayList<>();
        if (questionnaire != null && questionnaire.getQuestions() != null) {
            questionnaire.getQuestions().stream()
                    .map(this::questionDsl)
                    .filter(StringUtils::hasText)
                    .forEach(blocks::add);
        }
        // 导出给腾讯问卷内容编辑器粘贴的文本 DSL；答卷导入仍然接受用户上传的 CSV/XLSX。
        return (String.join("\n\n", blocks) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Questionnaire questionnaireOrNull(AnalysisRun run) {
        if (run == null || run.getResearchPackage() == null || run.getResearchPackage().getResearchPlan() == null) {
            return null;
        }
        return run.getResearchPackage().getResearchPlan().getQuestionnaire();
    }

    private String questionDsl(SurveyQuestion question) {
        if (question == null || !StringUtils.hasText(question.getQuestion())) {
            return "";
        }
        String text = dslLine(question.getQuestion());
        String description = StringUtils.hasText(question.getDimension())
                ? "维度：" + dslLine(question.getDimension())
                : "";
        // 暂按选项是否存在区分单选/多行文本，避免导出阶段猜测量表、多选等题型。
        if (question.getOptions() == null || question.getOptions().isEmpty()) {
            return questionHeader(text, "多行文本题", description);
        }
        List<String> options = question.getOptions().stream()
                .filter(StringUtils::hasText)
                .map(this::dslLine)
                .toList();
        if (options.isEmpty()) {
            return questionHeader(text, "多行文本题", description);
        }
        return questionHeader(text, "单选题", description) + "\n" + String.join("\n", options);
    }

    private String questionHeader(String text, String type, String description) {
        if (!StringUtils.hasText(description)) {
            return text + "[" + type + "]";
        }
        return text + "[" + type + "](" + description + ")";
    }

    private String dslLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    public SurveyResultBatch importResults(Questionnaire questionnaire, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentIngestionException("问卷结果文件为空。");
        }
        ImportedSheet sheet = readSheet(file);
        if (sheet.headers().isEmpty() || sheet.rows().isEmpty()) {
            throw new DocumentIngestionException("问卷结果文件需要包含表头和至少一行答卷。");
        }
        return toResultBatch(questionnaire, sheet, originalFilename(file));
    }

    private SurveyResultBatch toResultBatch(Questionnaire questionnaire, ImportedSheet sheet, String filename) {
        List<Integer> questionIndexes = questionIndexes(sheet.headers());
        if (questionIndexes.isEmpty()) {
            throw new DocumentIngestionException("没有识别到问卷题目列，请保留题目作为表头。");
        }
        int responseCount = sheet.rows().stream()
                .mapToInt(row -> rowHasAnswer(row, questionIndexes) ? 1 : 0)
                .sum();
        if (responseCount == 0) {
            throw new DocumentIngestionException("问卷结果中没有可用答卷。");
        }
        String title = StringUtils.hasText(questionnaire == null ? null : questionnaire.getTitle())
                ? questionnaire.getTitle()
                : titleFromFilename(filename);
        String rawText = """
                Survey title: %s
                Source file: %s
                Imported at: %s
                Sample size: %d
                Respondent segments: %s

                %s

                Open feedback:
                %s
                """.formatted(
                title,
                filename,
                Instant.now(),
                responseCount,
                respondentSegments(sheet),
                questionFindings(sheet, questionIndexes, responseCount),
                openFeedback(sheet)
        );
        return new SurveyResultBatch("import-" + UUID.randomUUID(), title, responseCount, rawText.trim());
    }

    private List<Integer> questionIndexes(List<String> headers) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index);
            if (!StringUtils.hasText(header) || isMetadataHeader(header) || isSegmentHeader(header) || isOpenTextHeader(header)) {
                continue;
            }
            indexes.add(index);
        }
        return indexes;
    }

    private String questionFindings(ImportedSheet sheet, List<Integer> questionIndexes, int responseCount) {
        List<String> blocks = new ArrayList<>();
        for (int index : questionIndexes) {
            if (blocks.size() >= MAX_FINDINGS) {
                break;
            }
            String question = sheet.headers().get(index);
            Map<String, Integer> distribution = distribution(sheet.rows(), index);
            if (distribution.isEmpty()) {
                continue;
            }
            var top = distribution.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElseThrow();
            blocks.add("""
                    Q: %s
                    Distribution: %s
                    Finding: %d of %d respondents selected "%s", so this answer should be treated as a validated survey signal.
                    """.formatted(
                    question,
                    distributionText(distribution),
                    top.getValue(),
                    responseCount,
                    top.getKey()
            ).trim());
        }
        return blocks.isEmpty() ? "No structured question result was available." : String.join("\n\n", blocks);
    }

    private Map<String, Integer> distribution(List<List<String>> rows, int columnIndex) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<String> row : rows) {
            String value = cell(row, columnIndex);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            for (String answer : splitAnswer(value)) {
                counts.merge(answer, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(MAX_VALUES_PER_QUESTION)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private List<String> splitAnswer(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String normalized = value.trim();
        String[] parts = normalized.split("[;；|]");
        if (parts.length == 1) {
            return List.of(normalized);
        }
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String answer = part.trim();
            if (StringUtils.hasText(answer)) {
                values.add(answer);
            }
        }
        return values.isEmpty() ? List.of(normalized) : values;
    }

    private String respondentSegments(ImportedSheet sheet) {
        int segmentIndex = firstIndex(sheet.headers(), this::isSegmentHeader);
        if (segmentIndex < 0) {
            return "Imported respondents";
        }
        Map<String, Integer> segments = distribution(sheet.rows(), segmentIndex);
        if (segments.isEmpty()) {
            return "Imported respondents";
        }
        return distributionText(segments);
    }

    private String openFeedback(ImportedSheet sheet) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < sheet.headers().size(); index++) {
            if (isOpenTextHeader(sheet.headers().get(index))) {
                indexes.add(index);
            }
        }
        if (indexes.isEmpty()) {
            return "- No open feedback column was imported.";
        }
        List<String> feedback = new ArrayList<>();
        for (List<String> row : sheet.rows()) {
            for (int index : indexes) {
                String value = cell(row, index);
                if (StringUtils.hasText(value)) {
                    feedback.add("- " + trim(value, 220));
                }
                if (feedback.size() >= 8) {
                    return String.join("\n", feedback);
                }
            }
        }
        return feedback.isEmpty() ? "- No open feedback column was imported." : String.join("\n", feedback);
    }

    private boolean rowHasAnswer(List<String> row, List<Integer> questionIndexes) {
        return questionIndexes.stream().anyMatch(index -> StringUtils.hasText(cell(row, index)));
    }

    private String distributionText(Map<String, Integer> distribution) {
        return distribution.entrySet().stream()
                .map(entry -> "%s=%d".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("; "));
    }

    private ImportedSheet readSheet(MultipartFile file) {
        String filename = originalFilename(file);
        String extension = extension(filename);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new DocumentIngestionException("读取问卷结果文件失败。", ex);
        }
        return switch (extension) {
            case "csv" -> readCsv(bytes);
            case "xlsx" -> readXlsx(bytes);
            default -> throw new DocumentIngestionException("不支持的问卷结果文件类型：" + extension + "。当前支持 csv、xlsx。");
        };
    }

    private ImportedSheet readCsv(byte[] bytes) {
        List<List<String>> rows = parseCsv(decodeCsv(bytes));
        return sheetFromRows(rows);
    }

    private String decodeCsv(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(cell.toString().trim());
                addRow(rows, row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString().trim());
        addRow(rows, row);
        return rows;
    }

    private ImportedSheet readXlsx(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new DocumentIngestionException("XLSX 文件没有工作表。");
            }
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                short lastCellNum = row.getLastCellNum();
                for (int index = 0; index < Math.max(0, lastCellNum); index++) {
                    values.add(formatter.formatCellValue(row.getCell(index)).trim());
                }
                addRow(rows, values);
            }
            return sheetFromRows(rows);
        } catch (DocumentIngestionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentIngestionException("解析 XLSX 问卷结果失败。", ex);
        }
    }

    private ImportedSheet sheetFromRows(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return new ImportedSheet(List.of(), List.of());
        }
        List<String> headers = new ArrayList<>(rows.get(0));
        if (!headers.isEmpty()) {
            headers.set(0, stripBom(headers.get(0)));
        }
        List<List<String>> dataRows = rows.stream().skip(1).toList();
        return new ImportedSheet(headers, dataRows);
    }

    private void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(StringUtils::hasText)) {
            rows.add(new ArrayList<>(row));
        }
    }

    private String csvLine(List<String> values) {
        return values.stream()
                .map(value -> "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"")
                .collect(Collectors.joining(","));
    }

    private boolean isMetadataHeader(String header) {
        return METADATA_HEADER_PATTERN.matcher(header.trim()).matches();
    }

    private boolean isSegmentHeader(String header) {
        return SEGMENT_HEADER_PATTERN.matcher(header.trim()).matches();
    }

    private boolean isOpenTextHeader(String header) {
        return OPEN_TEXT_HEADER_PATTERN.matcher(header.trim()).matches();
    }

    private int firstIndex(List<String> values, java.util.function.Predicate<String> predicate) {
        for (int index = 0; index < values.size(); index++) {
            if (predicate.test(values.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index).trim() : "";
    }

    private String stripBom(String value) {
        return value == null ? "" : value.replaceFirst("^\uFEFF", "").trim();
    }

    private String originalFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        return StringUtils.hasText(filename) ? filename.replace('\\', '/').replaceAll("^.*/", "").trim() : "survey-results.csv";
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String titleFromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        String title = dot > 0 ? filename.substring(0, dot) : filename;
        return StringUtils.hasText(title) ? title : "Imported survey results";
    }

    private String trim(String value, int maxLength) {
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private record ImportedSheet(List<String> headers, List<List<String>> rows) {
    }
}
