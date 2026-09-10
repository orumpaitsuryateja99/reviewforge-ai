package ai.reviewforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis-backed queue policy. {@code lease} bounds how long a claimed job may run before the
 * reaper treats the worker as lost and requeues the work. Setting {@code enabled} to false
 * leaves an instance serving the API without consuming the queue.
 */
@ConfigurationProperties(prefix = "reviewforge.jobs")
public record JobProperties(
        boolean enabled,
        int workers,
        int maxAttempts,
        Duration lease,
        Duration retryBackoff,
        Duration maxRetryBackoff,
        Duration pollTimeout
) {
}
