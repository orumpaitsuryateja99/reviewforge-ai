package ai.reviewforge.github.client;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.GitHubProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class GitHubApiClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_FILE_PAGES = 30;
    private static final int MAX_COMPARISON_FILES = 300;

    private final RestClient restClient;
    private final RestClient unauthenticatedClient;
    private final GitHubProperties properties;
    private final GitHubAppJwtFactory jwtFactory;

    public GitHubApiClient(RestClient.Builder builder, GitHubProperties properties, GitHubAppJwtFactory jwtFactory) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(properties.apiTimeout());
        this.restClient = builder.requestFactory(requestFactory).baseUrl(properties.apiUrl()).build();
        this.unauthenticatedClient = RestClient.builder().requestFactory(requestFactory).build();
        this.properties = properties;
        this.jwtFactory = jwtFactory;
    }

    public Installation getInstallation(long installationId) {
        return get("/app/installations/{id}", jwtFactory.create(), Installation.class, installationId);
    }

    public InstallationAccessToken createInstallationToken(long installationId) {
        try {
            InstallationAccessToken token = requestHeaders(
                    restClient.post().uri("/app/installations/{id}/access_tokens", installationId),
                    jwtFactory.create()
            ).retrieve().body(InstallationAccessToken.class);
            if (token == null || token.token() == null) {
                throw upstream("GitHub returned an empty installation token.");
            }
            return token;
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub could not create an installation access token.");
        }
    }

    public List<GitHubRepository> listInstallationRepositories(String token) {
        List<GitHubRepository> repositories = new ArrayList<>();
        for (int page = 1; ; page++) {
            RepositoryPage response = get(
                    "/installation/repositories?per_page={perPage}&page={page}",
                    token,
                    RepositoryPage.class,
                    PAGE_SIZE,
                    page
            );
            repositories.addAll(response.repositories());
            if (response.repositories().size() < PAGE_SIZE) {
                return repositories;
            }
        }
    }

    public List<PullRequest> listOpenPullRequests(String token, String owner, String repository) {
        List<PullRequest> pullRequests = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<PullRequest> response = getList(
                    "/repos/{owner}/{repository}/pulls?state=open&sort=updated&direction=desc&per_page={perPage}&page={page}",
                    token,
                    new ParameterizedTypeReference<>() {},
                    owner,
                    repository,
                    PAGE_SIZE,
                    page
            );
            pullRequests.addAll(response);
            if (response.size() < PAGE_SIZE) {
                return pullRequests;
            }
        }
    }

    public PullRequest getPullRequest(String token, String owner, String repository, int number) {
        return get("/repos/{owner}/{repository}/pulls/{number}", token, PullRequest.class,
                owner, repository, number);
    }

    public ChangedFilesResult listPullRequestFiles(String token, String owner, String repository, int number) {
        List<ChangedFile> files = new ArrayList<>();
        for (int page = 1; page <= MAX_FILE_PAGES; page++) {
            List<ChangedFile> response = getList(
                    "/repos/{owner}/{repository}/pulls/{number}/files?per_page={perPage}&page={page}",
                    token,
                    new ParameterizedTypeReference<>() {},
                    owner,
                    repository,
                    number,
                    PAGE_SIZE,
                    page
            );
            files.addAll(response);
            if (response.size() < PAGE_SIZE) {
                return new ChangedFilesResult(List.copyOf(files), false);
            }
        }
        return new ChangedFilesResult(List.copyOf(files), true);
    }

    /**
     * Reads the patch between two immutable commits. Unlike the pull-request files endpoint,
     * this cannot drift if another commit is pushed while ReviewForge is building context.
     */
    public ChangedFilesResult compareFiles(
            String token, String owner, String repository, String baseSha, String headSha
    ) {
        Comparison comparison = get(
                "/repos/{owner}/{repository}/compare/{base}...{head}", token, Comparison.class,
                owner, repository, baseSha, headSha
        );
        List<ChangedFile> files = comparison.files() == null ? List.of() : List.copyOf(comparison.files());
        return new ChangedFilesResult(files, files.size() >= MAX_COMPARISON_FILES);
    }

    /**
     * Reads a file exactly as it exists at {@code ref}. Returns empty when GitHub reports the
     * path is absent at that commit, which is a normal answer for a deleted or renamed file.
     */
    public Optional<String> getFileContent(String token, String owner, String repository, String path, String ref) {
        try {
            String body = restClient.get()
                    .uri("/repos/{owner}/{repository}/contents/{path}?ref={ref}", owner, repository, path, ref)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                    .header("X-GitHub-Api-Version", properties.apiVersion())
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(body);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw upstream("GitHub could not read " + path + " at " + ref + ".");
        }
    }

    /** Lists blob paths in the commit tree, capped so a very large repository cannot exhaust memory. */
    public List<String> listTreePaths(String token, String owner, String repository, String ref, int limit) {
        Tree tree = get("/repos/{owner}/{repository}/git/trees/{ref}?recursive=1", token, Tree.class,
                owner, repository, ref);
        return tree.tree().stream()
                .filter(entry -> "blob".equals(entry.type()))
                .map(TreeEntry::path)
                .limit(limit)
                .toList();
    }

    /**
     * Downloads the repository archive at an exact commit. The bytes are forwarded to the
     * isolated runner; no credential travels with them.
     */
    public byte[] downloadTarball(String token, String owner, String repository, String ref, long maxBytes) {
        try {
            ArchiveResponse response = restClient.get()
                    .uri("/repos/{owner}/{repository}/tarball/{ref}", owner, repository, ref)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header("X-GitHub-Api-Version", properties.apiVersion())
                    .exchange((request, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (status.is3xxRedirection()) {
                            return new ArchiveResponse(null, clientResponse.getHeaders().getFirst(HttpHeaders.LOCATION));
                        }
                        if (!status.is2xxSuccessful()) {
                            throw upstream("GitHub returned status " + status.value() + " for the source archive.");
                        }
                        return new ArchiveResponse(readBounded(clientResponse.getBody(), maxBytes), null);
                    });

            if (response == null) {
                throw upstream("GitHub returned an empty repository archive.");
            }
            if (response.archive() != null) {
                return response.archive();
            }
            if (response.location() == null) {
                throw upstream("GitHub redirected the archive request without a location.");
            }
            // GitHub redirects archives to a short-lived signed URL. That host must not receive
            // the installation token, so the follow-up request carries no credentials.
            return followArchiveRedirect(response.location(), maxBytes);
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub could not provide a source archive for " + ref + ".");
        }
    }

    private byte[] followArchiveRedirect(String location, long maxBytes) {
        URI target = trustedArchiveUri(location);
        byte[] archive = unauthenticatedClient.get()
                .uri(target)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw upstream("The archive download failed with status "
                                + response.getStatusCode().value() + ".");
                    }
                    return readBounded(response.getBody(), maxBytes);
                });
        if (archive == null || archive.length == 0) {
            throw upstream("The archive download returned no content.");
        }
        return archive;
    }

    private URI trustedArchiveUri(String location) {
        URI target;
        try {
            target = URI.create(location);
        } catch (IllegalArgumentException exception) {
            throw upstream("GitHub returned an invalid archive redirect.");
        }
        String host = target.getHost();
        String apiHost = URI.create(properties.apiUrl()).getHost();
        boolean trustedHost = host != null && (host.equalsIgnoreCase(apiHost)
                || host.equalsIgnoreCase("codeload.github.com")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".githubusercontent.com"));
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null || !trustedHost) {
            throw upstream("GitHub returned an untrusted archive redirect.");
        }
        return target;
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "SNAPSHOT_TOO_LARGE",
                        "The repository archive exceeds the configured snapshot limit of " + maxBytes + " bytes.");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private <T> T get(String uri, String token, Class<T> responseType, Object... variables) {
        try {
            T body = requestHeaders(restClient.get().uri(uri, variables), token)
                    .retrieve()
                    .body(responseType);
            if (body == null) {
                throw upstream("GitHub returned an empty response.");
            }
            return body;
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub API request failed with status " + exception.getStatusCode().value() + ".");
        }
    }

    private <T> List<T> getList(
            String uri,
            String token,
            ParameterizedTypeReference<List<T>> responseType,
            Object... variables
    ) {
        try {
            List<T> body = requestHeaders(restClient.get().uri(uri, variables), token)
                    .retrieve()
                    .body(responseType);
            return body == null ? List.of() : body;
        } catch (RestClientResponseException exception) {
            throw upstream("GitHub API request failed with status " + exception.getStatusCode().value() + ".");
        }
    }

    private RestClient.RequestHeadersSpec<?> requestHeaders(RestClient.RequestHeadersSpec<?> request, String token) {
        return request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", properties.apiVersion());
    }

    private ApiException upstream(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_FAILED", message);
    }

    public record Account(long id, String login, String type) {
    }

    public record Installation(long id, Account account, @JsonProperty("suspended_at") Instant suspendedAt) {
    }

    public record InstallationAccessToken(String token, @JsonProperty("expires_at") Instant expiresAt) {
    }

    public record RepositoryPage(@JsonProperty("total_count") int totalCount, List<GitHubRepository> repositories) {
    }

    public record GitHubRepository(
            long id,
            String name,
            @JsonProperty("full_name") String fullName,
            Owner owner,
            @JsonProperty("default_branch") String defaultBranch,
            @JsonProperty("private") boolean privateRepository,
            boolean archived
    ) {
    }

    public record Owner(String login) {
    }

    public record PullRequest(
            long id,
            int number,
            String title,
            String state,
            User user,
            GitReference base,
            GitReference head,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("merged_at") Instant mergedAt
    ) {
    }

    public record User(String login) {
    }

    public record GitReference(String ref, String sha) {
    }

    public record ChangedFile(
            String filename,
            @JsonProperty("previous_filename") String previousFilename,
            String status,
            int additions,
            int deletions,
            String patch
    ) {
    }

    public record ChangedFilesResult(List<ChangedFile> files, boolean truncated) {
    }

    public record Comparison(List<ChangedFile> files) {
    }

    private record ArchiveResponse(byte[] archive, String location) {
    }

    public record Tree(String sha, List<TreeEntry> tree, boolean truncated) {
    }

    public record TreeEntry(String path, String type, Long size) {
    }
}
