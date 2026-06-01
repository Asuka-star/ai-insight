package com.aiinsight.workflow;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties("ai-insight.workflow")
public class AnalysisWorkflowProperties {

    // 后端默认不自动返工；前端可为单次 run 开启 1-2 轮，避免旧配置让分析时间失控。
    private int maxReviewReworkAttempts = 0;

    int maxReviewReworkAttempts() {
        return Math.max(0, Math.min(maxReviewReworkAttempts, 2));
    }
}
