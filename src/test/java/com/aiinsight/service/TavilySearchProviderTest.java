package com.aiinsight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchProviderTest {

    @Test
    void searchRetriesTransientServerFailureOnce() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalSearchServer server = LocalSearchServer.start(requests)) {
            TavilySearchProperties properties = new TavilySearchProperties();
            properties.setApiKey("test-key");
            properties.setBaseUrl(server.url());
            properties.setTimeout(Duration.ofSeconds(2));
            TavilySearchProvider provider = new TavilySearchProvider(properties, new ObjectMapper());

            var results = provider.search("Cursor pricing", 3);

            assertThat(requests).hasValue(2);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getUrl()).isEqualTo("https://example.test/cursor");
            assertThat(results.get(0).getRank()).isEqualTo(1);
        }
    }

    private static final class LocalSearchServer implements AutoCloseable {
        private final HttpServer server;

        private LocalSearchServer(HttpServer server) {
            this.server = server;
        }

        static LocalSearchServer start(AtomicInteger requests) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/search", exchange -> {
                int requestNumber = requests.incrementAndGet();
                byte[] body;
                int status;
                if (requestNumber == 1) {
                    status = 500;
                    body = "temporary failure".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    body = """
                            {
                              "results": [
                                {
                                  "title": "Cursor",
                                  "url": "https://example.test/cursor",
                                  "content": "Cursor product page"
                                }
                              ]
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            return new LocalSearchServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
