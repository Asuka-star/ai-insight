package com.aiinsight.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    @Test
    void usesNoopClientWhenApiKeyIsMissing() {
        System.setProperty("ai.insight.llm.dotenv.enabled", "false");
        XiaomiLlmProperties properties = new XiaomiLlmProperties();

        LlmClient client = new LlmConfig().xiaomiLlmClient(properties);

        assertThat(client).isInstanceOf(NoopLlmClient.class);
        assertThat(client.isAvailable()).isFalse();
        System.clearProperty("ai.insight.llm.dotenv.enabled");
    }

    @Test
    void createsSpringAiClientWhenApiKeyIsConfigured() {
        System.setProperty("ai.insight.llm.dotenv.enabled", "false");
        XiaomiLlmProperties properties = new XiaomiLlmProperties();
        properties.setApiKey("test-api-key");

        LlmClient client = new LlmConfig().xiaomiLlmClient(properties);

        assertThat(client).isInstanceOf(SpringAiLlmClient.class);
        assertThat(client.isAvailable()).isTrue();
        System.clearProperty("ai.insight.llm.dotenv.enabled");
    }
}
