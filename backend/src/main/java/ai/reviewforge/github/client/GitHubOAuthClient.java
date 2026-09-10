package ai.reviewforge.github.client;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.GitHubProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class GitHubOAuthClient {

    private final RestClient restClient;
    private final GitHubProperties properties;

    public GitHubOAuthClient(RestClient.Builder builder, GitHubProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(properties.apiTimeout());
        this.restClient = builder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    public URI authorizationUri(String state) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.oauthCallbackUrl())
                .queryParam("state", state)
                .build(true)
                .toUri();
    }

    public OAuthToken exchangeCode(String code) {
        requireConfigured();
        try {
            OAuthToken token = restClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "client_id", properties.clientId(),
                            "client_secret", properties.clientSecret(),
                            "code", code,
                            "redirect_uri", properties.oauthCallbackUrl()
                    ))
                    .retrieve()
                    .body(OAuthToken.class);

            if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
                throw upstream("GitHub did not return a user access token.");
            }
            return token;
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub rejected the authorization code.");
        }
    }

    public GitHubUser getUser(String accessToken) {
        try {
            GitHubUser user = restClient.get()
                    .uri(properties.apiUrl() + "/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", properties.apiVersion())
                    .retrieve()
                    .body(GitHubUser.class);
            if (user == null) {
                throw upstream("GitHub returned an empty user profile.");
            }
            return user;
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub could not return the authorized user profile.");
        }
    }

    public boolean canAccessInstallation(String accessToken, long installationId) {
        try {
            restClient.get()
                    .uri(properties.apiUrl() + "/user/installations/" + installationId + "/repositories?per_page=1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", properties.apiVersion())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 403 || exception.getStatusCode().value() == 404) {
                return false;
            }
            throw upstream("GitHub could not validate the installation for this user.");
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED",
                    "GitHub App credentials have not been configured on the server.");
        }
    }

    private ApiException upstream(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_AUTH_FAILED", message);
    }

    public record OAuthToken(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }

    public record GitHubUser(long id, String login, String name, @JsonProperty("avatar_url") String avatarUrl) {
    }
}
