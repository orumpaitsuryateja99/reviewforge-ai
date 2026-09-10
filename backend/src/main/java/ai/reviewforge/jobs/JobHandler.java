package ai.reviewforge.jobs;

import java.util.UUID;

/**
 * Executes one kind of job. The handler owns its entity's lifecycle columns; the worker owns
 * queueing, retries, and give-up policy.
 */
public interface JobHandler {

    JobType type();

    void handle(UUID entityId);

    /** Called before a retry is queued, so the entity can show why it is being retried. */
    void onRetry(UUID entityId, int nextAttempt, String code, String message);

    /** Called when retries are exhausted or the failure is permanent. */
    void onGiveUp(UUID entityId, String code, String message);
}
