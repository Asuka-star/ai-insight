package com.aiinsight.service;

import com.aiinsight.model.run.AnalysisRequirement;
import com.aiinsight.model.run.AnalysisRun;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AnalysisEventBrokerTest {

    @Test
    void subscribeUsesNoFixedTimeoutForLongRunningAnalysis() {
        AnalysisEventBroker broker = new AnalysisEventBroker();

        var emitter = broker.subscribe(UUID.randomUUID());

        assertThat(emitter.getTimeout()).isZero();
    }

    @Test
    void publishDropsBrokenEmitterWithoutCompletingErroredAsyncContext() {
        AnalysisEventBroker broker = new AnalysisEventBroker();
        AnalysisRun run = new AnalysisRun(new AnalysisRequirement());
        BrokenEmitter brokenEmitter = new BrokenEmitter();
        @SuppressWarnings("unchecked")
        Map<UUID, List<SseEmitter>> emitters = (Map<UUID, List<SseEmitter>>) ReflectionTestUtils.getField(broker, "emitters");
        emitters.put(run.getId(), new CopyOnWriteArrayList<>(List.of(brokenEmitter)));

        assertThatCode(() -> broker.publish(run, "agent_started", "agent started")).doesNotThrowAnyException();

        assertThat(emitters).doesNotContainKey(run.getId());
        assertThat(brokenEmitter.completeCalled).isFalse();
    }

    private static class BrokenEmitter extends SseEmitter {
        private boolean completeCalled;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IllegalStateException("A non-container (application) thread attempted to use the AsyncContext after an error had occurred");
        }

        @Override
        public void complete() {
            completeCalled = true;
            throw new IllegalStateException("AsyncContext is already in error state");
        }
    }
}
