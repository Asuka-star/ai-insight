package com.aiinsight.service;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

class HostRateLimiter {

    private final Duration minimumInterval;
    private final ConcurrentMap<String, AtomicLong> nextAllowedAtMillis = new ConcurrentHashMap<>();

    HostRateLimiter(Duration minimumInterval) {
        this.minimumInterval = minimumInterval == null ? Duration.ZERO : minimumInterval;
    }

    void acquire(URI uri) throws InterruptedException {
        if (minimumInterval.isZero() || minimumInterval.isNegative()) {
            return;
        }
        String key = originKey(uri);
        AtomicLong nextAllowed = nextAllowedAtMillis.computeIfAbsent(key, ignored -> new AtomicLong(0));
        long intervalMillis = minimumInterval.toMillis();
        while (true) {
            long now = System.currentTimeMillis();
            long current = nextAllowed.get();
            long startAt = Math.max(now, current);
            long updated = startAt + intervalMillis;
            if (nextAllowed.compareAndSet(current, updated)) {
                long waitMillis = startAt - now;
                if (waitMillis > 0) {
                    Thread.sleep(waitMillis);
                }
                return;
            }
        }
    }

    private String originKey(URI uri) {
        int port = uri.getPort();
        String portPart = port < 0 ? "" : ":" + port;
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + portPart;
    }
}
