package com.aiinsight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDotenvValuesIntoSpringEnvironment() throws Exception {
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, """
                TAVILY_API_KEY='test-tavily-key'
                XIAOMI_LLM_MODEL=mimo-v2.5-pro # local model
                """);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getSystemProperties().put("ai-insight.dotenv.path", dotenv.toString());

        new DotenvEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("TAVILY_API_KEY")).isEqualTo("test-tavily-key");
        assertThat(environment.getProperty("XIAOMI_LLM_MODEL")).isEqualTo("mimo-v2.5-pro");
    }
}
