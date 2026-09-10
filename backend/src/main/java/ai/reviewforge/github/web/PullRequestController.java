package ai.reviewforge.github.web;

import ai.reviewforge.github.auth.CurrentSessionService;
import ai.reviewforge.github.service.PullRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PullRequestController {

    private final CurrentSessionService currentSessionService;
    private final PullRequestService pullRequestService;

    public PullRequestController(CurrentSessionService currentSessionService, PullRequestService pullRequestService) {
        this.currentSessionService = currentSessionService;
        this.pullRequestService = pullRequestService;
    }

    @GetMapping("/repositories/{repositoryId}/pull-requests")
    public List<PullRequestService.PullRequestSummary> list(
            HttpServletRequest request,
            @PathVariable UUID repositoryId
    ) {
        return pullRequestService.list(currentSessionService.require(request).userId(), repositoryId);
    }

    @GetMapping("/pull-requests/{pullRequestId}")
    public PullRequestService.PullRequestDetail detail(
            HttpServletRequest request,
            @PathVariable UUID pullRequestId
    ) {
        return pullRequestService.detail(currentSessionService.require(request).userId(), pullRequestId);
    }
}
