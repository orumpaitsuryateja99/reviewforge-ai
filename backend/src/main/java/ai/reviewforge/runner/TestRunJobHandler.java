package ai.reviewforge.runner;

import ai.reviewforge.jobs.JobHandler;
import ai.reviewforge.jobs.JobType;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TestRunJobHandler implements JobHandler {

    private final TestRunService testRunService;
    private final TestRunRepository repository;

    public TestRunJobHandler(TestRunService testRunService, TestRunRepository repository) {
        this.testRunService = testRunService;
        this.repository = repository;
    }

    @Override
    public JobType type() {
        return JobType.TEST_RUN;
    }

    @Override
    public void handle(UUID entityId) {
        testRunService.execute(entityId);
    }

    @Override
    public void onRetry(UUID entityId, int nextAttempt, String code, String message) {
        // The row keeps its RUNNING/PREPARING state; the queue owns the next attempt.
    }

    @Override
    public void onGiveUp(UUID entityId, String code, String message) {
        repository.markTerminal(entityId, TestRun.INFRASTRUCTURE_ERROR, code + ": " + message);
    }
}
