package com.aiinsight.service;

import java.util.List;

public class NoopEmbeddingClient implements EmbeddingClient {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String model() {
        return "noop-embedding";
    }

    @Override
    public List<List<Double>> embed(List<String> inputs) {
        return List.of();
    }
}
