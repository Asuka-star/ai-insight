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

    public static ChatOptions clarifier() {
        return new ChatOptions(0.2, 700);
    }

    public static ChatOptions researcher() {
        return new ChatOptions(0.2, 1600);
    }

    public static ChatOptions searchQueryPlanner() {
        return new ChatOptions(0.2, 900);
    }

    public static ChatOptions extractor() {
        return new ChatOptions(0.2, 2500);
    }

    public static ChatOptions analyst() {
        return new ChatOptions(0.2, 2200);
    }

    public static ChatOptions writer() {
        return new ChatOptions(0.2, 4500);
    }

    public static ChatOptions reviewer() {
        return new ChatOptions(0.2, 1800);
    }
}
