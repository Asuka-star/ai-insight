package com.aiinsight.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebPageFetchServiceTest {

    @Test
    void marksAntiBotChallengePagesAsUnusableContent() throws IOException {
        HttpServer server = serverWithPage("""
                <html>
                  <head><title>Just a moment...</title></head>
                  <body>Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc.</body>
                </html>
                """);
        try {
            var page = new WebPageFetchService().fetch(url(server, "/page"));

            assertThat(page.isUsable()).isFalse();
            assertThat(page.getStatus()).isEqualTo("UNUSABLE_CONTENT");
            assertThat(page.getFailureReason()).isEqualTo("ANTI_BOT_PAGE");
            assertThat(page.getSourceQuality()).isEqualTo("UNUSABLE");
            assertThat(page.getTitle()).isEqualTo("Just a moment...");
            assertThat(page.getRawText()).contains("Enable JavaScript and cookies");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marksVeryShortPagesAsThinContent() throws IOException {
        HttpServer server = serverWithPage("""
                <html>
                  <head><title>301 Moved Permanently</title></head>
                  <body>Moved.</body>
                </html>
                """);
        try {
            var page = new WebPageFetchService().fetch(url(server, "/page"));

            assertThat(page.isUsable()).isFalse();
            assertThat(page.getStatus()).isEqualTo("UNUSABLE_CONTENT");
            assertThat(page.getFailureReason()).isEqualTo("THIN_TEXT");
            assertThat(page.getTitle()).isEqualTo("301 Moved Permanently");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsNormalRedirectsToFinalPage() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/new");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/new", exchange -> {
            byte[] body = """
                    <html>
                      <head><title>Final product page</title></head>
                      <body>
                        <main>
                          Final page with product evidence, pricing, integrations, and enterprise security details.
                          This product documentation explains workflow automation, collaboration controls, permission governance,
                          compliance settings, release history, customer support, integration APIs, onboarding paths, and
                          enterprise adoption signals for a competitive analysis report.
                        </main>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var page = new WebPageFetchService(Duration.ofSeconds(1), Duration.ofSeconds(2)).fetch(url(server, "/old"));

            assertThat(page.isUsable()).isTrue();
            assertThat(page.getStatus()).isEqualTo("FETCHED");
            assertThat(page.getUrl()).isEqualTo(url(server, "/new"));
            assertThat(page.getTitle()).isEqualTo("Final product page");
            assertThat(page.getRawText()).contains("enterprise security");
            assertThat(page.getSourceType()).isEqualTo("article");
            assertThat(page.getSourceQuality()).isEqualTo("MEDIUM");
            assertThat(page.getComplianceNote()).contains("Redirect followed from");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void extractsMainContentWithoutNavigationNoise() throws IOException {
        HttpServer server = serverWithPage("""
                <html>
                  <head><title>Docs page</title></head>
                  <body>
                    <nav>Pricing Blog Login Careers</nav>
                    <main>
                      The product documentation describes AI search, permission governance, integrations,
                      enterprise administration, audit logs, workspace controls, pricing plan boundaries,
                      onboarding guidance, support policies, security controls, and release notes.
                    </main>
                    <footer>Footer legal links and unrelated navigation</footer>
                  </body>
                </html>
                """);
        try {
            var page = new WebPageFetchService().fetch(url(server, "/docs/page"));

            assertThat(page.isUsable()).isTrue();
            assertThat(page.getRawText()).contains("permission governance");
            assertThat(page.getRawText()).doesNotContain("Pricing Blog Login Careers");
            assertThat(page.getRawText()).doesNotContain("Footer legal links");
            assertThat(page.getSourceType()).isEqualTo("docs");
            assertThat(page.getSourceQuality()).isEqualTo("HIGH");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachesRobotsRulesPerOrigin() throws IOException {
        AtomicInteger robotsRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            robotsRequests.incrementAndGet();
            byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            byte[] body = usefulHtml("Cached robots page").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = noDelayFetchService(2);

            var first = service.fetch(url(server, "/one"));
            var second = service.fetch(url(server, "/two"));

            assertThat(first.isUsable()).isTrue();
            assertThat(second.isUsable()).isTrue();
            assertThat(robotsRequests).hasValue(1);
            assertThat(second.getComplianceNote()).contains("robotsCache=hit");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reusesCachedFetchedPageForSameUrl() throws IOException {
        AtomicInteger robotsRequests = new AtomicInteger();
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            robotsRequests.incrementAndGet();
            byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/cached", exchange -> {
            pageRequests.incrementAndGet();
            byte[] body = usefulHtml("Reusable cached page").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = noDelayFetchService(2);

            var first = service.fetch(url(server, "/cached"));
            var second = service.fetch(url(server, "/cached"));

            assertThat(first.isCacheHit()).isFalse();
            assertThat(second.isCacheHit()).isTrue();
            assertThat(first.getContentHash()).isEqualTo(second.getContentHash());
            assertThat(second.getComplianceNote()).contains("cacheHit=true", "contentHash=");
            assertThat(pageRequests).hasValue(1);
            assertThat(robotsRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rechecksRobotsBeforeReturningCachedFetchedPage() throws IOException {
        AtomicBoolean disallow = new AtomicBoolean(false);
        AtomicInteger robotsRequests = new AtomicInteger();
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            robotsRequests.incrementAndGet();
            String robots = disallow.get()
                    ? "User-agent: *\nDisallow: /cached\n"
                    : "User-agent: *\nAllow: /\n";
            byte[] body = robots.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/cached", exchange -> {
            pageRequests.incrementAndGet();
            byte[] body = usefulHtml("Reusable cached page").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = noDelayFetchServiceWithRobotsTtl(Duration.ZERO, Duration.ofMinutes(1));

            var first = service.fetch(url(server, "/cached"));
            disallow.set(true);
            var second = service.fetch(url(server, "/cached"));

            assertThat(first.isUsable()).isTrue();
            assertThat(second.getStatus()).isEqualTo("BLOCKED_BY_ROBOTS");
            assertThat(second.isCacheHit()).isFalse();
            assertThat(pageRequests).hasValue(1);
            assertThat(robotsRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotCacheUnusablePages() throws IOException {
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/flaky", exchange -> {
            int request = pageRequests.incrementAndGet();
            String html = request == 1
                    ? """
                    <html>
                      <head><title>Just a moment...</title></head>
                      <body>Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc.</body>
                    </html>
                    """
                    : usefulHtml("Recovered useful page");
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = noDelayFetchService(1);

            var first = service.fetch(url(server, "/flaky"));
            var second = service.fetch(url(server, "/flaky"));

            assertThat(first.getStatus()).isEqualTo("UNUSABLE_CONTENT");
            assertThat(second.getStatus()).isEqualTo("FETCHED");
            assertThat(second.isUsable()).isTrue();
            assertThat(second.isCacheHit()).isFalse();
            assertThat(pageRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void expiresUnavailableRobotsDecisionQuickly() throws IOException {
        AtomicInteger robotsRequests = new AtomicInteger();
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            int request = robotsRequests.incrementAndGet();
            if (request == 1) {
                byte[] body = "temporary robots failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            byte[] body = "User-agent: *\nDisallow: /private\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/private", exchange -> {
            pageRequests.incrementAndGet();
            byte[] body = usefulHtml("Private page").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = noDelayFetchServiceWithRobotsTtl(Duration.ofHours(1), Duration.ZERO);

            var first = service.fetch(url(server, "/private"));
            var second = service.fetch(url(server, "/private"));

            assertThat(first.getStatus()).isEqualTo("FETCHED");
            assertThat(second.getStatus()).isEqualTo("BLOCKED_BY_ROBOTS");
            assertThat(pageRequests).hasValue(1);
            assertThat(robotsRequests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksDisallowedRobotsPath() throws IOException {
        AtomicInteger robotsRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            robotsRequests.incrementAndGet();
            byte[] body = "User-agent: *\nDisallow: /private\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var page = noDelayFetchService(2).fetch(url(server, "/private/report"));

            assertThat(page.isUsable()).isFalse();
            assertThat(page.getStatus()).isEqualTo("BLOCKED_BY_ROBOTS");
            assertThat(page.getFailureReason()).isEqualTo("ROBOTS_BLOCKED");
            assertThat(robotsRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientServerErrorsBeforeEvaluatingPage() throws IOException {
        AtomicInteger pageRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/unstable", exchange -> {
            int request = pageRequests.incrementAndGet();
            byte[] body = (request == 1 ? "temporary failure" : usefulHtml("Recovered page"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(request == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var page = noDelayFetchService(2).fetch(url(server, "/unstable"));

            assertThat(page.isUsable()).isTrue();
            assertThat(page.getStatus()).isEqualTo("FETCHED");
            assertThat(pageRequests).hasValue(2);
            assertThat(page.getComplianceNote()).contains("retryCount=1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesMinimumIntervalBetweenSameOriginFetches() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", exchange -> {
            byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            byte[] body = usefulHtml("Rate limited page").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            WebPageFetchService service = new WebPageFetchService(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    new SourceTypeClassifier(),
                    new PageQualityEvaluator(),
                    Duration.ofMillis(60),
                    Duration.ZERO,
                    1
            );

            service.fetch(url(server, "/one"));
            long startedAt = System.nanoTime();
            service.fetch(url(server, "/two"));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(elapsedMillis).isGreaterThanOrEqualTo(40);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer serverWithPage(String html) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private WebPageFetchService noDelayFetchService(int maxFetchAttempts) {
        return new WebPageFetchService(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                Duration.ZERO,
                Duration.ofHours(1),
                maxFetchAttempts
        );
    }

    private WebPageFetchService noDelayFetchServiceWithRobotsTtl(Duration availableTtl, Duration unavailableTtl) {
        return new WebPageFetchService(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                new SourceTypeClassifier(),
                new PageQualityEvaluator(),
                Duration.ZERO,
                Duration.ofHours(1),
                1,
                availableTtl,
                unavailableTtl,
                new FetchedPageCache(Duration.ofHours(1))
        );
    }

    private String usefulHtml(String title) {
        return """
                <html>
                  <head><title>%s</title></head>
                  <body>
                    <main>
                      This product evidence page explains pricing, documentation, security controls, audit logs,
                      release notes, customer support, integration APIs, onboarding guidance, collaboration workflows,
                      permission governance, compliance settings, enterprise controls, and adoption signals for
                      competitive analysis with enough useful body text to pass page quality evaluation.
                    </main>
                  </body>
                </html>
                """.formatted(title);
    }
}
