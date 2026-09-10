package ai.reviewforge.analysis.web;

import ai.reviewforge.analysis.AnalysisService;
import ai.reviewforge.analysis.AnalysisView;
import ai.reviewforge.common.api.JobAccepted;
import ai.reviewforge.findings.RejectedFinding;
import ai.reviewforge.github.auth.CurrentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private final CurrentSessionService currentSessionService;
    private final AnalysisService analysisService;

    public AnalysisController(CurrentSessionService currentSessionService, AnalysisService analysisService) {
        this.currentSessionService = currentSessionService;
        this.analysisService = analysisService;
    }

    @PostMapping("/pull-requests/{pullRequestId}/analyses")
    public ResponseEntity<JobAccepted> request(HttpServletRequest request, @PathVariable UUID pullRequestId) {
        AnalysisView analysis = analysisService.request(userId(request), pullRequestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JobAccepted.of(analysis.id(), "/api/v1/analyses/" + analysis.id()));
    }

    @GetMapping("/pull-requests/{pullRequestId}/analyses")
    public List<AnalysisView> list(HttpServletRequest request, @PathVariable UUID pullRequestId) {
        return analysisService.listForPullRequest(userId(request), pullRequestId);
    }

    @GetMapping("/analyses/{analysisId}")
    public AnalysisView get(HttpServletRequest request, @PathVariable UUID analysisId) {
        return analysisService.get(userId(request), analysisId);
    }

    @GetMapping("/analyses/{analysisId}/findings")
    public List<FindingResponse> findings(HttpServletRequest request, @PathVariable UUID analysisId) {
        return analysisService.findings(userId(request), analysisId).stream()
                .map(FindingResponse::from)
                .toList();
    }

    @GetMapping("/analyses/{analysisId}/rejected-findings")
    public List<RejectedFinding> rejectedFindings(HttpServletRequest request, @PathVariable UUID analysisId) {
        return analysisService.rejectedFindings(userId(request), analysisId);
    }

    private UUID userId(HttpServletRequest request) {
        return currentSessionService.require(request).userId();
    }
}
