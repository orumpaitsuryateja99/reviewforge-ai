package ai.reviewforge.github.client;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@Service
public class InstallationTokenService {

    private static final String PREFIX = "reviewforge:installation-token:";

    private final StringRedisTemplate redisTemplate;
    private final GitHubApiClient apiClient;
    private final Clock clock;

    public InstallationTokenService(StringRedisTemplate redisTemplate, GitHubApiClient apiClient, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.apiClient = apiClient;
        this.clock = clock;
    }

    public String get(long installationId) {
        String key = PREFIX + installationId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }

        GitHubApiClient.InstallationAccessToken token = apiClient.createInstallationToken(installationId);
        Duration ttl = Duration.between(clock.instant(), token.expiresAt()).minusMinutes(1);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(1);
        }
        redisTemplate.opsForValue().set(key, token.token(), ttl);
        return token.token();
    }
}
