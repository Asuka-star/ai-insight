package com.aiinsight.llm;

import java.time.Duration;

interface OpenAiCompatibleLlmProperties {

    String getApiKey();

    String getBaseUrl();

    String getCompletionsPath();

    String getModel();

    default String getDisplayModel() {
        return getModel();
    }

    Duration getTimeout();
}
