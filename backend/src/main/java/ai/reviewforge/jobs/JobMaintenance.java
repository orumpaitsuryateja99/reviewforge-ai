package ai.reviewforge.jobs;

import ai.reviewforge.config.JobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Periodic queue upkeep: promote delayed retries, and recover jobs whose worker vanished
 * mid-run so a crash costs a retry rather than a stuck pull request.
 */
@Component
public class JobMaintenance {

    private static final Logger log = LoggerFactory.getLogger(JobMaintenance.class);

    private final JobQueue queue;
    private final JobProperties properties;
    private final Map<JobType, JobHandler> handlers;

    public JobMaintenance(JobQueue queue, JobProperties properties, List<JobHandler> handlers) {
        this.queue = queue;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${reviewforge.jobs.maintenance-interval:10s}")
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        int promoted = queue.promoteDueRetries();
        if (promoted > 0) {
            log.debug("Promoted {} delayed job(s)", promoted);
        }

        for (JobQueue.Claim claim : queue.reclaimExpired()) {
            JobHandler handler = handlers.get(claim.envelope().type());
            if (handler == null) {
                queue.acknowledge(claim);
                continue;
            }
            int attempt = claim.envelope().attempt();
            if (attempt < properties.maxAttempts()) {
                handler.onRetry(claim.envelope().entityId(), attempt + 1, "LEASE_EXPIRED",
                        "The worker running this job stopped reporting.");
                queue.retry(claim, properties.retryBackoff());
                log.warn("Recovered abandoned job {} {}", claim.envelope().type(), claim.envelope().entityId());
            } else {
                handler.onGiveUp(claim.envelope().entityId(), "LEASE_EXPIRED",
                        "The job was abandoned after the maximum number of attempts.");
                queue.acknowledge(claim);
            }
        }
    }
}
