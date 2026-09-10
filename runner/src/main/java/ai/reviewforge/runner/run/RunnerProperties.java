package ai.reviewforge.runner.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hard limits on what a single execution may consume. */
@ConfigurationProperties(prefix = "runner")
public record RunnerProperties(
        String workspaceRoot,
        int maxRunSeconds,
        int defaultRunSeconds,
        int maxOutputBytes,
        long maxExtractedBytes,
        int maxExtractedEntries,
        String mavenCommand,
        String mavenRepositoryPath,
        String homeDirectory,
        boolean offline
) {
}
