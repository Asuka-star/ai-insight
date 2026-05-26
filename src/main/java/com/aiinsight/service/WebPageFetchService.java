package com.aiinsight.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class WebPageFetchService {

    private static final String USER_AGENT = "AI-Insight-ResearchBot/0.1";
    private static final int MAX_TEXT_LENGTH = 12_000;
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final RestClient restClient;

    public WebPageFetchService() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    public FetchedPage fetch(String url) {
        URI uri = URI.create(url);
        validateHttpUri(uri);
        // The MVP uses a small robots check before fetching user-provided public URLs.
        // A blocked or failed page returns an unusable FetchedPage so the workflow can degrade gracefully.
        RobotsDecision robotsDecision = robotsDecision(uri);
        if (!robotsDecision.allowed()) {
            log.warn("Web page fetch blocked by robots: url={}, note={}", url, robotsDecision.note());
            return FetchedPage.blocked(url, robotsDecision.note());
        }
        try {
            String html = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
            String title = extractTitle(html, uri);
            String text = extractText(html);
            log.info("Web page fetch completed: url={}, title={}, rawTextChars={}, note={}",
                    url,
                    title,
                    text.length(),
                    robotsDecision.note());
            return FetchedPage.success(url, title, truncate(text), robotsDecision.note());
        } catch (RuntimeException ex) {
            log.warn("Web page fetch failed: url={}, exceptionType={}, message={}, note={}",
                    url,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    robotsDecision.note());
            return FetchedPage.failed(url, "页面抓取失败：" + ex.getMessage() + "；" + robotsDecision.note());
        }
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

    private RobotsDecision robotsDecision(URI uri) {
        URI robotsUri = URI.create(uri.getScheme() + "://" + uri.getHost() + "/robots.txt");
        try {
            String robots = restClient.get()
                    .uri(robotsUri)
                    .retrieve()
                    .body(String.class);
            boolean allowed = isAllowedByRobots(robots, uri.getPath());
            String note = allowed
                    ? "robots.txt checked: allowed for public fetch."
                    : "robots.txt checked: disallowed for public fetch.";
            return new RobotsDecision(allowed, note);
        } catch (RuntimeException ex) {
            return new RobotsDecision(true, "robots.txt unavailable, treated as publicly fetchable for MVP evidence collection.");
        }
    }

    private boolean isAllowedByRobots(String robots, String path) {
        if (robots == null || robots.isBlank()) {
            return true;
        }
        boolean appliesToBot = false;
        for (String rawLine : robots.lines().toList()) {
            String line = rawLine.split("#", 2)[0].trim();
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                String agent = line.substring("user-agent:".length()).trim();
                appliesToBot = "*".equals(agent) || USER_AGENT.toLowerCase(Locale.ROOT).contains(agent.toLowerCase(Locale.ROOT));
                continue;
            }
            if (appliesToBot && lower.startsWith("disallow:")) {
                String disallow = line.substring("disallow:".length()).trim();
                if (!disallow.isBlank() && path.startsWith(disallow)) {
                    return false;
                }
            }
        }
        return true;
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
        String withoutScripts = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        return normalizeText(withoutScripts.replaceAll("(?s)<[^>]+>", " "));
    }

    private String normalizeText(String text) {
        return HtmlUtils.htmlUnescape(text).replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH);
    }

    private static class RobotsDecision {

        private final boolean allowed;
        private final String note;

        RobotsDecision(boolean allowed, String note) {
            this.allowed = allowed;
            this.note = note;
        }

        boolean allowed() {
            return allowed;
        }

        String note() {
            return note;
        }
    }

    @Getter
    public static class FetchedPage {

        private final String url;
        private final String title;
        private final String rawText;
        private final String complianceNote;
        private final boolean usable;
        private final String status;

        private FetchedPage(String url, String title, String rawText, String complianceNote, boolean usable, String status) {
            this.url = url;
            this.title = title;
            this.rawText = rawText;
            this.complianceNote = complianceNote;
            this.usable = usable;
            this.status = status;
        }

        static FetchedPage success(String url, String title, String rawText, String complianceNote) {
            return new FetchedPage(url, title, rawText, complianceNote, true, "FETCHED");
        }

        static FetchedPage blocked(String url, String complianceNote) {
            return new FetchedPage(url, url, "", complianceNote, false, "BLOCKED_BY_ROBOTS");
        }

        static FetchedPage failed(String url, String complianceNote) {
            return new FetchedPage(url, url, "", complianceNote, false, "FETCH_FAILED");
        }
    }
}
