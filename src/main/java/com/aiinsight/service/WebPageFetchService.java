package com.aiinsight.service;

import com.aiinsight.config.HttpClientFactory;
import com.aiinsight.config.HttpProxyProperties;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

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
    private final WebPageRenderService webPageRenderService;
    private final int maxFetchAttempts;

    public WebPageFetchService() {
        this(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    public WebPageFetchService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(
                CONNECT_TIMEOUT,
                READ_TIMEOUT,
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                HOST_MINIMUM_INTERVAL,
                FETCH_CACHE_TTL,
                MAX_FETCH_ATTEMPTS,
                null,
                cacheFrom(jdbcTemplateProvider)
        );
    }

    @Autowired
    public WebPageFetchService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                               HttpProxyProperties proxyProperties,
                               WebPageRenderService webPageRenderService) {
        this(
                CONNECT_TIMEOUT,
                READ_TIMEOUT,
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                HOST_MINIMUM_INTERVAL,
                FETCH_CACHE_TTL,
                MAX_FETCH_ATTEMPTS,
                proxyProperties,
                null,
                null,
                cacheFrom(jdbcTemplateProvider),
                webPageRenderService
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
                null,
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
                        HttpProxyProperties proxyProperties,
                        FetchedPageCache fetchedPageCache) {
        this(
                connectTimeout,
                readTimeout,
                sourceTypeClassifier,
                pageQualityEvaluator,
                hostMinimumInterval,
                fetchCacheTtl,
                maxFetchAttempts,
                proxyProperties,
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
        this(
                connectTimeout,
                readTimeout,
                sourceTypeClassifier,
                pageQualityEvaluator,
                hostMinimumInterval,
                fetchCacheTtl,
                maxFetchAttempts,
                null,
                robotsAvailableTtl,
                robotsUnavailableTtl,
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
                        HttpProxyProperties proxyProperties,
                        Duration robotsAvailableTtl,
                        Duration robotsUnavailableTtl,
                        FetchedPageCache fetchedPageCache) {
        this(
                connectTimeout,
                readTimeout,
                sourceTypeClassifier,
                pageQualityEvaluator,
                hostMinimumInterval,
                fetchCacheTtl,
                maxFetchAttempts,
                proxyProperties,
                robotsAvailableTtl,
                robotsUnavailableTtl,
                fetchedPageCache,
                WebPageRenderService.disabled()
        );
    }

    WebPageFetchService(Duration connectTimeout,
                        Duration readTimeout,
                        SourceTypeClassifier sourceTypeClassifier,
                        PageQualityEvaluator pageQualityEvaluator,
                        Duration hostMinimumInterval,
                        Duration fetchCacheTtl,
                        int maxFetchAttempts,
                        HttpProxyProperties proxyProperties,
                        Duration robotsAvailableTtl,
                        Duration robotsUnavailableTtl,
                        FetchedPageCache fetchedPageCache,
                        WebPageRenderService webPageRenderService) {
        HttpClient httpClient = HttpClientFactory.builder(connectTimeout, proxyProperties)
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
        this.webPageRenderService = webPageRenderService == null ? WebPageRenderService.disabled() : webPageRenderService;
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
            List<String> internalLinks = extractInternalLinks(html, finalUri);
            TextExtractionResult extraction = extractText(html, title);
            String text = extraction.text();
            String truncatedText = truncate(text);
            String textHash = contentHash(truncatedText);
            String sourceType = sourceTypeClassifier.classify(finalUrl, title);
            PageQualityEvaluator.PageQualityResult quality = pageQualityEvaluator.evaluate(
                    title,
                    text,
                    response.statusCode(),
                    contentType
            );
            String failureReason = extraction.metadataFallback() && quality.usable()
                    ? "METADATA_ONLY"
                    : quality.failureReason();
            String sourceQuality = quality.usable()
                    ? (extraction.metadataFallback()
                    ? "LOW"
                    : sourceTypeClassifier.qualityFor(sourceType, "FETCHED", "LIVE_FETCHED"))
                    : quality.sourceQuality();
            String renderNote = "";
            // 只有静态抓取为空/过薄时才走浏览器渲染兜底，控制 Playwright 成本和单次分析耗时。
            if (shouldAttemptRender(quality, extraction)) {
                WebPageRenderService.RenderResult renderResult = webPageRenderService.render(finalUrl);
                if (renderResult.success() && StringUtils.hasText(renderResult.html())) {
                    String renderedFinalUrl = StringUtils.hasText(renderResult.finalUrl()) ? renderResult.finalUrl() : finalUrl;
                    String renderedTitle = StringUtils.hasText(renderResult.title())
                            ? normalizeText(renderResult.title())
                            : extractTitle(renderResult.html(), URI.create(renderedFinalUrl));
                    TextExtractionResult renderedExtraction = extractText(renderResult.html(), renderedTitle);
                    String renderedText = renderedExtraction.text();
                    PageQualityEvaluator.PageQualityResult renderedQuality = pageQualityEvaluator.evaluate(
                            renderedTitle,
                            renderedText,
                            response.statusCode(),
                            contentType
                    );
                    if (renderImprovesPage(quality, extraction, renderedQuality, renderedExtraction)) {
                        finalUrl = renderedFinalUrl;
                        internalLinks = extractInternalLinks(renderResult.html(), URI.create(renderedFinalUrl));
                        title = renderedTitle;
                        extraction = renderedExtraction;
                        text = renderedText;
                        truncatedText = truncate(text);
                        textHash = contentHash(truncatedText);
                        sourceType = sourceTypeClassifier.classify(finalUrl, title);
                        quality = renderedQuality;
                        failureReason = extraction.metadataFallback() && quality.usable()
                                ? "METADATA_ONLY"
                                : quality.failureReason();
                        sourceQuality = quality.usable()
                                ? (extraction.metadataFallback()
                                ? "LOW"
                                : sourceTypeClassifier.qualityFor(sourceType, "FETCHED", "LIVE_FETCHED"))
                                : quality.sourceQuality();
                        renderNote = "renderFallback=used; renderNote=" + sanitizeNote(renderResult.note()) + ";";
                    } else {
                        renderNote = "renderFallback=attempted_unusable; renderFailureReason=%s; renderExtractionMode=%s; renderNote=%s;"
                                .formatted(renderedQuality.failureReason(), renderedExtraction.mode(), sanitizeNote(renderResult.note()));
                    }
                } else if (renderResult.attempted()) {
                    renderNote = "renderFallback=failed; renderFailureReason=%s; renderNote=%s;"
                            .formatted(renderResult.failureReason(), sanitizeNote(renderResult.note()));
                }
            }
            String complianceNote = appendFetchMetadata(
                    appendNote(redirectNote(url, finalUrl, robotsDecision.note()), renderNote),
                    response.statusCode(),
                    contentType,
                    sourceType,
                    sourceQuality,
                    failureReason,
                    fetchResult.retryCount(),
                    extraction.mode(),
                    textHash
            );
            log.info("Web page fetch completed: url={}, title={}, statusCode={}, sourceType={}, sourceQuality={}, failureReason={}, retryCount={}, rawTextChars={}, note={}",
                    finalUrl,
                    title,
                    response.statusCode(),
                    sourceType,
                    sourceQuality,
                    failureReason,
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
                        failureReason,
                        response.statusCode(),
                        contentType,
                        internalLinks
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
                    failureReason,
                    response.statusCode(),
                    contentType,
                    internalLinks
            );
            fetchedPageCache.put(uri, page);
            return page;
        } catch (HttpTimeoutException ex) {
            String failureReason = "TIMEOUT";
            log.warn("Web page fetch timeout: url={}, failureReason={}, exceptionType={}, message={}, note={}",
                    url,
                    failureReason,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取超时：" + ex.getMessage() + "；" + robotsDecision.note(), failureReason);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            String failureReason = "TIMEOUT";
            log.warn("Web page fetch interrupted: url={}, failureReason={}, exceptionType={}, message={}, note={}",
                    url,
                    failureReason,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取被中断：" + ex.getMessage() + "；" + robotsDecision.note(), failureReason);
        } catch (RuntimeException ex) {
            String failureReason = classifyFetchFailure(ex);
            log.warn("Web page fetch failed: url={}, failureReason={}, exceptionType={}, message={}, note={}",
                    url,
                    failureReason,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage() + "；" + robotsDecision.note(), failureReason);
        } catch (Exception ex) {
            String failureReason = classifyFetchFailure(ex);
            log.warn("Web page fetch failed: url={}, failureReason={}, exceptionType={}, message={}, note={}",
                    url,
                    failureReason,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage() + "；" + robotsDecision.note(), failureReason);
        }
    }

    private String classifyFetchFailure(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof SSLHandshakeException || cursor instanceof SSLException) {
                return "TLS_FAILED";
            }
            if (cursor instanceof UnknownHostException) {
                return "DNS_FAILED";
            }
            if (cursor instanceof ConnectException) {
                return "CONNECT_FAILED";
            }
            if (cursor instanceof HttpTimeoutException) {
                return "TIMEOUT";
            }
            cursor = cursor.getCause();
        }
        return "UNKNOWN";
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

    private boolean shouldAttemptRender(PageQualityEvaluator.PageQualityResult quality, TextExtractionResult extraction) {
        if (extraction.metadataFallback()) {
            return true;
        }
        if (quality.usable()) {
            return false;
        }
        return "EMPTY_TEXT".equals(quality.failureReason()) || "THIN_TEXT".equals(quality.failureReason());
    }

    private boolean renderImprovesPage(PageQualityEvaluator.PageQualityResult staticQuality,
                                       TextExtractionResult staticExtraction,
                                       PageQualityEvaluator.PageQualityResult renderedQuality,
                                       TextExtractionResult renderedExtraction) {
        if (!renderedQuality.usable()) {
            return false;
        }
        return !renderedExtraction.metadataFallback() || !staticQuality.usable() || staticExtraction.metadataFallback();
    }

    private String appendNote(String note, String addition) {
        if (!StringUtils.hasText(addition)) {
            return note;
        }
        return (note == null ? "" : note.trim() + " ") + addition.trim();
    }

    private String sanitizeNote(String note) {
        return note == null ? "" : note.replaceAll("[\\r\\n]+", " ").replace(';', ',').trim();
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

    private List<String> extractInternalLinks(String html, URI pageUri) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        String pageHost = normalizeHost(pageUri.getHost());
        LinkedHashSet<String> links = new LinkedHashSet<>();
        Document document = Jsoup.parse(html, pageUri.toString());
        for (Element link : document.select("a[href]")) {
            String absoluteUrl = normalizeNavigationUrl(link.absUrl("href"));
            if (!StringUtils.hasText(absoluteUrl)) {
                continue;
            }
            try {
                URI linkUri = URI.create(absoluteUrl);
                if (normalizeHost(linkUri.getHost()).equals(pageHost)) {
                    links.add(absoluteUrl);
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed navigation links.
            }
        }
        return new ArrayList<>(links);
    }

    private String normalizeNavigationUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!StringUtils.hasText(scheme) || !StringUtils.hasText(host)) {
                return "";
            }
            String path = StringUtils.hasText(uri.getPath()) ? uri.getPath().replaceFirst("/+$", "") : "";
            if (!StringUtils.hasText(path)) {
                path = "/";
            }
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    uri.getUserInfo(),
                    host.toLowerCase(Locale.ROOT),
                    uri.getPort(),
                    path,
                    null,
                    null
            ).toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeHost(String host) {
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    private TextExtractionResult extractText(String html, String title) {
        if (html == null || html.isBlank()) {
            return new TextExtractionResult("", false, "empty_html");
        }
        Document document = Jsoup.parse(html);
        document.select("script,style,noscript,svg,canvas,form,iframe,nav,header,footer,aside").remove();
        String mainText = selectMainContent(document)
                .map(Element::text)
                .map(this::normalizeText)
                .orElse("");
        if (!mainText.isBlank()) {
            return new TextExtractionResult(mainText, false, "main_content");
        }
        String bodyText = normalizeText(document.body() == null ? document.text() : document.body().text());
        if (!bodyText.isBlank()) {
            return new TextExtractionResult(bodyText, false, "body_text");
        }
        String metadataText = metadataText(document, title);
        if (!metadataText.isBlank()) {
            return new TextExtractionResult(metadataText, true, "metadata_fallback");
        }
        return new TextExtractionResult("", false, "empty_text");
    }

    private Optional<Element> selectMainContent(Document document) {
        return document.select("main, article, [role=main], #content, .content, #main, .main")
                .stream()
                .max(Comparator.comparingInt(element -> element.text().length()));
    }

    private String normalizeText(String text) {
        return storageSafeText(HtmlUtils.htmlUnescape(text)).replaceAll("\\s+", " ").trim();
    }

    private String metadataText(Document document, String title) {
        Set<String> values = new LinkedHashSet<>();
        addMetadata(values, title);
        for (String selector : List.of(
                "meta[name=description]",
                "meta[property=og:title]",
                "meta[property=og:description]",
                "meta[name=twitter:title]",
                "meta[name=twitter:description]",
                "meta[itemprop=name]",
                "meta[itemprop=description]"
        )) {
            for (Element element : document.select(selector)) {
                addMetadata(values, element.attr("content"));
            }
        }
        List<String> cleaned = new ArrayList<>(values);
        return normalizeText(String.join(". ", cleaned));
    }

    private void addMetadata(Set<String> values, String value) {
        String normalized = normalizeText(value == null ? "" : value);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
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
                                       String extractionMode,
                                       String contentHash) {
        return "%s statusCode=%d; contentType=%s; sourceType=%s; sourceQuality=%s; failureReason=%s; retryCount=%d; extractionMode=%s; contentHash=%s."
                .formatted(
                        note == null ? "" : note.trim(),
                        statusCode,
                        contentType == null || contentType.isBlank() ? "unknown" : contentType,
                        sourceType,
                        sourceQuality,
                        failureReason,
                        retryCount,
                        extractionMode,
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

    static String storageSafeText(String value) {
        return value == null ? "" : value.replace("\u0000", "");
    }

    private record FetchAttemptResult(HttpResponse<String> response, int retryCount) {
    }

    private record TextExtractionResult(String text, boolean metadataFallback, String mode) {
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
        private final List<String> internalLinks;
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
                            List<String> internalLinks,
                             Instant fetchedAt,
                             boolean cacheHit,
                             boolean usable,
                             String status) {
            String safeRawText = storageSafeText(rawText);
            this.url = storageSafeText(url);
            this.title = storageSafeText(title);
            this.rawText = safeRawText;
            this.complianceNote = storageSafeText(complianceNote);
            this.sourceType = storageSafeText(sourceType);
            this.sourceQuality = storageSafeText(sourceQuality);
            this.failureReason = storageSafeText(failureReason);
            this.statusCode = statusCode;
            this.contentType = storageSafeText(contentType);
            this.contentHash = shouldRehash(rawText, safeRawText, contentHash)
                    ? contentHash(safeRawText)
                    : storageSafeText(contentHash);
            this.internalLinks = internalLinks == null ? List.of() : List.copyOf(internalLinks);
            this.fetchedAt = fetchedAt;
            this.cacheHit = cacheHit;
            this.usable = usable;
            this.status = storageSafeText(status);
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
            return success(url, title, rawText, complianceNote, sourceType, sourceQuality, "NONE", statusCode, contentType);
        }

        static FetchedPage success(String url,
                                   String title,
                                   String rawText,
                                   String complianceNote,
                                   String sourceType,
                                   String sourceQuality,
                                   String failureReason,
                                   int statusCode,
                                   String contentType) {
            return success(url, title, rawText, complianceNote, sourceType, sourceQuality, failureReason, statusCode, contentType, List.of());
        }

        static FetchedPage success(String url,
                                   String title,
                                   String rawText,
                                   String complianceNote,
                                   String sourceType,
                                   String sourceQuality,
                                   String failureReason,
                                   int statusCode,
                                   String contentType,
                                   List<String> internalLinks) {
            return new FetchedPage(url, title, rawText, complianceNote, sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash(rawText), internalLinks, Instant.now(), false, true, "FETCHED");
        }

        static FetchedPage blocked(String url, String complianceNote) {
            return new FetchedPage(url, url, "", complianceNote, "article", "UNUSABLE", "ROBOTS_BLOCKED", 0, "", contentHash(""), List.of(), Instant.now(), false, false, "BLOCKED_BY_ROBOTS");
        }

        static FetchedPage failed(String url, String complianceNote) {
            return failed(url, complianceNote, "UNKNOWN");
        }

        static FetchedPage failed(String url, String complianceNote, String failureReason) {
            return new FetchedPage(url, url, "", complianceNote, "article", "UNUSABLE", failureReason, 0, "", contentHash(""), List.of(), Instant.now(), false, false, "FETCH_FAILED");
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
            return unusable(url, title, rawText, complianceNote, sourceType, sourceQuality, failureReason, statusCode, contentType, List.of());
        }

        static FetchedPage unusable(String url,
                                    String title,
                                    String rawText,
                                    String complianceNote,
                                    String sourceType,
                                    String sourceQuality,
                                    String failureReason,
                                    int statusCode,
                                    String contentType,
                                    List<String> internalLinks) {
            return new FetchedPage(url, title, rawText, complianceNote, sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash(rawText), internalLinks, Instant.now(), false, false, "UNUSABLE_CONTENT");
        }

        boolean isCacheable() {
            return "FETCHED".equals(status);
        }

        FetchedPage fetchedCopy() {
            return new FetchedPage(url, title, rawText, stripCacheHit(complianceNote), sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash, internalLinks, fetchedAt, false, usable, status);
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
                    List.of(),
                    fetchedAt == null ? Instant.now() : fetchedAt,
                    false,
                    usable,
                    status == null || status.isBlank() ? "FETCHED" : status
            );
        }

        FetchedPage cachedCopy(Instant cachedAt) {
            String note = stripCacheHit(complianceNote) + " cacheHit=true; cachedAt=" + cachedAt + "; contentHash=" + contentHash + ".";
            return new FetchedPage(url, title, rawText, note.trim(), sourceType, sourceQuality, failureReason, statusCode, contentType, contentHash, internalLinks, fetchedAt, true, usable, status);
        }

        private static String stripCacheHit(String note) {
            if (note == null || note.isBlank()) {
                return "";
            }
            return note
                    .replaceAll("\\s*cacheHit=true; cachedAt=[^;]+; contentHash=[0-9a-f]+\\.", "")
                    .trim();
        }

        private static boolean shouldRehash(String originalRawText, String safeRawText, String providedHash) {
            return providedHash == null
                    || providedHash.isBlank()
                    || (originalRawText != null && !safeRawText.equals(originalRawText));
        }
    }
}
