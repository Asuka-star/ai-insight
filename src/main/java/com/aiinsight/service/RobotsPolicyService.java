package com.aiinsight.service;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

class RobotsPolicyService {

    private static final Duration DEFAULT_AVAILABLE_TTL = Duration.ofHours(12);
    private static final Duration DEFAULT_UNAVAILABLE_TTL = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final String userAgent;
    private final Duration availableTtl;
    private final Duration unavailableTtl;
    private final Supplier<Instant> clock;
    private final ConcurrentMap<String, CachedRobotsRules> cache = new ConcurrentHashMap<>();

    RobotsPolicyService(RestClient restClient, String userAgent) {
        this(restClient, userAgent, DEFAULT_AVAILABLE_TTL, DEFAULT_UNAVAILABLE_TTL, Instant::now);
    }

    RobotsPolicyService(RestClient restClient,
                        String userAgent,
                        Duration availableTtl,
                        Duration unavailableTtl,
                        Supplier<Instant> clock) {
        this.restClient = restClient;
        this.userAgent = userAgent;
        this.availableTtl = availableTtl == null ? DEFAULT_AVAILABLE_TTL : availableTtl;
        this.unavailableTtl = unavailableTtl == null ? DEFAULT_UNAVAILABLE_TTL : unavailableTtl;
        this.clock = clock == null ? Instant::now : clock;
    }

    RobotsDecision decide(URI uri) {
        String origin = origin(uri);
        RobotsRules rules = cachedRules(origin);
        boolean allowed = rules.allowed(uri.getPath(), userAgent);
        String note = rules.available()
                ? "robots.txt checked: " + (allowed ? "allowed" : "disallowed") + " for public fetch; robotsCache=hit."
                : "robots.txt unavailable, treated as publicly fetchable for MVP evidence collection; robotsCache=hit.";
        return new RobotsDecision(allowed, note);
    }

    int cachedOriginCount() {
        return cache.size();
    }

    private RobotsRules cachedRules(String origin) {
        Instant now = clock.get();
        CachedRobotsRules cached = cache.get(origin);
        if (cached != null && !cached.expired(now)) {
            return cached.rules();
        }
        RobotsRules refreshedRules = fetchRules(origin);
        Duration ttl = refreshedRules.available() ? availableTtl : unavailableTtl;
        CachedRobotsRules refreshed = new CachedRobotsRules(refreshedRules, now.plus(ttl));
        cache.put(origin, refreshed);
        return refreshed.rules();
    }

    private RobotsRules fetchRules(String origin) {
        try {
            String robots = restClient.get()
                    .uri(URI.create(origin + "/robots.txt"))
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .retrieve()
                    .body(String.class);
            return RobotsRules.available(parseGroups(robots));
        } catch (RuntimeException ex) {
            return RobotsRules.unavailable();
        }
    }

    private List<RobotsGroup> parseGroups(String robots) {
        List<RobotsGroup> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<RobotsRule> rules = new ArrayList<>();
        boolean sawRule = false;
        for (String rawLine : robots == null ? List.<String>of() : robots.lines().toList()) {
            String line = rawLine.split("#", 2)[0].trim();
            if (line.isBlank()) {
                if (!agents.isEmpty() || !rules.isEmpty()) {
                    groups.add(new RobotsGroup(agents, rules));
                }
                agents = new ArrayList<>();
                rules = new ArrayList<>();
                sawRule = false;
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                if (sawRule && !agents.isEmpty()) {
                    groups.add(new RobotsGroup(agents, rules));
                    agents = new ArrayList<>();
                    rules = new ArrayList<>();
                    sawRule = false;
                }
                agents.add(line.substring("user-agent:".length()).trim().toLowerCase(Locale.ROOT));
                continue;
            }
            if (lower.startsWith("allow:")) {
                sawRule = true;
                rules.add(new RobotsRule(true, line.substring("allow:".length()).trim()));
                continue;
            }
            if (lower.startsWith("disallow:")) {
                sawRule = true;
                rules.add(new RobotsRule(false, line.substring("disallow:".length()).trim()));
            }
        }
        if (!agents.isEmpty() || !rules.isEmpty()) {
            groups.add(new RobotsGroup(agents, rules));
        }
        return groups;
    }

    private String origin(URI uri) {
        int port = uri.getPort();
        String portPart = port < 0 ? "" : ":" + port;
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + portPart;
    }

    record RobotsDecision(boolean allowed, String note) {
    }

    private record CachedRobotsRules(RobotsRules rules, Instant expiresAt) {

        boolean expired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    private record RobotsRules(boolean available, List<RobotsGroup> groups) {

        static RobotsRules available(List<RobotsGroup> groups) {
            return new RobotsRules(true, groups);
        }

        static RobotsRules unavailable() {
            return new RobotsRules(false, List.of());
        }

        boolean allowed(String path, String userAgent) {
            if (!available || groups.isEmpty()) {
                return true;
            }
            String normalizedPath = path == null || path.isBlank() ? "/" : path;
            String normalizedAgent = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
            RobotsGroup selected = groups.stream()
                    .filter(group -> group.matches(normalizedAgent))
                    .max((left, right) -> Integer.compare(left.matchSpecificity(normalizedAgent), right.matchSpecificity(normalizedAgent)))
                    .orElse(null);
            if (selected == null) {
                return true;
            }
            return selected.allowed(normalizedPath);
        }
    }

    private record RobotsGroup(List<String> agents, List<RobotsRule> rules) {

        boolean matches(String userAgent) {
            return agents.stream().anyMatch(agent -> "*".equals(agent) || userAgent.contains(agent));
        }

        int matchSpecificity(String userAgent) {
            return agents.stream()
                    .filter(agent -> "*".equals(agent) || userAgent.contains(agent))
                    .mapToInt(agent -> "*".equals(agent) ? 0 : agent.length())
                    .max()
                    .orElse(-1);
        }

        boolean allowed(String path) {
            RobotsRule selected = rules.stream()
                    .filter(rule -> rule.matches(path))
                    .max((left, right) -> {
                        int lengthComparison = Integer.compare(left.path().length(), right.path().length());
                        if (lengthComparison != 0) {
                            return lengthComparison;
                        }
                        return Boolean.compare(left.allow(), right.allow());
                    })
                    .orElse(null);
            return selected == null || selected.allow();
        }
    }

    private record RobotsRule(boolean allow, String path) {

        boolean matches(String requestPath) {
            return !path.isBlank() && requestPath.startsWith(path);
        }
    }
}
