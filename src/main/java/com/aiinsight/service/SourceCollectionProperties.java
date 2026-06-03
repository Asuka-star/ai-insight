package com.aiinsight.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("ai-insight.source-collection")
public class SourceCollectionProperties {

    // 默认值偏保守：先压住搜索结果和并发上限，长报告可通过配置显式放大采集预算。
    private int maxResultsPerQuery = 2;
    private int minSearchSources = 8;
    private int smallBatchSearchSourcesPerCompetitor = 3;
    private int largeBatchSearchSourcesPerCompetitor = 1;
    private int largeBatchCompetitorThreshold = 12;
    private int hardMaxSearchSources = 24;
    private int maxParallelBatches = 8;
    private int maxParallelSearches = 4;
    private int maxParallelFetches = 8;

    int maxResultsPerQuery() {
        return clamp(maxResultsPerQuery, 1, 5);
    }

    int minSearchSources() {
        return clamp(minSearchSources, 1, hardMaxSearchSources());
    }

    int smallBatchSearchSourcesPerCompetitor() {
        return clamp(smallBatchSearchSourcesPerCompetitor, 1, 6);
    }

    int largeBatchSearchSourcesPerCompetitor() {
        return clamp(largeBatchSearchSourcesPerCompetitor, 1, 4);
    }

    int largeBatchCompetitorThreshold() {
        return Math.max(1, largeBatchCompetitorThreshold);
    }

    int hardMaxSearchSources() {
        return clamp(hardMaxSearchSources, 1, 80);
    }

    int maxParallelBatches() {
        return clamp(maxParallelBatches, 1, 12);
    }

    int maxParallelSearches() {
        return clamp(maxParallelSearches, 1, 12);
    }

    int maxParallelFetches() {
        return clamp(maxParallelFetches, 1, 16);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
