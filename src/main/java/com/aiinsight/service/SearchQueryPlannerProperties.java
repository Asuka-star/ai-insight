package com.aiinsight.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("ai-insight.search-query-planner")
public class SearchQueryPlannerProperties {

    // 查询预算直接影响 Tavily 调用数和后续网页抓取量，默认只保留必要的权威/维度查询。
    private int maxSearchQueries = 8;
    private int maxSearchQueriesPerCompetitor = 7;

    int maxSearchQueries() {
        return clamp(maxSearchQueries, 1, 20);
    }

    int maxSearchQueriesPerCompetitor() {
        return clamp(maxSearchQueriesPerCompetitor, 1, 12);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
