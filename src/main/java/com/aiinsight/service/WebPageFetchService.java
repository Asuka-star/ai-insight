package com.aiinsight.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class WebPageFetchService {

    private static final String USER_AGENT = "AI-Insight-ResearchBot/0.1";
    private static final int MAX_TEXT_LENGTH = 12_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HOST_MINIMUM_INTERVAL = Duration.ofSeconds(1);
    private static final Duration FETCH_CACHE_TTL = Duration.ofHours(6);
    private static final int MAX_FETCH_ATTEMPTS = 2;
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final RestClient restClient;
    private final SourceTypeClassifier sourceTypeClassifier;
    private final PageQualityEvaluator pageQualityEvaluator;
    private final RobotsPolicyService robotsPolicyService;
    private final HostRateLimiter hostRateLimiter;
    private final FetchedPageCache fetchedPageCache;
    private final int maxFetchAttempts;

    public WebPageFetchService() {
        this(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Autowired
    public WebPageFetchService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(
                CONNECT_TIMEOUT,
                READ_TIMEOUT,
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                HOST_MINIMUM_INTERVAL,
                FETCH_CACHE_TTL,
                MAX_FETCH_ATTEMPTS,
                cacheFrom(jdbcTemplateProvider)
        );
    }

    WebPageFetchService(Duration connectTimeout, Duration readTimeout) {
        this(
                connectTimeout,
                readTimeout,
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                HOST_MINIMUM_INTERVAL,
                FETCH_CACHE_TTL,
                MAX_FETCH_ATTEMPTS,
                new FetchedPageCache(FETCH_CACHE_TTL)
        );
    }

    WebPageFetchService(Duration connectTimeout,
                        Duration readTimeout,
                        SourceTypeClassifier sourceTypeClassifier,
                        PageQualityEvaluator pageQualityEvaluator,
                        Duration hostMinimumInterval,
                        Duration fetchCacheTtl,
                        int maxFetchAttempts) {
        this(
                connectTimeout,
                readTimeout,
                sourceTypeClassifier,
                pageQualityEvaluator,
                hostMinimumInterval,
                fetchCacheTtl,
                maxFetchAttempts,
                null,
                null,
                new FetchedPageCache(fetchCacheTtl)
        );
    }

    WebPageFetchService(Duration connectTimeout,
                        Duration readTimeout,
                        SourceTypeClassifier sourceTypeClassifier,
                        PageQualityEvaluator pageQualityEvaluator,
                        Duration hostMinimumInterval,
                        Duration fetchCacheTtl,
                        int maxFetchAttempts,
                        FetchedPageCache fetchedPageCache) {
        this(
                connectTimeout,
                readTimeout,
                sourceTypeClassifier,
                pageQualityEvaluator,
                hostMinimumInterval,
                fetchCacheTtl,
                maxFetchAttempts,
                null,
                null,
                fetchedPageCache
        );
    }

    WebPageFetchService(Duration connectTimeout,
                        Duration readTimeout,
                        SourceTypeClassifier sourceTypeClassifier,
                        PageQualityEvaluator pageQualityEvaluator,
                        Duration hostMinimumInterval,
                        Duration fetchCacheTtl,
                        int maxFetchAttempts,
                        Duration robotsAvailableTtl,
                        Duration robotsUnavailableTtl,
                        FetchedPageCache fetchedPageCache) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
        this.sourceTypeClassifier = sourceTypeClassifier;
        this.pageQualityEvaluator = pageQualityEvaluator;
        this.robotsPolicyService = new RobotsPolicyService(restClient, USER_AGENT, robotsAvailableTtl, robotsUnavailableTtl, Instant::now);
        this.hostRateLimiter = new HostRateLimiter(hostMinimumInterval);
        this.fetchedPageCache = fetchedPageCache == null ? new FetchedPageCache(fetchCacheTtl) : fetchedPageCache;
        this.maxFetchAttempts = Math.max(1, maxFetchAttempts);
    }

    private static FetchedPageCache cacheFrom(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new FetchedPageCache(FETCH_CACHE_TTL);
        }
        return FetchedPageCache.jdbc(FETCH_CACHE_TTL, jdbcTemplate);
    }

    public FetchedPage fetch(String url) {
        URI uri = URI.create(url);
        validateHttpUri(uri);
        RobotsPolicyService.RobotsDecision robotsDecision = robotsPolicyService.decide(uri);
        if (!robotsDecision.allowed()) {
            log.warn("Web page fetch blocked by robots: url={}, note={}", url, robotsDecision.note());
            return FetchedPage.blocked(url, robotsDecision.note());
        }
        Optional<FetchedPage> cachedPage = fetchedPageCache.get(uri);
        if (cachedPage.isPresent()) {
            FetchedPage page = cachedPage.get();
            log.info("Web page fetch cache hit: url={}, finalUrl={}, title={}, sourceType={}, sourceQuality={}, contentHash={}, robotsNote={}",
                    url,
                    page.getUrl(),
                    page.getTitle(),
                    page.getSourceType(),
                    page.getSourceQuality(),
                    page.getContentHash(),
                    robotsDecision.note());
            return page;
        }
        try {
            hostRateLimiter.acquire(uri);
            FetchAttemptResult fetchResult = sendWithRetry(uri);
            HttpResponse<String> response = fetchResult.response();
            String html = response.body();
            URI finalUri = response.uri();
            String finalUrl = finalUri.toString();
            String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
            String title = extractTitle(html, finalUri);
            String text = extractText(html);
            String truncatedText = truncate(text);
            String textHash = contentHash(truncatedText);
            String sourceType = sourceTypeClassifier.classify(finalUrl, title);
            PageQualityEvaluator.PageQualityResult quality = pageQualityEvaluator.evaluate(
                    title,
                    text,
                    response.statusCode(),
                    contentType
            );
            String sourceQuality = quality.usable()
                    ? sourceTypeClassifier.qualityFor(sourceType, "FETCHED", "LIVE_FETCHED")
                    : quality.sourceQuality();
            String complianceNote = appendFetchMetadata(
                    redirectNote(url, finalUrl, robotsDecision.note()),
                    response.statusCode(),
                    contentType,
                    sourceType,
                    sourceQuality,
                    quality.failureReason(),
                    fetchResult.retryCount(),
                    textHash
            );
            log.info("Web page fetch completed: url={}, title={}, statusCode={}, sourceType={}, sourceQuality={}, failureReason={}, retryCount={}, rawTextChars={}, note={}",
                    finalUrl,
                    title,
                    response.statusCode(),
                    sourceType,
                    sourceQuality,
                    quality.failureReason(),
                    fetchResult.retryCount(),
                    text.length(),
                    complianceNote);
            if (!quality.usable()) {
                FetchedPage page = FetchedPage.unusable(
                        finalUrl,
                        title,
                        truncatedText,
                        complianceNote,
                        sourceType,
                        sourceQuality,
                        quality.failureReason(),
                        response.statusCode(),
                        contentType
                );
                fetchedPageCache.put(uri, page);
                return page;
            }
            FetchedPage page = FetchedPage.success(
                    finalUrl,
                    title,
                    truncatedText,
                    complianceNote,
                    sourceType,
                    sourceQuality,
                    response.statusCode(),
                    contentType
            );
            fetchedPageCache.put(uri, page);
            return page;
        } catch (HttpTimeoutException ex) {
            log.warn("Web page fetch timeout: url={}, exceptionType={}, message={}, note={}",
                    url,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取超时：" + ex.getMessage() + "；" + robotsDecision.note(), "TIMEOUT");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Web page fetch interrupted: url={}, exceptionType={}, message={}, note={}",
                    url,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取被中断：" + ex.getMessage() + "；" + robotsDecision.note(), "TIMEOUT");
        } catch (RuntimeException ex) {
            log.warn("Web page fetch failed: url={}, exceptionType={}, message={}, note={}",
                    url,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage() + "；" + robotsDecision.note(), "UNKNOWN");
        } catch (Exception ex) {
            log.warn("Web page fetch failed: url={}, exceptionType={}, message={}, note={}",
                    url,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage() + "；" + robotsDecision.note(), "UNKNOWN");
        }
    }

    private FetchAttemptResult sendWithRetry(URI uri) throws IOException, InterruptedException {
        int retryCount = 0;
        for (int attempt = 1; attempt <= maxFetchAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(pageRequest(uri), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < maxFetchAttempts) {
                    retryCount++;
                    sleepBeforeRetry(attempt);
                    continue;
                }
                return new FetchAttemptResult(response, retryCount);
            } catch (HttpTimeoutException ex) {
                if (attempt >= maxFetchAttempts) {
                    throw ex;
                }
                retryCount++;
                sleepBeforeRetry(attempt);
            } catch (IOException ex) {
                if (attempt >= maxFetchAttempts) {
                    throw ex;
                }
                retryCount++;
                sleepBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("Fetch retry loop ended without a response");
    }

    private void sleepBeforeRetry(int attempt) throws InterruptedException {
        long backoffMillis = Math.min(500, 100L * attempt);
        Thread.sleep(backoffMillis);
    }

    private HttpRequest pageRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .GET()
                .build();
    }

    private String redirectNote(String originalUrl, String finalUrl, String robotsNote) {
        if (normalizeUrl(originalUrl).equals(normalizeUrl(finalUrl))) {
            return robotsNote;
        }
        return robotsNote + " Redirect followed from " + originalUrl + " to " + finalUrl + ".";
    }

    private void validateHttpUri(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Source URL must be an absolute URL: " + uri);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http/https source URLs are supported: " + uri);
        }
    }

    private String extractTitle(String html, URI uri) {
        if (html == null) {
            return uri.getHost();
        }
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            return normalizeText(matcher.group(1));
        }
        return uri.getHost();
    }

    private String extractText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript,svg,canvas,form,iframe,nav,header,footer,aside").remove();
        String mainText = selectMainContent(document)
                .map(Element::text)
                .map(this::normalizeText)
                .orElse("");
        if (!mainText.isBlank()) {
            return mainText;
        }
        return normalizeText(document.body() == null ? document.text() : document.body().text());
    }

    private Optional<Element> selectMainContent(Document document) {
        return document.select("main, article, [role=main], #content, .content, #main, .main")
                .stream()
                .max(Comparator.comparingInt(element -> element.text().length()));
    }

    private String normalizeText(String text) {
        return HtmlUtils.htmlUnescape(text).replaceAll("\\s+", " ").trim();
    }

    private String normalizeUrl(String url) {
        return url == null ? "" : url.trim().replaceFirst("/+$", "").toLowerCase(Locale.ROOT);
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH);
    }

    private String appendFetchMetadata(String note,
                                       int statusCode,
                                       String contentType,
                                       String sourceType,
                                       String sourceQuality,
                                       String failureReason,
                                       int retryCount,
                                       String contentHash) {
        return "%s statusCode=%d; contentType=%s; sourceType=%s; sourceQuality=%s; failureReason=%s; retryCount=%d; contentHash=%s."
                .formatted(
                        note == null ? "" : note.trim(),
                        statusCode,
                        contentType == null || contentType.isBlank() ? "unknown" : contentType,
                        sourceType,
                        sourceQuality,
                        failureReason,
                        retryCount,
                        contentHash
                )
                .trim();
    }

    private static String contentHash(String rawText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((rawText == null ? "" : rawText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record FetchAttemptResult(HttpResponse<String> response, int retryCount) {
    }

    @Getter
    public static class FetchedPage {

        private final String url;
        private final String title;
        private final String rawText;
        private final String complianceNote;
        private final String sourceType;
        private final String sourceQuality;
        private final String failureReason;
        private final int statusCode;
        private final String contentType;
        private final String contentHash;
        private final Instant fetchedAt;
        private final boolean cacheHit;
        private final boolean usable;
        private final String status;

        private FetchedPage(String url,
                            String title,
                            String rawText,
                            String complianceNote,
                            String sourceType,
                            String sourceQuality,
                            String failureReason,
                            int statusCode,
                            String contentType,
                            String contentHash,
                            Instant fetchedAt,
                            boolean cacheHit,
                            boolean usable,
                            String status) {
            this.url = url;
            this.title = title;
            this.rawText = rawText;
            this.complianceNote = complianceNote;
            this.sourceType = sourceType;
            this.sourceQuality = sourceQuality;
            this.failureReason = failureReason;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.contentHash = contentHash;
            this.fetchedAt = fetchedAt;
            this.cacheHit = cacheHit;
            this.usable = usable;
            this.status = status;
        }

        static FetchedPage success(String url, String title, String rawText, String complianceNote) {
            return success(url, title, rawText, complianceNote, "article", "MEDIUM", 200, "text/html");
        }

        static FetchedPage success(String url,
                                   String title,
                                   String rawText,
                                   String complianceNote,
                                   String sourceType,
                                   String sourceQuality,
                                   int statusCode,
                                   String contentType) {
            return new FetchedPage(url, title, rawText, complianceNote, sourceType, sourceQuality, "NONE", statusCode, contentType, contentHash(rawText), Instant.now(), false, true, "FETCHED");
        }

        static FetchedPage blocked(String url, String complianceNote) {
            return new FetchedPage(url, url, "", complianceNote, "article", "UNUSABLE", "ROBOTS_BLOCKED", 0, "", contentHash(""), Instant.now(), false, false, "BLOCKED_BY_ROBOTS");
        }

        static FetchedPage failed(String url, String complianceNote) {
            return failed(url, complianceNote, "UNKNOWN");
        }

        static FetchedPage failed(String url, String complianceNote, String failureReason) {
            return new FetchedPage(url, url, "", complianceNote, "article", "UNUSABLE", failureReason, 0, "", contentHash(""), Instant.now(), false, false, "FETCH_FAILED");
        }

        static FetchedPage unusable(String url,
                                    String title,
                                    String rawText,
                                    String complianceNote,
                                    String sourceType,
                                    String sourceQuality,
                                    String failureReason,
                                    int statusCode,
                                    String contentType) {
            return new FetchedPage(url, title, rawText, complianceNote, sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash(rawText), Instant.now(), false, false, "UNUSABLE_CONTENT");
        }

        boolean isCacheable() {
            return "FETCHED".equals(status);
        }

        FetchedPage fetchedCopy() {
            return new FetchedPage(url, title, rawText, stripCacheHit(complianceNote), sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash, fetchedAt, false, usable, status);
        }

        static FetchedPage restored(String url,
                                    String title,
                                    String rawText,
                                    String complianceNote,
                                    String sourceType,
                                    String sourceQuality,
                                    String failureReason,
                                    int statusCode,
                                    String contentType,
                                    String contentHash,
                                    Instant fetchedAt,
                                    boolean usable,
                                    String status) {
            return new FetchedPage(
                    url,
                    title,
                    rawText == null ? "" : rawText,
                    stripCacheHit(complianceNote),
                    sourceType == null || sourceType.isBlank() ? "article" : sourceType,
                    sourceQuality == null || sourceQuality.isBlank() ? "UNKNOWN" : sourceQuality,
                    failureReason == null || failureReason.isBlank() ? "NONE" : failureReason,
                    statusCode,
                    contentType == null ? "" : contentType,
                    contentHash == null || contentHash.isBlank() ? contentHash(rawText) : contentHash,
                    fetchedAt == null ? Instant.now() : fetchedAt,
                    false,
                    usable,
                    status == null || status.isBlank() ? "FETCHED" : status
            );
        }

        FetchedPage cachedCopy(Instant cachedAt) {
            String note = stripCacheHit(complianceNote) + " cacheHit=true; cachedAt=" + cachedAt + "; contentHash=" + contentHash + ".";
            return new FetchedPage(url, title, rawText, note.trim(), sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash, fetchedAt, true, usable, status);
        }

        private static String stripCacheHit(String note) {
            if (note == null || note.isBlank()) {
                return "";
            }
            return note
                    .replaceAll("\\s*cacheHit=true; cachedAt=[^;]+; contentHash=[0-9a-f]+\\.", "")
                    .trim();
        }
    }
}
