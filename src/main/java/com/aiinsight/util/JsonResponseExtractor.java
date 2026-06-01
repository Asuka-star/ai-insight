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
        IllegalArgumentException incompleteJson = null;
        for (int start = 0; start < trimmed.length(); start++) {
            char ch = trimmed.charAt(start);
            if (ch != '{' && ch != '[') {
                continue;
            }
            if (!looksLikeJsonStart(trimmed, start)) {
                continue;
            }
            try {
                int end = matchingEnd(trimmed, start);
                return trimmed.substring(start, end + 1);
            } catch (IllegalArgumentException ex) {
                incompleteJson = ex;
            }
        }
        if (incompleteJson != null) {
            throw incompleteJson;
        }
        throw new IllegalArgumentException("模型输出不包含 JSON");
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

    private static boolean looksLikeJsonStart(String text, int start) {
        int next = nextNonWhitespace(text, start + 1);
        if (next < 0) {
            return false;
        }
        char opener = text.charAt(start);
        char nextChar = text.charAt(next);
        if (opener == '{') {
            return nextChar == '"' || nextChar == '}';
        }
        return nextChar == '{'
                || nextChar == '['
                || nextChar == '"'
                || nextChar == ']'
                || nextChar == 't'
                || nextChar == 'f'
                || nextChar == 'n';
    }

    private static int nextNonWhitespace(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
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
