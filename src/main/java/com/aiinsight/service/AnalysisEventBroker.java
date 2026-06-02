package com.aiinsight.service;

import com.aiinsight.dto.RunEvent;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.repository.AnalysisRunRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final long SSE_NO_TIMEOUT = 0L;
    public static final String RUN_SNAPSHOT_EVENT = "run_snapshot";

    // 一个 run 可以被多个工作台页面同时订阅，所以按 runId 保存 emitter 列表。
    private final ConcurrentMap<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AnalysisRunRepository repository;

    public AnalysisEventBroker() {
        this.repository = null;
    }

    @Autowired
    public AnalysisEventBroker(AnalysisRunRepository repository) {
        this.repository = repository;
    }

    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(SSE_NO_TIMEOUT);
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        // 浏览器断开后及时移除，防止长期演示时堆积失效连接。
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(ignored -> remove(runId, emitter));
        if (!send(emitter, RunEvent.of(runId, "subscribed", "SSE connection established"))) {
            remove(runId, emitter);
            return emitter;
        }
        // 补发一次当前权威快照，避免客户端拿到 runId 后才订阅而错过刚发生的 agent_started/agent_succeeded。
        if (repository != null) {
            repository.findById(runId).ifPresent(run -> {
                if (!send(emitter, RUN_SNAPSHOT_EVENT, run)) {
                    remove(runId, emitter);
                }
            });
        }
        return emitter;
    }

    public void publish(AnalysisRun run, String type, String message) {
        RunEvent event = RunEvent.of(run.getId(), type, message);
        UUID runId = run.getId();
        emitters.getOrDefault(runId, List.of()).forEach(emitter -> {
            if (!send(emitter, event)) {
                remove(runId, emitter);
            }
        });
    }

    public void close(UUID runId) {
        List<SseEmitter> runEmitters = emitters.remove(runId);
        if (runEmitters != null) {
            runEmitters.forEach(this::completeQuietly);
        }
    }

    private boolean send(SseEmitter emitter, RunEvent event) {
        return send(emitter, event.getType(), event);
    }

    private boolean send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
            return true;
        } catch (IOException | RuntimeException ex) {
            // 写失败通常意味着客户端已断开。这里不要再 complete()，因为容器可能已经进入 onError 后的错误态。
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 连接可能已经由容器错误回调关闭，显式 close 时吞掉即可。
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
