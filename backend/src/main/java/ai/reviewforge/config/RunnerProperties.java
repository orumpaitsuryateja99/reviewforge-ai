package ai.reviewforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the isolated test runner. The runner is a separate trust
 * boundary: it receives a source snapshot, a patch, and a run policy, and never a
 * GitHub token, model key, or database credential.
 */
@ConfigurationProperties(prefix = "reviewforge.runner")
public record RunnerProperties(
        String url,
        boolean enabled,
        Duration timeout,
        int runTimeoutSeconds,
        long maxSnapshotBytes
) {

    public boolean configured() {
        return enabled && url != null && !url.isBlank();
    }
}
