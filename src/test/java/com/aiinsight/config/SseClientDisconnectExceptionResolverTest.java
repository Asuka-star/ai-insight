package com.aiinsight.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class SseClientDisconnectExceptionResolverTest {

    private final SseClientDisconnectExceptionResolver resolver = new SseClientDisconnectExceptionResolver();

    @Test
    void handlesClientDisconnectForRunEventsEndpoint() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/analysis-runs/run-1/events");

        var modelAndView = resolver.resolveException(
                request,
                new MockHttpServletResponse(),
                null,
                new AsyncRequestNotUsableException("client disconnected", new IOException("Connection reset"))
        );

        assertThat(modelAndView).isNotNull();
    }

    @Test
    void leavesNonSseIOExceptionForDefaultHandling() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/analysis-runs/run-1");

        var modelAndView = resolver.resolveException(
                request,
                new MockHttpServletResponse(),
                null,
                new IOException("unexpected write failure")
        );

        assertThat(modelAndView).isNull();
    }
}
