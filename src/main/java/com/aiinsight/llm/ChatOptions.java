package com.aiinsight.llm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatOptions {

    private double temperature;
    private int maxTokens;

    public static ChatOptions deterministic() {
        return new ChatOptions(0.2, 1800);
    }
}
