package ai.reviewforge.github.service;

import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.client.InstallationTokenService;
import ai.reviewforge.github.persistence.GitHubInstallationRepository;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import ai.reviewforge.github.persistence.PullRequestRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PullRequestService {

    private final GitHubRepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final PullRequestRepository pullRequestRepository;
    private final InstallationTokenService tokenService;
    private final GitHubApiClient apiClient;

    public PullRequestService(
            GitHubRepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            PullRequestRepository pullRequestRepository,
            InstallationTokenService tokenService,
            GitHubApiClient apiClient
    ) {
        this.repositoryRepository = repositoryRepository;
        this.installationRepository = installationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.tokenService = tokenService;
        this.apiClient = apiClient;
    }

    public List<PullRequestSummary> list(UUID userId, UUID repositoryId) {
        GitHubRepositoryRepository.RepositoryRecord repository = repositoryRepository.requireForUser(repositoryId, userId);
        String token = installationToken(repository, userId);
        return apiClient.listOpenPullRequests(token, repository.owner(), repository.name()).stream()
                .map(pullRequest -> pullRequestRepository.upsert(repository.id(), pullRequest))
                .map(this::summary)
                .toList();
    }

    /**
     * Resolves a pull request together with the repository and installation token needed to
     * read it. With {@code refresh}, GitHub is consulted so the caller sees the current head
     * SHA; without it, the cached record is returned unchanged.
     */
    public Resolved resolve(UUID userId, UUID pullRequestId, boolean refresh) {
        PullRequestRepository.PullRequestRecord existing = pullRequestRepository.requireForUser(pullRequestId, userId);
        GitHubRepositoryRepository.RepositoryRecord repository = repositoryRepository.requireForUser(
                existing.repositoryId(), userId
        );
        String token = installationToken(repository, userId);
        if (!refresh) {
            return new Resolved(repository, existing, token);
        }
        GitHubApiClient.PullRequest live = apiClient.getPullRequest(
                token, repository.owner(), repository.name(), existing.number()
        );
        return new Resolved(repository, pullRequestRepository.upsert(repository.id(), live), token);
    }

    public PullRequestDetail detail(UUID userId, UUID pullRequestId) {
        Resolved resolved = resolve(userId, pullRequestId, true);
        GitHubRepositoryRepository.RepositoryRecord repository = resolved.repository();
        String token = resolved.token();
        PullRequestRepository.PullRequestRecord saved = resolved.pullRequest();
        GitHubApiClient.ChangedFilesResult changedFiles = apiClient.compareFiles(
                token, repository.owner(), repository.name(), saved.baseSha(), saved.headSha()
        );
        List<ChangedFile> files = changedFiles.files().stream()
                .map(this::changedFile)
                .toList();
        return new PullRequestDetail(
                saved.id(), saved.repositoryId(), saved.number(), saved.title(), saved.author(), saved.state(),
                saved.baseRef(), saved.baseSha(), saved.headRef(), saved.headSha(), saved.updatedAt(),
                files, changedFiles.truncated()
        );
    }

    private String installationToken(GitHubRepositoryRepository.RepositoryRecord repository, UUID userId) {
        GitHubInstallationRepository.InstallationRecord installation = installationRepository.requireActive(
                repository.installationId(), userId
        );
        return tokenService.get(installation.installationId());
    }

    private PullRequestSummary summary(PullRequestRepository.PullRequestRecord pullRequest) {
        return new PullRequestSummary(
                pullRequest.id(), pullRequest.repositoryId(), pullRequest.number(), pullRequest.title(),
                pullRequest.author(), pullRequest.state(), pullRequest.headSha(), pullRequest.updatedAt()
        );
    }

    private ChangedFile changedFile(GitHubApiClient.ChangedFile file) {
        String status = switch (file.status().toLowerCase(Locale.ROOT)) {
            case "added" -> "ADDED";
            case "removed" -> "REMOVED";
            case "renamed" -> "RENAMED";
            default -> "MODIFIED";
        };
        boolean patchUnavailable = file.patch() == null;
        return new ChangedFile(
                file.filename(), file.previousFilename(), status, file.additions(), file.deletions(),
                patchUnavailable ? "" : file.patch(), patchUnavailable
        );
    }

    public record Resolved(
            GitHubRepositoryRepository.RepositoryRecord repository,
            PullRequestRepository.PullRequestRecord pullRequest,
            String token
    ) {
    }

    public record PullRequestSummary(
            UUID id,
            UUID repositoryId,
            int number,
            String title,
            String authorLogin,
            String state,
            String headSha,
            Instant updatedAt
    ) {
    }

    public record PullRequestDetail(
            UUID id,
            UUID repositoryId,
            int number,
            String title,
            String authorLogin,
            String state,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            Instant updatedAt,
            List<ChangedFile> files,
            boolean filesTruncated
    ) {
    }

    public record ChangedFile(
            String path,
            String previousPath,
            String status,
            int additions,
            int deletions,
            String patch,
            boolean patchTruncated
    ) {
    }
}
