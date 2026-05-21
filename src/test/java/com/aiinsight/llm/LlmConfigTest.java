package com.aiinsight.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    @Test
    void usesNoopClientWhenApiKeyIsMissing() {
        XiaomiLlmProperties properties = new XiaomiLlmProperties();

        LlmClient client = new LlmConfig().xiaomiLlmClient(properties);

        assertThat(client).isInstanceOf(NoopLlmClient.class);
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void createsSpringAiClientWhenApiKeyIsConfigured() {
        XiaomiLlmProperties properties = new XiaomiLlmProperties();
        properties.setApiKey("test-api-key");

        LlmClient client = new LlmConfig().xiaomiLlmClient(properties);

        assertThat(client).isInstanceOf(SpringAiLlmClient.class);
        assertThat(client.isAvailable()).isTrue();
    }
}
