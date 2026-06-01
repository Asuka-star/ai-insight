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

    // 默认允许一轮自动返工，让 Reviewer 发现的可修复问题进入闭环；前端仍可为单次 run 调整为 0-2 轮。
    private int maxReviewReworkAttempts = 1;

    int maxReviewReworkAttempts() {
        return Math.max(0, Math.min(maxReviewReworkAttempts, 2));
    }
}
