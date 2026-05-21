package com.aiinsight.api;

import com.aiinsight.domain.AgentName;
import com.aiinsight.domain.AnalysisRun;
import com.aiinsight.service.AnalysisEventBroker;
import com.aiinsight.service.AnalysisWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis-runs")
@RequiredArgsConstructor
public class AnalysisRunController {

    private final AnalysisWorkflowService workflowService;
    private final AnalysisEventBroker eventBroker;

    @PostMapping
    public AnalysisRun create(@Valid @RequestBody CreateAnalysisRunRequest request) {
        return workflowService.start(request);
    }

    @GetMapping
    public Collection<AnalysisRun> list() {
        return workflowService.list();
    }

    @GetMapping("/{runId}")
    public AnalysisRun get(@PathVariable UUID runId) {
        return workflowService.get(runId);
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID runId) {
        return eventBroker.subscribe(runId);
    }

    @PostMapping("/{runId}/agents/{agentName}/rerun")
    public AnalysisRun rerunAgent(@PathVariable UUID runId, @PathVariable AgentName agentName) {
        return workflowService.rerunAgent(runId, agentName);
    }
}
