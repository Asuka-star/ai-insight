package com.aiinsight.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class TavilySearchProvider implements SearchProvider {

    private static final int MAX_ATTEMPTS = 2;

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
        String body = postWithRetry(request, query);
        return parseResults(body, query);
    }

    private String postWithRetry(Map<String, Object> request, String query) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri(properties.getBaseUrl())
                        .body(request)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException ex) {
                lastFailure = ex;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(ex)) {
                    throw ex;
                }
                log.warn("Tavily search request failed transiently; retrying once: query={}, exceptionType={}, message={}",
                        query,
                        ex.getClass().getName(),
                        ex.getMessage());
            }
        }
        throw lastFailure;
    }

    private boolean isRetryable(RestClientException ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        if (ex instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        String message = ex.getMessage();
        return message != null && (message.contains("Connection reset")
                || message.contains("timed out")
                || message.contains("I/O error"));
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
