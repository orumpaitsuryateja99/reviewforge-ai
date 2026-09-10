package ai.reviewforge.analysis;

import ai.reviewforge.jobs.JobHandler;
import ai.reviewforge.jobs.JobType;
import ai.reviewforge.review.ReviewEngine;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Bridges the queue to {@link ReviewEngine} and keeps the analysis row honest about retries. */
@Component
public class AnalysisJobHandler implements JobHandler {

    private final ReviewEngine reviewEngine;
    private final AnalysisJobRepository repository;

    public AnalysisJobHandler(ReviewEngine reviewEngine, AnalysisJobRepository repository) {
        this.reviewEngine = reviewEngine;
        this.repository = repository;
    }

    @Override
    public JobType type() {
        return JobType.ANALYSIS;
    }

    @Override
    public void handle(UUID entityId) {
        repository.incrementAttempts(entityId);
        reviewEngine.run(entityId);
    }

    @Override
    public void onRetry(UUID entityId, int nextAttempt, String code, String message) {
        repository.requeue(entityId);
    }

    @Override
    public void onGiveUp(UUID entityId, String code, String message) {
        repository.markFailed(entityId, code, message);
    }
}
