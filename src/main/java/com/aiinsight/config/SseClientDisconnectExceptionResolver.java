package com.aiinsight.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

@Component
public class SseClientDisconnectExceptionResolver implements HandlerExceptionResolver, Ordered {

    @Override
    public ModelAndView resolveException(HttpServletRequest request,
                                         HttpServletResponse response,
                                         Object handler,
                                         Exception ex) {
        if (isRunEventsRequest(request) && isClientDisconnect(ex)) {
            return new ModelAndView();
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean isRunEventsRequest(HttpServletRequest request) {
        String uri = request == null ? "" : request.getRequestURI();
        return uri.startsWith("/api/analysis-runs/") && uri.endsWith("/events");
    }

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
