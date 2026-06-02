package com.aiinsight.service;

import com.aiinsight.config.HttpClientFactory;
import com.aiinsight.config.HttpProxyProperties;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final int MAX_ATTEMPTS = 2;

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties,
                                           ObjectMapper objectMapper,
                                           HttpProxyProperties proxyProperties) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClientFactory.builder(properties.getTimeout(), proxyProperties)
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
    public String model() {
        return properties.getModel();
    }

    @Override
    public List<List<Double>> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel());
        request.put("input", inputs);
        if (properties.getDimensions() > 0) {
            request.put("dimensions", properties.getDimensions());
        }
        String body = postWithRetry(request);
        return parseEmbeddings(body);
    }

    private String postWithRetry(Map<String, Object> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri(endpoint())
                        .body(request)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException ex) {
                lastFailure = ex;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(ex)) {
                    throw ex;
                }
                long delayMs = retryDelayMs(ex);
                log.warn("Embedding request failed transiently; retrying after {}ms: model={}, exceptionType={}, message={}",
                        delayMs,
                        properties.getModel(),
                        ex.getClass().getName(),
                        ex.getMessage());
                sleepQuietly(delayMs);
            }
        }
        throw lastFailure;
    }

    // 429 时优先用 Retry-After 头，否则默认 1.5 秒；5xx / IO 错误默认 500ms
    private long retryDelayMs(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            if (responseException.getStatusCode().value() == 429) {
                HttpHeaders headers = responseException.getResponseHeaders();
                String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                if (retryAfter != null) {
                    try {
                        long seconds = Long.parseLong(retryAfter.trim());
                        return Math.min(seconds * 1000, 10_000);
                    } catch (NumberFormatException ignored) {
                        // Retry-After 可能是 HTTP-date 格式，直接用默认值
                    }
                }
                return 1500;
            }
        }
        return 500;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding retry interrupted", ie);
        }
    }

    private String endpoint() {
        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        String path = properties.getEmbeddingsPath();
        if (!StringUtils.hasText(path)) {
            path = "/embeddings";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return baseUrl + path;
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

    private List<List<Double>> parseEmbeddings(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("Embedding response missing data array");
            }
            List<EmbeddingResult> results = new ArrayList<>();
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                JsonNode vectorNode = item.path("embedding");
                if (!vectorNode.isArray()) {
                    continue;
                }
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : vectorNode) {
                    vector.add(value.asDouble());
                }
                int index = item.has("index") ? item.path("index").asInt() : fallbackIndex;
                results.add(new EmbeddingResult(index, vector));
                fallbackIndex++;
            }
            return results.stream()
                    .sorted(Comparator.comparingInt(EmbeddingResult::index))
                    .map(EmbeddingResult::vector)
                    .toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse embedding response", ex);
        }
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("/+$", "");
    }

    private record EmbeddingResult(int index, List<Double> vector) {
    }
}
