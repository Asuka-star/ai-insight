package com.aiinsight.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WebPageFetchServiceTest {

    @Test
    void fetchesAntiBotChallengePagesAsHttpContent() throws IOException {
        HttpServer server = serverWithPage("""
                <html>
                  <head><title>Just a moment...</title></head>
                  <body>Enable JavaScript and cookies to continue. Cloudflare Ray ID: abc.</body>
                </html>
                """);
        try {
            var page = new WebPageFetchService().fetch(url(server, "/page"));

            assertThat(page.isUsable()).isTrue();
            assertThat(page.getStatus()).isEqualTo("FETCHED");
            assertThat(page.getTitle()).isEqualTo("Just a moment...");
            assertThat(page.getRawText()).contains("Enable JavaScript and cookies");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchesVeryShortPagesAsHttpContent() throws IOException {
        HttpServer server = serverWithPage("""
                <html>
                  <head><title>301 Moved Permanently</title></head>
                  <body>Moved.</body>
                </html>
                """);
        try {
            var page = new WebPageFetchService().fetch(url(server, "/page"));

            assertThat(page.isUsable()).isTrue();
            assertThat(page.getStatus()).isEqualTo("FETCHED");
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
                      <body>Final page with product evidence, pricing, integrations, and enterprise security details.</body>
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
            assertThat(page.getComplianceNote()).contains("Redirect followed from");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer serverWithPage(String html) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
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
}
