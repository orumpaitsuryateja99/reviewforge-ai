package ai.reviewforge.findings;

import java.time.Instant;
import java.util.UUID;

/** A finding that survived validation and is stored against one immutable analysis. */
public record Finding(
        UUID id,
        UUID analysisJobId,
        int ordinal,
        String category,
        String severity,
        String title,
        String explanation,
        String filePath,
        int startLine,
        int endLine,
        String evidence,
        String failureScenario,
        String suggestedFix,
        double confidence,
        Instant createdAt
) {
}
