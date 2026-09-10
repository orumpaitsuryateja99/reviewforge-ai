package ai.reviewforge.github.persistence;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.github.client.GitHubApiClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class GitHubRepositoryRepository {

    private final JdbcClient jdbcClient;

    public GitHubRepositoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public RepositoryRecord upsert(UUID installationId, GitHubApiClient.GitHubRepository repository) {
        return jdbcClient.sql("""
                        INSERT INTO repositories (
                            id, github_installation_id, github_repository_id, owner_login, name,
                            full_name, default_branch, private, archived
                        ) VALUES (
                            :id, :installationId, :githubRepositoryId, :owner, :name,
                            :fullName, :defaultBranch, :privateRepository, :archived
                        )
                        ON CONFLICT (github_repository_id) DO UPDATE SET
                            github_installation_id = EXCLUDED.github_installation_id,
                            owner_login = EXCLUDED.owner_login,
                            name = EXCLUDED.name,
                            full_name = EXCLUDED.full_name,
                            default_branch = EXCLUDED.default_branch,
                            private = EXCLUDED.private,
                            archived = EXCLUDED.archived,
                            updated_at = NOW()
                        RETURNING id, github_installation_id, github_repository_id, owner_login, name,
                                  full_name, default_branch, private, test_execution_allowed, archived
                        """)
                .param("id", UUID.randomUUID())
                .param("installationId", installationId)
                .param("githubRepositoryId", repository.id())
                .param("owner", repository.owner().login())
                .param("name", repository.name())
                .param("fullName", repository.fullName())
                .param("defaultBranch", repository.defaultBranch())
                .param("privateRepository", repository.privateRepository())
                .param("archived", repository.archived())
                .query(this::map)
                .single();
    }

    public List<RepositoryRecord> findByUser(UUID userId) {
        return jdbcClient.sql("""
                        SELECT r.id, r.github_installation_id, r.github_repository_id, r.owner_login,
                               r.name, r.full_name, r.default_branch, r.private,
                               r.test_execution_allowed, r.archived
                        FROM repositories r
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE i.installed_by_user_id = :userId
                          AND i.suspended_at IS NULL
                          AND r.archived = FALSE
                        ORDER BY r.full_name
                        """)
                .param("userId", userId)
                .query(this::map)
                .list();
    }

    public RepositoryRecord requireForUser(UUID repositoryId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT r.id, r.github_installation_id, r.github_repository_id, r.owner_login,
                               r.name, r.full_name, r.default_branch, r.private,
                               r.test_execution_allowed, r.archived
                        FROM repositories r
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE r.id = :repositoryId
                          AND i.installed_by_user_id = :userId
                          AND i.suspended_at IS NULL
                        """)
                .param("repositoryId", repositoryId)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND",
                        "The repository does not exist or is not available to this user."));
    }

    public RepositoryRecord setTestExecutionAllowed(UUID repositoryId, boolean allowed) {
        return jdbcClient.sql("""
                        UPDATE repositories
                        SET test_execution_allowed = :allowed, updated_at = NOW()
                        WHERE id = :repositoryId
                        RETURNING id, github_installation_id, github_repository_id, owner_login, name,
                                  full_name, default_branch, private, test_execution_allowed, archived
                        """)
                .param("repositoryId", repositoryId)
                .param("allowed", allowed)
                .query(this::map)
                .single();
    }

    public void archiveMissing(UUID installationId, List<Long> activeGitHubIds) {
        if (activeGitHubIds.isEmpty()) {
            jdbcClient.sql("""
                            UPDATE repositories SET archived = TRUE, updated_at = NOW()
                            WHERE github_installation_id = :installationId
                            """)
                    .param("installationId", installationId)
                    .update();
            return;
        }
        jdbcClient.sql("""
                        UPDATE repositories SET archived = TRUE, updated_at = NOW()
                        WHERE github_installation_id = :installationId
                          AND github_repository_id NOT IN (:activeIds)
                        """)
                .param("installationId", installationId)
                .param("activeIds", activeGitHubIds)
                .update();
    }

    private RepositoryRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RepositoryRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("github_installation_id", UUID.class),
                rs.getLong("github_repository_id"),
                rs.getString("owner_login"),
                rs.getString("name"),
                rs.getString("full_name"),
                rs.getString("default_branch"),
                rs.getBoolean("private"),
                rs.getBoolean("test_execution_allowed"),
                rs.getBoolean("archived")
        );
    }

    public record RepositoryRecord(
            UUID id,
            UUID installationId,
            long githubRepositoryId,
            String owner,
            String name,
            String fullName,
            String defaultBranch,
            boolean privateRepository,
            boolean testExecutionAllowed,
            boolean archived
    ) {
    }
}
