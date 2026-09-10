package ai.reviewforge.github.auth;

import ai.reviewforge.common.api.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OAuthStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "reviewforge:oauth-state:";

    private final StringRedisTemplate redisTemplate;
    private final SecureTokenGenerator tokenGenerator;

    public OAuthStateStore(StringRedisTemplate redisTemplate, SecureTokenGenerator tokenGenerator) {
        this.redisTemplate = redisTemplate;
        this.tokenGenerator = tokenGenerator;
    }

    public String issue(String purpose, String subject) {
        String state = tokenGenerator.generate();
        redisTemplate.opsForValue().set(PREFIX + state, purpose + ":" + subject, STATE_TTL);
        return state;
    }

    public void consume(String state, String expectedPurpose, String expectedSubject) {
        if (state == null || state.isBlank()) {
            throw invalidState();
        }

        String value = redisTemplate.opsForValue().getAndDelete(PREFIX + state);
        String expected = expectedPurpose + ":" + expectedSubject;
        if (!expected.equals(value)) {
            throw invalidState();
        }
    }

    private ApiException invalidState() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OAUTH_STATE",
                "The GitHub authorization state is missing, expired, or has already been used.");
    }
}
