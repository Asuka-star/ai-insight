package com.aiinsight.service;

import com.aiinsight.dto.RunEvent;
import com.aiinsight.model.run.AnalysisRun;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AnalysisEventBroker {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    // 一个 run 可以被多个工作台页面同时订阅，所以按 runId 保存 emitter 列表。
    private final ConcurrentMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        // 浏览器断开后及时移除，防止长期演示时堆积失效连接。
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        send(emitter, RunEvent.of(runId, "subscribed", "SSE connection established"));
        return emitter;
    }

    public void publish(AnalysisRun run, String type, String message) {
        RunEvent event = RunEvent.of(run.getId(), type, message);
        emitters.getOrDefault(run.getId(), List.of()).forEach(emitter -> send(emitter, event));
    }

    private void send(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.getType()).data(event));
        } catch (IOException | IllegalStateException ex) {
            // 写失败通常意味着客户端已断开，交给 completion 回调清理。
            emitter.complete();
        }
    }

    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> runEmitters = emitters.get(runId);
        if (runEmitters != null) {
            runEmitters.remove(emitter);
            if (runEmitters.isEmpty()) {
                emitters.remove(runId, runEmitters);
            }
        }
    }
}
