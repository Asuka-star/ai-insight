package com.aiinsight.controller;

import com.aiinsight.dto.CreateAnalysisRunRequest;
import com.aiinsight.dto.AddAnalysisContextRequest;
import com.aiinsight.dto.AddUserEvidenceRequest;
import com.aiinsight.dto.AnalysisRunMetrics;
import com.aiinsight.dto.AnalysisRunSummary;
import com.aiinsight.dto.UpdateAnalysisRequirementRequest;
import com.aiinsight.model.enums.AgentName;
import com.aiinsight.model.run.AgentTrace;
import com.aiinsight.model.run.AnalysisRun;
import com.aiinsight.model.run.EvidenceChunk;
import com.aiinsight.service.AnalysisEventBroker;
import com.aiinsight.service.AnalysisWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        return workflowService.createDraft(request);
    }

    @GetMapping
    public Collection<AnalysisRun> list() {
        return workflowService.list();
    }

    @GetMapping("/summaries")
    public Collection<AnalysisRunSummary> listSummaries() {
        return workflowService.listSummaries();
    }

    @GetMapping("/{runId}")
    public AnalysisRun get(@PathVariable UUID runId) {
        return workflowService.get(runId);
    }

    @PutMapping("/{runId}/requirement")
    public AnalysisRun updateRequirement(@PathVariable UUID runId,
                                         @RequestBody UpdateAnalysisRequirementRequest request) {
        return workflowService.updateRequirement(runId, request);
    }

    @PostMapping("/{runId}/start")
    public AnalysisRun start(@PathVariable UUID runId) {
        return workflowService.startExecution(runId);
    }

    @PostMapping("/{runId}/cancel")
    public AnalysisRun cancel(@PathVariable UUID runId) {
        return workflowService.cancel(runId);
    }

    @PostMapping("/{runId}/context")
    public AnalysisRun addContext(@PathVariable UUID runId,
                                  @Valid @RequestBody AddAnalysisContextRequest request) {
        return workflowService.addContext(runId, request);
    }

    @PostMapping("/{runId}/evidence")
    public AnalysisRun addEvidence(@PathVariable UUID runId,
                                   @Valid @RequestBody AddUserEvidenceRequest request) {
        return workflowService.addEvidence(runId, request);
    }

    @GetMapping("/{runId}/traces")
    public Collection<AgentTrace> traces(@PathVariable UUID runId) {
        return workflowService.traces(runId);
    }

    @GetMapping("/{runId}/metrics")
    public AnalysisRunMetrics metrics(@PathVariable UUID runId) {
        return workflowService.metrics(runId);
    }

    @GetMapping("/{runId}/retrieval")
    public Collection<EvidenceChunk> retrieveEvidence(@PathVariable UUID runId,
                                                      @RequestParam String query,
                                                      @RequestParam(required = false) Integer topK) {
        return workflowService.retrieveEvidence(runId, query, topK);
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
