package ai.reviewforge.github.service;

import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.client.InstallationTokenService;
import ai.reviewforge.github.persistence.GitHubInstallationRepository;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RepositorySyncService {

    private final GitHubApiClient apiClient;
    private final InstallationTokenService tokenService;
    private final GitHubInstallationRepository installationRepository;
    private final GitHubRepositoryRepository repositoryRepository;

    public RepositorySyncService(
            GitHubApiClient apiClient,
            InstallationTokenService tokenService,
            GitHubInstallationRepository installationRepository,
            GitHubRepositoryRepository repositoryRepository
    ) {
        this.apiClient = apiClient;
        this.tokenService = tokenService;
        this.installationRepository = installationRepository;
        this.repositoryRepository = repositoryRepository;
    }

    public List<GitHubRepositoryRepository.RepositoryRecord> repositoriesForUser(UUID userId, boolean refresh) {
        if (refresh) {
            installationRepository.findActiveByUser(userId).forEach(this::sync);
        }
        return repositoryRepository.findByUser(userId);
    }

    @Transactional
    public void sync(GitHubInstallationRepository.InstallationRecord installation) {
        String token = tokenService.get(installation.installationId());
        List<GitHubApiClient.GitHubRepository> repositories = apiClient.listInstallationRepositories(token);
        repositories.forEach(repository -> repositoryRepository.upsert(installation.id(), repository));
        repositoryRepository.archiveMissing(
                installation.id(),
                repositories.stream().map(GitHubApiClient.GitHubRepository::id).toList()
        );
    }
}
