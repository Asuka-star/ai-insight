package com.aiinsight.util;

import org.springframework.util.StringUtils;

public final class JsonResponseExtractor {

    private JsonResponseExtractor() {
    }

    public static String extractJsonValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("模型输出为空");
        }
        String trimmed = stripCodeFence(raw.trim());
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            throw new IllegalArgumentException("模型输出不包含 JSON");
        }
        int end = matchingEnd(trimmed, start);
        return trimmed.substring(start, end + 1);
    }

    public static String extractJsonObject(String raw) {
        String value = extractJsonValue(raw);
        if (!value.startsWith("{")) {
            throw new IllegalArgumentException("模型输出不包含 JSON 对象");
        }
        return value;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        return text
                .replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```\\s*$", "")
                .trim();
    }

    private static int matchingEnd(String text, int start) {
        char opener = text.charAt(start);
        char closer = opener == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == opener) {
                depth++;
                continue;
            }
            if (ch == closer) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("模型输出 JSON 未完整闭合");
    }
}
