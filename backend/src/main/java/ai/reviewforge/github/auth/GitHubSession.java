package ai.reviewforge.github.auth;

import java.time.Instant;
import java.util.UUID;

public record GitHubSession(
        UUID userId,
        String login,
        String avatarUrl,
        String userAccessToken,
        Instant expiresAt
) {
}
