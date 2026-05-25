package com.aiinsight.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "localDotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("ai-insight.dotenv.enabled", Boolean.class, true)) {
            return;
        }
        Path dotenv = Path.of(environment.getProperty("ai-insight.dotenv.path", ".env"));
        if (!Files.isRegularFile(dotenv)) {
            return;
        }
        Map<String, Object> values = readDotenv(dotenv);
        if (values.isEmpty()) {
            return;
        }
        // 放在 systemEnvironment 后面，确保真实环境变量优先于本地 .env。
        if (environment.getPropertySources().contains("systemEnvironment")) {
            environment.getPropertySources().addAfter("systemEnvironment", new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        } else {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    private Map<String, Object> readDotenv(Path dotenv) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(dotenv)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                String key = trimmed.substring(0, equals).trim();
                String value = stripInlineComment(stripQuotes(trimmed.substring(equals + 1).trim()));
                if (StringUtils.hasText(key)) {
                    values.put(key, value);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read local .env file: " + dotenv, ex);
        }
        return values;
    }

    private String stripInlineComment(String value) {
        if (!value.contains("#")) {
            return value;
        }
        int hash = value.indexOf('#');
        if (hash > 0 && Character.isWhitespace(value.charAt(hash - 1))) {
            return value.substring(0, hash).trim();
        }
        return value;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
