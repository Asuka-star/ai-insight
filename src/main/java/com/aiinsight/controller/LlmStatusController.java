package com.aiinsight.controller;

import com.aiinsight.dto.LlmStatusResponse;
import com.aiinsight.llm.DoubaoLlmProperties;
import com.aiinsight.llm.LlmClient;
import com.aiinsight.llm.XiaomiLlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmStatusController {

    private final LlmClient llmClient;
    private final XiaomiLlmProperties properties;
    private final DoubaoLlmProperties doubaoProperties;

    @GetMapping("/status")
    public LlmStatusResponse status() {
        return new LlmStatusResponse(
                llmClient.isAvailable(),
                StringUtils.hasText(properties.getApiKey()),
                "xiaomi-openai-compatible",
                properties.getModel(),
                properties.getBaseUrl(),
                properties.getCompletionsPath(),
                StringUtils.hasText(doubaoProperties.getApiKey()),
                StringUtils.hasText(doubaoProperties.getEndpointId()),
                "doubao-openai-compatible",
                doubaoProperties.getEndpointId(),
                doubaoProperties.getDisplayModel(),
                doubaoProperties.getBaseUrl(),
                doubaoProperties.getCompletionsPath()
        );
    }
}
