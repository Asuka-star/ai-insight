package com.aiinsight.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
class AgentRoutingLlmClient implements LlmClient {

    private final LlmClient defaultClient;
    private final Map<String, LlmClient> agentClients;

    AgentRoutingLlmClient(LlmClient defaultClient, Map<String, LlmClient> agentClients) {
        this.defaultClient = defaultClient;
        this.agentClients = agentClients == null ? Map.of() : Map.copyOf(agentClients);
    }

    @Override
    public boolean isAvailable() {
        return defaultClient.isAvailable();
    }

    @Override
    public String complete(ChatRequest request) {
        LlmClient client = routeClient(request);
        return client.complete(request);
    }

    private LlmClient routeClient(ChatRequest request) {
        if (request == null || request.getAgentName() == null || request.getAgentName().isBlank()) {
            return defaultClient;
        }
        LlmClient routed = agentClients.get(request.getAgentName());
        if (routed == null || !routed.isAvailable()) {
            return defaultClient;
        }
        log.debug("LLM request routed by agent: agent={}, subtask={}", request.getAgentName(), request.getSubtaskName());
        return routed;
    }
}
