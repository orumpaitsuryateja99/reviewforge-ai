package ai.reviewforge.github.web;

import ai.reviewforge.github.auth.CurrentSessionService;
import ai.reviewforge.github.auth.GitHubSession;
import ai.reviewforge.github.service.GitHubInstallationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/github/installations")
public class GitHubInstallationController {

    private final CurrentSessionService currentSessionService;
    private final GitHubInstallationService installationService;

    public GitHubInstallationController(
            CurrentSessionService currentSessionService,
            GitHubInstallationService installationService
    ) {
        this.currentSessionService = currentSessionService;
        this.installationService = installationService;
    }

    @GetMapping("/new")
    public ResponseEntity<Void> install(HttpServletRequest request) {
        GitHubSession session = currentSessionService.require(request);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(installationService.beginInstallation(session))
                .build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            HttpServletRequest request,
            @RequestParam("installation_id") long installationId,
            @RequestParam String state
    ) {
        GitHubSession session = currentSessionService.require(request);
        installationService.completeInstallation(session, installationId, state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(installationService.frontendRedirect())
                .build();
    }
}
