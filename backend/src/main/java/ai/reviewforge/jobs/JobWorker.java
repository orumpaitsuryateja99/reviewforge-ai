package ai.reviewforge.jobs;

import ai.reviewforge.config.JobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pool of queue consumers. Each worker claims one job, runs its handler, and either
 * acknowledges it or schedules a backed-off retry until the attempt budget is spent.
 */
@Component
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final JobProperties properties;
    private final Map<JobType, JobHandler> handlers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService executor;

    public JobWorker(JobQueue queue, JobProperties properties, List<JobHandler> handlers) {
        this.queue = queue;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            log.info("Job workers are disabled on this instance");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newFixedThreadPool(properties.workers(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("reviewforge-job-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int worker = 0; worker < properties.workers(); worker++) {
            executor.submit(this::consume);
        }
        log.info("Started {} job workers", properties.workers());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Job workers did not stop within the shutdown window");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void consume() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<JobQueue.Claim> claim = queue.claim();
                claim.ifPresent(this::execute);
            } catch (RuntimeException exception) {
                if (running.get()) {
                    log.warn("Job worker loop error: {}", exception.toString());
                    sleepBriefly();
                }
            }
        }
    }

    void execute(JobQueue.Claim claim) {
        JobHandler handler = handlers.get(claim.envelope().type());
        if (handler == null) {
            log.error("No handler registered for job type {}", claim.envelope().type());
            queue.acknowledge(claim);
            return;
        }

        try {
            handler.handle(claim.envelope().entityId());
            queue.acknowledge(claim);
        } catch (RuntimeException exception) {
            JobFailure failure = JobFailure.of(exception);
            int attempt = claim.envelope().attempt();
            if (failure.retryable() && attempt < properties.maxAttempts()) {
                Duration delay = backoff(attempt);
                handler.onRetry(claim.envelope().entityId(), attempt + 1, failure.code(), failure.message());
                queue.retry(claim, delay);
                log.warn("Job {} {} failed ({}); retrying in {}s",
                        claim.envelope().type(), claim.envelope().entityId(), failure.code(), delay.toSeconds());
            } else {
                handler.onGiveUp(claim.envelope().entityId(), failure.code(), failure.message());
                queue.acknowledge(claim);
                log.error("Job {} {} failed permanently: {} - {}",
                        claim.envelope().type(), claim.envelope().entityId(), failure.code(), failure.message());
            }
        }
    }

    Duration backoff(int attempt) {
        long millis = properties.retryBackoff().toMillis() * (1L << Math.min(attempt - 1, 16));
        return Duration.ofMillis(Math.min(millis, properties.maxRetryBackoff().toMillis()));
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
