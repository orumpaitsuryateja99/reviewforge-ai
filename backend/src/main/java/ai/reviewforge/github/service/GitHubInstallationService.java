package ai.reviewforge.github.service;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.GitHubProperties;
import ai.reviewforge.github.auth.GitHubSession;
import ai.reviewforge.github.auth.OAuthStateStore;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.client.GitHubOAuthClient;
import ai.reviewforge.github.persistence.GitHubInstallationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Service
public class GitHubInstallationService {

    private final GitHubProperties properties;
    private final OAuthStateStore stateStore;
    private final GitHubOAuthClient oauthClient;
    private final GitHubApiClient apiClient;
    private final GitHubInstallationRepository installationRepository;
    private final RepositorySyncService repositorySyncService;

    public GitHubInstallationService(
            GitHubProperties properties,
            OAuthStateStore stateStore,
            GitHubOAuthClient oauthClient,
            GitHubApiClient apiClient,
            GitHubInstallationRepository installationRepository,
            RepositorySyncService repositorySyncService
    ) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.oauthClient = oauthClient;
        this.apiClient = apiClient;
        this.installationRepository = installationRepository;
        this.repositorySyncService = repositorySyncService;
    }

    public URI beginInstallation(GitHubSession session) {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED",
                    "GitHub App credentials have not been configured on the server.");
        }
        String state = stateStore.issue("INSTALL", session.userId().toString());
        return UriComponentsBuilder.fromUriString("https://github.com/apps/{slug}/installations/new")
                .queryParam("state", state)
                .buildAndExpand(properties.appSlug())
                .toUri();
    }

    public void completeInstallation(GitHubSession session, long installationId, String state) {
        stateStore.consume(state, "INSTALL", session.userId().toString());
        if (!oauthClient.canAccessInstallation(session.userAccessToken(), installationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INSTALLATION_ACCESS_DENIED",
                    "The GitHub App installation is not associated with the signed-in user.");
        }

        GitHubApiClient.Installation installation = apiClient.getInstallation(installationId);
        GitHubInstallationRepository.InstallationRecord saved = installationRepository.upsert(
                session.userId(), installation
        );
        repositorySyncService.sync(saved);
    }

    public URI frontendRedirect() {
        return UriComponentsBuilder.fromUriString(properties.frontendUrl())
                .queryParam("github", "installed")
                .build(true)
                .toUri();
    }
}
