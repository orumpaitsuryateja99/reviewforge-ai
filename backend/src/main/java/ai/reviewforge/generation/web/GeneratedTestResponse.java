package ai.reviewforge.generation.web;

import ai.reviewforge.generation.GeneratedTest;

import java.time.Instant;
import java.util.UUID;

/** API shape for a proposed test. The stored source is returned so the UI can preview it. */
public record GeneratedTestResponse(
        UUID id,
        UUID findingId,
        UUID analysisId,
        String status,
        String targetFilePath,
        String unifiedDiff,
        String fileContent,
        String rationale,
        String headSha,
        String errorMessage,
        Instant approvedAt,
        Instant createdAt
) {

    public static GeneratedTestResponse from(GeneratedTest test) {
        return new GeneratedTestResponse(
                test.id(), test.findingId(), test.analysisJobId(), test.status(), test.targetFilePath(),
                test.unifiedDiff(), test.fileContent(), test.rationale(), test.headSha(),
                test.errorMessage(), test.approvedAt(), test.createdAt()
        );
    }
}
