package ai.reviewforge.analysis;

import java.time.Instant;
import java.util.UUID;

/** API projection of an analysis, including whether its commit is still the current head. */
public record AnalysisView(
        UUID id,
        UUID pullRequestId,
        String headSha,
        String currentHeadSha,
        String status,
        int progressPercent,
        boolean stale,
        int findingCount,
        int contextFileCount,
        String llmProvider,
        String llmModel,
        JobError error,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    public record JobError(String code, String message) {
    }

    public static AnalysisView of(AnalysisJob job, String currentHeadSha, int findingCount) {
        boolean stale = AnalysisJob.STALE.equals(job.status()) || !job.headSha().equals(currentHeadSha);
        return new AnalysisView(
                job.id(), job.pullRequestId(), job.headSha(), currentHeadSha, job.status(), job.progressPercent(),
                stale, findingCount, job.contextFileCount(), job.llmProvider(), job.llmModel(),
                job.errorCode() == null ? null : new JobError(job.errorCode(), job.errorMessage()),
                job.createdAt(), job.startedAt(), job.completedAt()
        );
    }
}
