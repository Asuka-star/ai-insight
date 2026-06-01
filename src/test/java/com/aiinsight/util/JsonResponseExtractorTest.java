package com.aiinsight.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResponseExtractorTest {

    @Test
    void extractsBalancedJsonAndIgnoresTrailingCommentary() {
        String raw = """
                ```json
                {"profiles":[{"productName":"Cursor","note":"contains } and [ inside text"}]}
                ```
                以上是结构化结果。
                """;

        String json = JsonResponseExtractor.extractJsonObject(raw);

        assertThat(json).isEqualTo("{\"profiles\":[{\"productName\":\"Cursor\",\"note\":\"contains } and [ inside text\"}]}");
    }

    @Test
    void extractsArrayJsonValue() {
        String raw = "Here is the result:\n[{\"id\":\"S1\"}]\nDone.";

        assertThat(JsonResponseExtractor.extractJsonValue(raw)).isEqualTo("[{\"id\":\"S1\"}]");
    }

    @Test
    void skipsCitationMarkersBeforeJsonObject() {
        String raw = "Based on [S1] and [S2]:\n{\"profiles\":[{\"productName\":\"Cursor\"}]}";

        assertThat(JsonResponseExtractor.extractJsonObject(raw))
                .isEqualTo("{\"profiles\":[{\"productName\":\"Cursor\"}]}");
    }

    @Test
    void skipsCitationMarkersBeforeJsonArray() {
        String raw = "Evidence [S1] supports this.\n[{\"id\":\"S1\"}]";

        assertThat(JsonResponseExtractor.extractJsonValue(raw)).isEqualTo("[{\"id\":\"S1\"}]");
    }
}
