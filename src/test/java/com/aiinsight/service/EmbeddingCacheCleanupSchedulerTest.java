package com.aiinsight.service;

import com.aiinsight.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingCacheCleanupSchedulerTest {

    @Test
    void deletesExpiredCacheEntriesWhenCacheTtlIsEnabled() {
        AnalysisRunRepository repository = mock(AnalysisRunRepository.class);
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setCacheTtl(Duration.ofDays(30));
        when(repository.deleteExpiredEmbeddingCache(Duration.ofDays(30))).thenReturn(3);
        EmbeddingCacheCleanupScheduler scheduler = new EmbeddingCacheCleanupScheduler(repository, properties);

        scheduler.cleanupExpiredEmbeddingCache();

        verify(repository).deleteExpiredEmbeddingCache(Duration.ofDays(30));
    }

    @Test
    void skipsCleanupWhenCacheIsDisabledOrTtlIsNonPositive() {
        AnalysisRunRepository repository = mock(AnalysisRunRepository.class);
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setCacheEnabled(false);
        EmbeddingCacheCleanupScheduler scheduler = new EmbeddingCacheCleanupScheduler(repository, properties);

        scheduler.cleanupExpiredEmbeddingCache();

        verify(repository, never()).deleteExpiredEmbeddingCache(org.mockito.ArgumentMatchers.any());

        properties.setCacheEnabled(true);
        properties.setCacheTtl(Duration.ZERO);
        scheduler.cleanupExpiredEmbeddingCache();

        verify(repository, never()).deleteExpiredEmbeddingCache(org.mockito.ArgumentMatchers.any());
    }
}
