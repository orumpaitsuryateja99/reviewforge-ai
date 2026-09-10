package ai.reviewforge.jobs;

import java.util.UUID;

/** Queue payload. The entity row, not the envelope, is the source of truth for state. */
public record JobEnvelope(JobType type, UUID entityId, int attempt, long enqueuedAtEpochMillis) {

    public JobEnvelope nextAttempt(long nowEpochMillis) {
        return new JobEnvelope(type, entityId, attempt + 1, nowEpochMillis);
    }
}
