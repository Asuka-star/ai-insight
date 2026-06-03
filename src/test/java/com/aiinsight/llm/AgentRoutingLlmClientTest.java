package com.aiinsight.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRoutingLlmClientTest {

    @Test
    void routesClarifierRequestsToConfiguredAgentClient() {
        RecordingClient defaultClient = new RecordingClient("default-response");
        RecordingClient clarifierClient = new RecordingClient("clarifier-response");
        AgentRoutingLlmClient client = new AgentRoutingLlmClient(defaultClient, Map.of("CLARIFIER", clarifierClient));

        String result = client.complete(request("CLARIFIER"));

        assertThat(result).isEqualTo("clarifier-response");
        assertThat(defaultClient.calls).isZero();
        assertThat(clarifierClient.calls).isEqualTo(1);
    }

    @Test
    void fallsBackToDefaultClientWhenAgentIsNotConfigured() {
        RecordingClient defaultClient = new RecordingClient("default-response");
        RecordingClient clarifierClient = new RecordingClient("clarifier-response");
        AgentRoutingLlmClient client = new AgentRoutingLlmClient(defaultClient, Map.of("CLARIFIER", clarifierClient));

        String result = client.complete(request("RESEARCHER"));

        assertThat(result).isEqualTo("default-response");
        assertThat(defaultClient.calls).isEqualTo(1);
        assertThat(clarifierClient.calls).isZero();
    }

    @Test
    void reportsAvailabilityFromDefaultClient() {
        AgentRoutingLlmClient client = new AgentRoutingLlmClient(
                new RecordingClient("default-response", true),
                Map.of("CLARIFIER", new RecordingClient("clarifier-response", true))
        );

        assertThat(client.isAvailable()).isTrue();
    }

    private ChatRequest request(String agentName) {
        return new ChatRequest(
                List.of(ChatMessage.user("hello")),
                ChatOptions.deterministic()
        ).tagged(agentName, "test");
    }

    private static class RecordingClient implements LlmClient {

        private final String response;
        private final boolean available;
        private int calls;

        RecordingClient(String response) {
            this(response, true);
        }

        RecordingClient(String response, boolean available) {
            this.response = response;
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String complete(ChatRequest request) {
            calls++;
            return response;
        }
    }
}
