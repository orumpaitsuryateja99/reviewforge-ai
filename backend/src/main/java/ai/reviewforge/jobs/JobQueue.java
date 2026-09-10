package ai.reviewforge.jobs;

import ai.reviewforge.config.JobProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redis work queue with an at-least-once handoff: claiming moves the payload from the ready
 * list into a processing list and records a lease, so a worker that dies leaves recoverable
 * work rather than a lost job.
 */
@Component
public class JobQueue {

    private static final String READY = "reviewforge:jobs:ready";
    private static final String PROCESSING = "reviewforge:jobs:processing";
    private static final String DELAYED = "reviewforge:jobs:delayed";
    private static final String LEASES = "reviewforge:jobs:leases";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JobProperties properties;
    private final Clock clock;

    public JobQueue(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            JobProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public void enqueue(JobType type, UUID entityId) {
        enqueue(new JobEnvelope(type, entityId, 1, clock.millis()));
    }

    public void enqueue(JobEnvelope envelope) {
        redisTemplate.opsForList().leftPush(READY, serialize(envelope));
    }

    public void enqueueAfter(JobEnvelope envelope, Duration delay) {
        redisTemplate.opsForZSet().add(DELAYED, serialize(envelope), clock.millis() + delay.toMillis());
    }

    /** Blocks up to {@code pollTimeout} for work. The returned claim must be acknowledged or released. */
    public Optional<Claim> claim() {
        String payload = redisTemplate.opsForList()
                .move(READY, Direction.RIGHT, PROCESSING, Direction.LEFT, properties.pollTimeout());
        if (payload == null) {
            return Optional.empty();
        }
        redisTemplate.opsForHash().put(LEASES, payload, String.valueOf(clock.millis()));
        return deserialize(payload).map(envelope -> new Claim(payload, envelope));
    }

    public void acknowledge(Claim claim) {
        redisTemplate.opsForList().remove(PROCESSING, 1, claim.payload());
        redisTemplate.opsForHash().delete(LEASES, claim.payload());
    }

    public void retry(Claim claim, Duration delay) {
        acknowledge(claim);
        enqueueAfter(claim.envelope().nextAttempt(clock.millis()), delay);
    }

    /** Moves delayed retries whose time has come back onto the ready list. */
    public int promoteDueRetries() {
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(DELAYED, 0, clock.millis());
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (String payload : due) {
            Long removed = redisTemplate.opsForZSet().remove(DELAYED, payload);
            if (removed != null && removed > 0) {
                redisTemplate.opsForList().leftPush(READY, payload);
                promoted++;
            }
        }
        return promoted;
    }

    /** Returns claims whose lease expired, so the caller can decide between retry and failure. */
    public List<Claim> reclaimExpired() {
        List<String> processing = redisTemplate.opsForList().range(PROCESSING, 0, -1);
        if (processing == null || processing.isEmpty()) {
            return List.of();
        }
        long cutoff = clock.millis() - properties.lease().toMillis();
        List<Claim> expired = new ArrayList<>();
        for (String payload : processing) {
            Object leasedAt = redisTemplate.opsForHash().get(LEASES, payload);
            long claimedAt = leasedAt == null ? 0L : Long.parseLong(leasedAt.toString());
            if (claimedAt < cutoff) {
                deserialize(payload).ifPresent(envelope -> expired.add(new Claim(payload, envelope)));
            }
        }
        return expired;
    }

    public long readyDepth() {
        Long size = redisTemplate.opsForList().size(READY);
        return size == null ? 0 : size;
    }

    private String serialize(JobEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize job envelope", exception);
        }
    }

    private Optional<JobEnvelope> deserialize(String payload) {
        try {
            return Optional.of(objectMapper.readValue(payload, JobEnvelope.class));
        } catch (JsonProcessingException exception) {
            // An unreadable payload can never run; drop it rather than blocking the queue.
            redisTemplate.opsForList().remove(PROCESSING, 1, payload);
            redisTemplate.opsForHash().delete(LEASES, payload);
            return Optional.empty();
        }
    }

    public record Claim(String payload, JobEnvelope envelope) {
    }
}
