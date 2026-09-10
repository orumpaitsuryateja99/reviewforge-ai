package ai.reviewforge.generation;

import ai.reviewforge.jobs.JobHandler;
import ai.reviewforge.jobs.JobType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TestGenerationJobHandler implements JobHandler {

    private final TestGenerationService generationService;
    private final GeneratedTestRepository repository;

    public TestGenerationJobHandler(TestGenerationService generationService, GeneratedTestRepository repository) {
        this.generationService = generationService;
        this.repository = repository;
    }

    @Override
    public JobType type() {
        return JobType.TEST_GENERATION;
    }

    @Override
    public void handle(UUID entityId) {
        generationService.generate(entityId);
    }

    @Override
    public void onRetry(UUID entityId, int nextAttempt, String code, String message) {
        // The row stays GENERATING; the queue owns the next attempt.
    }

    @Override
    public void onGiveUp(UUID entityId, String code, String message) {
        repository.markFailed(entityId, code + ": " + message);
    }
}
