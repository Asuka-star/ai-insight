package com.aiinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiInsightApplication.class, args);
    }
}
