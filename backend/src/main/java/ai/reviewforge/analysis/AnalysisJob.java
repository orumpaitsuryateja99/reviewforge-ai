package ai.reviewforge.analysis;

import java.time.Instant;
import java.util.UUID;

/**
 * One review of one immutable commit. {@code headSha} never changes after creation: a new
 * head means a new analysis, and the previous one becomes {@code STALE}.
 */
public record AnalysisJob(
        UUID id,
        UUID pullRequestId,
        UUID requestedByUserId,
        String headSha,
        String status,
        int progressPercent,
        String llmProvider,
        String llmModel,
        String errorCode,
        String errorMessage,
        int attempts,
        int contextFileCount,
        Integer inputTokens,
        Integer outputTokens,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {

    public static final String QUEUED = "QUEUED";
    public static final String BUILDING_CONTEXT = "BUILDING_CONTEXT";
    public static final String ANALYZING = "ANALYZING";
    public static final String VALIDATING = "VALIDATING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String STALE = "STALE";
    public static final String CANCELLED = "CANCELLED";

    public boolean terminal() {
        return COMPLETED.equals(status) || FAILED.equals(status)
                || STALE.equals(status) || CANCELLED.equals(status);
    }
}
