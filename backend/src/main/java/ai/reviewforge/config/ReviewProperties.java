package ai.reviewforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bounds for the context builder. Every limit fails closed: context is trimmed and the
 * omission is reported rather than silently overflowing the model window.
 */
@ConfigurationProperties(prefix = "reviewforge.review")
public record ReviewProperties(
        List<String> reviewableExtensions,
        int maxFiles,
        int maxFileBytes,
        int maxContextBytes,
        int hunkContextLines,
        int maxFindings,
        double minConfidence
) {

    public boolean reviewable(String path) {
        String lower = path.toLowerCase();
        return reviewableExtensions.stream().anyMatch(lower::endsWith);
    }
}
