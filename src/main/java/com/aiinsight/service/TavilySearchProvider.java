package com.aiinsight.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TavilySearchProvider implements SearchProvider {

    private final TavilySearchProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public TavilySearchProvider(TavilySearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public boolean isAvailable() {
        return StringUtils.hasText(properties.getApiKey());
    }

    @Override
    public List<SearchResult> search(String query, int count) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Map<String, Object> request = Map.of(
                "query", query,
                "search_depth", properties.getSearchDepth(),
                "max_results", Math.max(1, Math.min(count, properties.getMaxResults())),
                "include_raw_content", properties.isIncludeRawContent()
        );
        String body = restClient.post()
                .uri(properties.getBaseUrl())
                .body(request)
                .retrieve()
                .body(String.class);
        return parseResults(body, query);
    }

    private List<SearchResult> parseResults(String body, String query) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return List.of();
            }
            List<SearchResult> parsed = new ArrayList<>();
            int rank = 1;
            for (JsonNode item : results) {
                String url = item.path("url").asText("");
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                String content = firstText(
                        item.path("raw_content").asText(""),
                        item.path("content").asText(""),
                        item.path("snippet").asText("")
                );
                parsed.add(new SearchResult(
                        item.path("title").asText(url),
                        url,
                        content,
                        query,
                        rank
                ));
                rank++;
            }
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Tavily Search response", ex);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }
}
