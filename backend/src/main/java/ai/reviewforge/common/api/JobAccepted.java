package ai.reviewforge.common.api;

import java.util.UUID;

/** Response for accepted asynchronous work: the caller polls {@code statusUrl}. */
public record JobAccepted(UUID jobId, String status, String statusUrl) {

    public static JobAccepted of(UUID jobId, String resourcePath) {
        return new JobAccepted(jobId, "QUEUED", resourcePath);
    }
}
