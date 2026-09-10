package ai.reviewforge.jobs;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.review.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JobWorkerTest {

    private static final JobProperties PROPERTIES = new JobProperties(
            true, 1, 3, Duration.ofMinutes(15), Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(2));

    private final JobQueue queue = mock(JobQueue.class);
    private final UUID entityId = UUID.randomUUID();

    @Test
    void acknowledgesWorkThatSucceeds() {
        RecordingHandler handler = new RecordingHandler(null);
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of(handler));

        worker.execute(claim(1));

        assertThat(handler.handled).containsExactly(entityId);
        verify(queue).acknowledge(any());
        verify(queue, never()).retry(any(), any());
    }

    @Test
    void retriesRetryableFailuresUntilTheAttemptBudgetIsSpent() {
        RecordingHandler handler = new RecordingHandler(
                new LlmException("MODEL_RATE_LIMITED", "Slow down.", true));
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of(handler));

        worker.execute(claim(1));

        verify(queue).retry(any(), eq(Duration.ofSeconds(10)));
        assertThat(handler.retried).containsExactly(2);
        assertThat(handler.gaveUp).isEmpty();
    }

    @Test
    void givesUpOnTheFinalAttempt() {
        RecordingHandler handler = new RecordingHandler(
                new LlmException("MODEL_UNAVAILABLE", "Upstream is down.", true));
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of(handler));

        worker.execute(claim(3));

        verify(queue, never()).retry(any(), any());
        verify(queue).acknowledge(any());
        assertThat(handler.gaveUp).containsExactly("MODEL_UNAVAILABLE");
    }

    @Test
    void doesNotRetryPermanentFailures() {
        RecordingHandler handler = new RecordingHandler(new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "GENERATED_TEST_REJECTED", "The proposal was refused."));
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of(handler));

        worker.execute(claim(1));

        verify(queue, never()).retry(any(), any());
        assertThat(handler.gaveUp).containsExactly("GENERATED_TEST_REJECTED");
    }

    @Test
    void backsOffExponentiallyUpToTheConfiguredCeiling() {
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of(new RecordingHandler(null)));

        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(worker.backoff(9)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void acknowledgesJobsWithNoRegisteredHandler() {
        JobWorker worker = new JobWorker(queue, PROPERTIES, List.of());

        worker.execute(claim(1));

        verify(queue).acknowledge(any());
    }

    private JobQueue.Claim claim(int attempt) {
        JobEnvelope envelope = new JobEnvelope(JobType.ANALYSIS, entityId, attempt, 0L);
        return new JobQueue.Claim("payload", envelope);
    }

    private static final class RecordingHandler implements JobHandler {

        private final RuntimeException failure;
        private final List<UUID> handled = new ArrayList<>();
        private final List<Integer> retried = new ArrayList<>();
        private final List<String> gaveUp = new ArrayList<>();

        private RecordingHandler(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public JobType type() {
            return JobType.ANALYSIS;
        }

        @Override
        public void handle(UUID entityId) {
            handled.add(entityId);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void onRetry(UUID entityId, int nextAttempt, String code, String message) {
            retried.add(nextAttempt);
        }

        @Override
        public void onGiveUp(UUID entityId, String code, String message) {
            gaveUp.add(code);
        }
    }
}
