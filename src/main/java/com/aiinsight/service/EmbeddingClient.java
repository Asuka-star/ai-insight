package com.aiinsight.service;

import java.util.List;

public interface EmbeddingClient {

    boolean isAvailable();

    String model();

    List<List<Double>> embed(List<String> inputs);
}
