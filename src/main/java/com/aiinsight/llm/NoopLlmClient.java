package com.aiinsight.llm;

class NoopLlmClient implements LlmClient {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String complete(ChatRequest request) {
        throw new IllegalStateException("LLM is not configured");
    }
}
