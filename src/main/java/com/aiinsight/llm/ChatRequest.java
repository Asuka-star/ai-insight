package com.aiinsight.llm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private List<ChatMessage> messages;
    private ChatOptions options;
    private String agentName;
    private String subtaskName;

    public ChatRequest(List<ChatMessage> messages, ChatOptions options) {
        this.messages = messages;
        this.options = options;
    }

    public ChatRequest tagged(String agentName, String subtaskName) {
        this.agentName = agentName;
        this.subtaskName = subtaskName;
        return this;
    }
}
