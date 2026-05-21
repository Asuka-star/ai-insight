package com.aiinsight.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean
    AsyncTaskExecutor analysisTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newCachedThreadPool());
    }
}
