package ai.reviewforge.github.web;

import ai.reviewforge.github.auth.CurrentSessionService;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import ai.reviewforge.github.service.RepositorySyncService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final CurrentSessionService currentSessionService;
    private final RepositorySyncService repositorySyncService;

    public RepositoryController(CurrentSessionService currentSessionService, RepositorySyncService repositorySyncService) {
        this.currentSessionService = currentSessionService;
        this.repositorySyncService = repositorySyncService;
    }

    @GetMapping
    public List<RepositoryResponse> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "true") boolean refresh
    ) {
        UUID userId = currentSessionService.require(request).userId();
        return repositorySyncService.repositoriesForUser(userId, refresh).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public record RepositoryResponse(
            UUID id,
            long githubRepositoryId,
            String fullName,
            String defaultBranch,
            boolean privateRepository,
            boolean testExecutionAllowed
    ) {
        static RepositoryResponse from(GitHubRepositoryRepository.RepositoryRecord repository) {
            return new RepositoryResponse(
                    repository.id(), repository.githubRepositoryId(), repository.fullName(),
                    repository.defaultBranch(), repository.privateRepository(), repository.testExecutionAllowed()
            );
        }
    }
}
