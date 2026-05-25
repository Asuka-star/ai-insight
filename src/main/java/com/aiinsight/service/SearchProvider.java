package com.aiinsight.service;

import java.util.List;

public interface SearchProvider {

    boolean isAvailable();

    List<SearchResult> search(String query, int count);
}
