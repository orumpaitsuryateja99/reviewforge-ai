package ai.reviewforge.github.auth;

import ai.reviewforge.config.GitHubProperties;
import ai.reviewforge.github.client.GitHubOAuthClient;
import ai.reviewforge.github.persistence.UserRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class GitHubAuthService {

    private final OAuthStateStore stateStore;
    private final GitHubOAuthClient oauthClient;
    private final UserRepository userRepository;
    private final GitHubSessionStore sessionStore;
    private final GitHubProperties properties;
    private final Clock clock;

    public GitHubAuthService(
            OAuthStateStore stateStore,
            GitHubOAuthClient oauthClient,
            UserRepository userRepository,
            GitHubSessionStore sessionStore,
            GitHubProperties properties,
            Clock clock
    ) {
        this.stateStore = stateStore;
        this.oauthClient = oauthClient;
        this.userRepository = userRepository;
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.clock = clock;
    }

    public URI beginLogin() {
        return oauthClient.authorizationUri(stateStore.issue("LOGIN", "anonymous"));
    }

    public GitHubSessionStore.CreatedSession completeLogin(String code, String state) {
        stateStore.consume(state, "LOGIN", "anonymous");
        GitHubOAuthClient.OAuthToken token = oauthClient.exchangeCode(code);
        GitHubOAuthClient.GitHubUser user = oauthClient.getUser(token.accessToken());
        UUID userId = userRepository.upsert(user.id(), user.login(), user.name(), user.avatarUrl());
        Instant tokenExpiry = token.expiresIn() == null || token.expiresIn() <= 0
                ? null
                : clock.instant().plusSeconds(token.expiresIn());
        return sessionStore.create(new GitHubSession(
                userId, user.login(), user.avatarUrl(), token.accessToken(), tokenExpiry
        ));
    }

    public URI frontendRedirect(String result) {
        return UriComponentsBuilderHelper.appendQuery(properties.frontendUrl(), "github", result);
    }

    private static final class UriComponentsBuilderHelper {
        private static URI appendQuery(String baseUrl, String name, String value) {
            return org.springframework.web.util.UriComponentsBuilder.fromUriString(baseUrl)
                    .queryParam(name, value)
                    .build(true)
                    .toUri();
        }
    }
}
