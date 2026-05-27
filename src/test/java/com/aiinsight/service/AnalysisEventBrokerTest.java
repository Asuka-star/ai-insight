package com.aiinsight.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisEventBrokerTest {

    @Test
    void subscribeUsesNoFixedTimeoutForLongRunningAnalysis() {
        AnalysisEventBroker broker = new AnalysisEventBroker();

        var emitter = broker.subscribe(UUID.randomUUID());

        assertThat(emitter.getTimeout()).isZero();
    }
}
