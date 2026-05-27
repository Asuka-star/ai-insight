package com.aiinsight.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class FetchedPageCache {

    private final Duration ttl;
    private final Store store;

    FetchedPageCache(Duration ttl) {
        this(ttl, new InMemoryStore());
    }

    FetchedPageCache(Duration ttl, Store store) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.store = store == null ? new InMemoryStore() : store;
    }

    static FetchedPageCache jdbc(Duration ttl, JdbcTemplate jdbcTemplate) {
        return new FetchedPageCache(ttl, new JdbcStore(jdbcTemplate));
    }

    Optional<WebPageFetchService.FetchedPage> get(URI uri) {
        if (disabled()) {
            return Optional.empty();
        }
        String key = normalize(uri);
        Optional<CacheEntry> entryCandidate = store.get(key);
        if (entryCandidate.isEmpty()) {
            return Optional.empty();
        }
        CacheEntry entry = entryCandidate.get();
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired(ttl)) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.page().cachedCopy(entry.cachedAt()));
    }

    void put(URI requestedUri, WebPageFetchService.FetchedPage page) {
        if (disabled() || page == null || !page.isCacheable()) {
            return;
        }
        CacheEntry entry = new CacheEntry(page.fetchedCopy(), Instant.now());
        store.put(normalize(requestedUri), entry);
        try {
            URI finalUri = URI.create(page.getUrl());
            store.put(normalize(finalUri), entry);
        } catch (RuntimeException ignored) {
            // The requested URL key is still enough for same-input reuse.
        }
    }

    int size() {
        return store.size();
    }

    private boolean disabled() {
        return ttl.isZero() || ttl.isNegative();
    }

    private String normalize(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String portPart = port < 0 ? "" : ":" + port;
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return scheme + "://" + host + portPart + path.replaceFirst("/+$", "") + query;
    }

    interface Store {

        Optional<CacheEntry> get(String normalizedUrl);

        void put(String normalizedUrl, CacheEntry entry);

        void remove(String normalizedUrl);

        int size();
    }

    private static class InMemoryStore implements Store {

        private final ConcurrentMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

        @Override
        public Optional<CacheEntry> get(String normalizedUrl) {
            return Optional.ofNullable(entries.get(normalizedUrl));
        }

        @Override
        public void put(String normalizedUrl, CacheEntry entry) {
            entries.put(normalizedUrl, entry);
        }

        @Override
        public void remove(String normalizedUrl) {
            entries.remove(normalizedUrl);
        }

        @Override
        public int size() {
            return entries.size();
        }
    }

    private static class JdbcStore implements Store {

        private final JdbcTemplate jdbcTemplate;

        JdbcStore(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
            ensureSchema();
        }

        @Override
        public Optional<CacheEntry> get(String normalizedUrl) {
            return jdbcTemplate.query("""
                            select final_url, title, raw_text, compliance_note, source_type, source_quality,
                                   failure_reason, status_code, content_type, content_hash, fetched_at,
                                   usable, page_status, cached_at
                            from fetched_page_cache
                            where normalized_url = ?
                            """,
                    (rs, rowNum) -> toEntry(rs),
                    normalizedUrl
            ).stream().findFirst();
        }

        @Override
        public void put(String normalizedUrl, CacheEntry entry) {
            WebPageFetchService.FetchedPage page = entry.page();
            jdbcTemplate.update("""
                            insert into fetched_page_cache
                                (normalized_url, final_url, title, raw_text, compliance_note, source_type,
                                 source_quality, failure_reason, status_code, content_type, content_hash,
                                 fetched_at, usable, page_status, cached_at, updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict (normalized_url) do update set
                                final_url = excluded.final_url,
                                title = excluded.title,
                                raw_text = excluded.raw_text,
                                compliance_note = excluded.compliance_note,
                                source_type = excluded.source_type,
                                source_quality = excluded.source_quality,
                                failure_reason = excluded.failure_reason,
                                status_code = excluded.status_code,
                                content_type = excluded.content_type,
                                content_hash = excluded.content_hash,
                                fetched_at = excluded.fetched_at,
                                usable = excluded.usable,
                                page_status = excluded.page_status,
                                cached_at = excluded.cached_at,
                                updated_at = excluded.updated_at
                            """,
                    normalizedUrl,
                    page.getUrl(),
                    page.getTitle(),
                    page.getRawText(),
                    page.getComplianceNote(),
                    page.getSourceType(),
                    page.getSourceQuality(),
                    page.getFailureReason(),
                    page.getStatusCode(),
                    page.getContentType(),
                    page.getContentHash(),
                    timestamp(page.getFetchedAt()),
                    page.isUsable(),
                    page.getStatus(),
                    timestamp(entry.cachedAt()),
                    timestamp(Instant.now())
            );
        }

        @Override
        public void remove(String normalizedUrl) {
            jdbcTemplate.update("delete from fetched_page_cache where normalized_url = ?", normalizedUrl);
        }

        @Override
        public int size() {
            Integer count = jdbcTemplate.queryForObject("select count(*) from fetched_page_cache", Integer.class);
            return count == null ? 0 : count;
        }

        private void ensureSchema() {
            jdbcTemplate.execute("""
                    create table if not exists fetched_page_cache (
                        normalized_url text primary key,
                        final_url text not null,
                        title text,
                        raw_text text,
                        compliance_note text,
                        source_type varchar(64),
                        source_quality varchar(32),
                        failure_reason varchar(64),
                        status_code integer,
                        content_type text,
                        content_hash varchar(64),
                        fetched_at timestamptz,
                        usable boolean,
                        page_status varchar(64),
                        cached_at timestamptz not null,
                        updated_at timestamptz not null
                    )
                    """);
            jdbcTemplate.execute("""
                    create index if not exists idx_fetched_page_cache_hash
                    on fetched_page_cache (content_hash)
                    """);
            jdbcTemplate.execute("""
                    create index if not exists idx_fetched_page_cache_cached_at
                    on fetched_page_cache (cached_at desc)
                    """);
        }

        private CacheEntry toEntry(ResultSet rs) throws SQLException {
            WebPageFetchService.FetchedPage page = WebPageFetchService.FetchedPage.restored(
                    rs.getString("final_url"),
                    rs.getString("title"),
                    rs.getString("raw_text"),
                    rs.getString("compliance_note"),
                    rs.getString("source_type"),
                    rs.getString("source_quality"),
                    rs.getString("failure_reason"),
                    rs.getInt("status_code"),
                    rs.getString("content_type"),
                    rs.getString("content_hash"),
                    instant(rs.getTimestamp("fetched_at")),
                    rs.getBoolean("usable"),
                    rs.getString("page_status")
            );
            return new CacheEntry(page, instant(rs.getTimestamp("cached_at")));
        }

        private Timestamp timestamp(Instant instant) {
            return instant == null ? null : Timestamp.from(instant);
        }

        private Instant instant(Timestamp timestamp) {
            return timestamp == null ? Instant.now() : timestamp.toInstant();
        }
    }

    private record CacheEntry(WebPageFetchService.FetchedPage page, Instant cachedAt) {

        boolean expired(Duration ttl) {
            return cachedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
