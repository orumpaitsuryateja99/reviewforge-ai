package ai.reviewforge.github.persistence;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.github.client.GitHubApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class PullRequestRepository {

    private final JdbcClient jdbcClient;

    public PullRequestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public PullRequestRecord upsert(UUID repositoryId, GitHubApiClient.PullRequest pullRequest) {
        PullRequestRecord result = jdbcClient.sql("""
                        INSERT INTO pull_requests (
                            id, repository_id, github_pull_request_id, number, title, author_login,
                            state, base_ref, base_sha, head_ref, head_sha, github_updated_at
                        ) VALUES (
                            :id, :repositoryId, :githubId, :number, :title, :author,
                            :state, :baseRef, :baseSha, :headRef, :headSha, :githubUpdatedAt
                        )
                        ON CONFLICT (repository_id, number) DO UPDATE SET
                            github_pull_request_id = EXCLUDED.github_pull_request_id,
                            title = EXCLUDED.title,
                            author_login = EXCLUDED.author_login,
                            state = EXCLUDED.state,
                            base_ref = EXCLUDED.base_ref,
                            base_sha = EXCLUDED.base_sha,
                            head_ref = EXCLUDED.head_ref,
                            head_sha = EXCLUDED.head_sha,
                            github_updated_at = EXCLUDED.github_updated_at,
                            updated_at = NOW()
                        RETURNING id, repository_id, github_pull_request_id, number, title, author_login,
                                  state, base_ref, base_sha, head_ref, head_sha, github_updated_at
                        """)
                .param("id", UUID.randomUUID())
                .param("repositoryId", repositoryId)
                .param("githubId", pullRequest.id())
                .param("number", pullRequest.number())
                .param("title", pullRequest.title())
                .param("author", pullRequest.user().login())
                .param("state", state(pullRequest))
                .param("baseRef", pullRequest.base().ref())
                .param("baseSha", pullRequest.base().sha())
                .param("headRef", pullRequest.head().ref())
                .param("headSha", pullRequest.head().sha())
                .param("githubUpdatedAt", Timestamp.from(pullRequest.updatedAt()))
                .query(this::map)
                .single();

        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = 'STALE', updated_at = NOW()
                        WHERE pull_request_id = :pullRequestId
                          AND head_sha <> :headSha
                          AND status = 'COMPLETED'
                        """)
                .param("pullRequestId", result.id())
                .param("headSha", result.headSha())
                .update();
        return result;
    }

    public PullRequestRecord requireForUser(UUID pullRequestId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT pr.id, pr.repository_id, pr.github_pull_request_id, pr.number, pr.title,
                               pr.author_login, pr.state, pr.base_ref, pr.base_sha, pr.head_ref,
                               pr.head_sha, pr.github_updated_at
                        FROM pull_requests pr
                        JOIN repositories r ON r.id = pr.repository_id
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE pr.id = :pullRequestId AND i.installed_by_user_id = :userId
                        """)
                .param("pullRequestId", pullRequestId)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PULL_REQUEST_NOT_FOUND",
                        "The pull request does not exist or is not available to this user."));
    }

    private String state(GitHubApiClient.PullRequest pullRequest) {
        if (pullRequest.mergedAt() != null) {
            return "MERGED";
        }
        return pullRequest.state().toUpperCase();
    }

    private PullRequestRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PullRequestRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getLong("github_pull_request_id"),
                rs.getInt("number"),
                rs.getString("title"),
                rs.getString("author_login"),
                rs.getString("state"),
                rs.getString("base_ref"),
                rs.getString("base_sha").trim(),
                rs.getString("head_ref"),
                rs.getString("head_sha").trim(),
                rs.getTimestamp("github_updated_at").toInstant()
        );
    }

    public record PullRequestRecord(
            UUID id,
            UUID repositoryId,
            long githubPullRequestId,
            int number,
            String title,
            String author,
            String state,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            java.time.Instant updatedAt
    ) {
    }
}
