package com.aiinsight.service;

import java.util.List;

public class NoopSearchProvider implements SearchProvider {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<SearchResult> search(String query, int count) {
        return List.of();
    }
}
