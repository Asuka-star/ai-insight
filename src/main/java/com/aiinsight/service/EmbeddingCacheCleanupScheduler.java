package com.aiinsight.service;

import com.aiinsight.repository.AnalysisRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingCacheCleanupScheduler {

    private final AnalysisRunRepository repository;
    private final EmbeddingProperties properties;

    @Scheduled(
            fixedDelayString = "${ai-insight.embedding.cache-cleanup-interval-ms:86400000}",
            initialDelayString = "${ai-insight.embedding.cache-cleanup-initial-delay-ms:600000}"
    )
    public void cleanupExpiredEmbeddingCache() {
        if (!properties.isCacheEnabled()
                || properties.getCacheTtl() == null
                || properties.getCacheTtl().isZero()
                || properties.getCacheTtl().isNegative()) {
            return;
        }
        int deleted = repository.deleteExpiredEmbeddingCache(properties.getCacheTtl());
        if (deleted > 0) {
            log.info("Expired embedding cache entries cleaned: deleted={}, ttl={}", deleted, properties.getCacheTtl());
        }
    }
}
