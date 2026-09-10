package ai.reviewforge.github.persistence;

import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class GitHubInstallationRepository {

    private final JdbcClient jdbcClient;

    public GitHubInstallationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public InstallationRecord upsert(UUID userId, GitHubApiClient.Installation installation) {
        return jdbcClient.sql("""
                        INSERT INTO github_installations (
                            id, installation_id, account_id, account_login, account_type,
                            installed_by_user_id, suspended_at
                        ) VALUES (
                            :id, :installationId, :accountId, :accountLogin, :accountType,
                            :userId, :suspendedAt
                        )
                        ON CONFLICT (installation_id) DO UPDATE SET
                            account_id = EXCLUDED.account_id,
                            account_login = EXCLUDED.account_login,
                            account_type = EXCLUDED.account_type,
                            installed_by_user_id = EXCLUDED.installed_by_user_id,
                            suspended_at = EXCLUDED.suspended_at,
                            updated_at = NOW()
                        RETURNING id, installation_id, installed_by_user_id, account_login, suspended_at
                        """)
                .param("id", UUID.randomUUID())
                .param("installationId", installation.id())
                .param("accountId", installation.account().id())
                .param("accountLogin", installation.account().login())
                .param("accountType", installation.account().type().toUpperCase())
                .param("userId", userId)
                .param("suspendedAt", installation.suspendedAt() == null ? null : Timestamp.from(installation.suspendedAt()))
                .query((rs, rowNum) -> new InstallationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getLong("installation_id"),
                        rs.getObject("installed_by_user_id", UUID.class),
                        rs.getString("account_login"),
                        rs.getTimestamp("suspended_at") == null ? null : rs.getTimestamp("suspended_at").toInstant()
                ))
                .single();
    }

    public List<InstallationRecord> findActiveByUser(UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, installation_id, installed_by_user_id, account_login, suspended_at
                        FROM github_installations
                        WHERE installed_by_user_id = :userId AND suspended_at IS NULL
                        ORDER BY account_login
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new InstallationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getLong("installation_id"),
                        rs.getObject("installed_by_user_id", UUID.class),
                        rs.getString("account_login"),
                        null
                ))
                .list();
    }

    public InstallationRecord requireActive(UUID id, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, installation_id, installed_by_user_id, account_login, suspended_at
                        FROM github_installations
                        WHERE id = :id AND installed_by_user_id = :userId AND suspended_at IS NULL
                        """)
                .param("id", id)
                .param("userId", userId)
                .query((rs, rowNum) -> new InstallationRecord(
                        rs.getObject("id", UUID.class),
                        rs.getLong("installation_id"),
                        rs.getObject("installed_by_user_id", UUID.class),
                        rs.getString("account_login"),
                        null
                ))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND",
                        "The GitHub App installation is no longer available."));
    }

    public record InstallationRecord(
            UUID id,
            long installationId,
            UUID installedByUserId,
            String accountLogin,
            java.time.Instant suspendedAt
    ) {
    }
}
