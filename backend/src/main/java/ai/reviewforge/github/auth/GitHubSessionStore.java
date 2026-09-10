package ai.reviewforge.github.auth;

import ai.reviewforge.config.GitHubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class GitHubSessionStore {

    public static final String COOKIE_NAME = "reviewforge_session";
    private static final String PREFIX = "reviewforge:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureTokenGenerator tokenGenerator;
    private final GitHubProperties properties;
    private final Clock clock;

    public GitHubSessionStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SecureTokenGenerator tokenGenerator,
            GitHubProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    public CreatedSession create(GitHubSession session) {
        String id = tokenGenerator.generate();
        Instant configuredExpiry = clock.instant().plus(properties.sessionTtl());
        Instant expiry = session.expiresAt() != null && session.expiresAt().isBefore(configuredExpiry)
                ? session.expiresAt()
                : configuredExpiry;
        Duration ttl = Duration.between(clock.instant(), expiry);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("Cannot create an already expired GitHub session");
        }
        GitHubSession expiringSession = new GitHubSession(
                session.userId(), session.login(), session.avatarUrl(), session.userAccessToken(),
                expiry
        );
        try {
            redisTemplate.opsForValue().set(
                    PREFIX + id,
                    objectMapper.writeValueAsString(expiringSession),
                    ttl
            );
            return new CreatedSession(id, expiringSession);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize authenticated session", exception);
        }
    }

    public Optional<GitHubSession> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(PREFIX + id);
        if (json == null) {
            return Optional.empty();
        }
        try {
            GitHubSession session = objectMapper.readValue(json, GitHubSession.class);
            if (session.expiresAt().isBefore(clock.instant())) {
                delete(id);
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (JsonProcessingException exception) {
            delete(id);
            return Optional.empty();
        }
    }

    public void delete(String id) {
        if (id != null && !id.isBlank()) {
            redisTemplate.delete(PREFIX + id);
        }
    }

    public record CreatedSession(String id, GitHubSession session) {
    }
}
