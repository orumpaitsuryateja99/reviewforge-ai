package ai.reviewforge.generation;

import java.time.Instant;
import java.util.UUID;

/** A proposed test patch and its approval state. Approval never writes to the repository. */
public record GeneratedTest(
        UUID id,
        UUID findingId,
        UUID analysisJobId,
        String status,
        String targetFilePath,
        String unifiedDiff,
        String fileContent,
        String rationale,
        String headSha,
        UUID approvedByUserId,
        Instant approvedAt,
        String errorMessage,
        int attempts,
        Instant createdAt
) {

    public static final String GENERATING = "GENERATING";
    public static final String PROPOSED = "PROPOSED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    public static final String STALE = "STALE";
}
