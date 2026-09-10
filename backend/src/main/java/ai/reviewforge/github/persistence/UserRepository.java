package ai.reviewforge.github.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID upsert(long githubUserId, String login, String displayName, String avatarUrl) {
        return jdbcClient.sql("""
                        INSERT INTO app_users (id, github_user_id, github_login, display_name, avatar_url)
                        VALUES (:id, :githubUserId, :login, :displayName, :avatarUrl)
                        ON CONFLICT (github_user_id) DO UPDATE SET
                            github_login = EXCLUDED.github_login,
                            display_name = EXCLUDED.display_name,
                            avatar_url = EXCLUDED.avatar_url,
                            updated_at = NOW()
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("githubUserId", githubUserId)
                .param("login", login)
                .param("displayName", displayName)
                .param("avatarUrl", avatarUrl)
                .query(UUID.class)
                .single();
    }
}
