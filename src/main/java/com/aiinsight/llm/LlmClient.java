package com.aiinsight.llm;

public interface LlmClient {

    boolean isAvailable();

    String complete(ChatRequest request);
}
