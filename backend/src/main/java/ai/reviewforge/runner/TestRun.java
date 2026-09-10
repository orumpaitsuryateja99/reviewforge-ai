package ai.reviewforge.runner;

import java.time.Instant;
import java.util.UUID;

/** One execution of an approved generated test inside the isolated runner. */
public record TestRun(
        UUID id,
        UUID generatedTestId,
        String status,
        String commandProfile,
        Integer exitCode,
        Integer testsRun,
        Integer testsPassed,
        Integer testsFailed,
        Integer testsSkipped,
        String stdout,
        String stderr,
        boolean timedOut,
        Integer durationMillis,
        int attempts,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {

    public static final String QUEUED = "QUEUED";
    public static final String PREPARING = "PREPARING";
    public static final String RUNNING = "RUNNING";
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String TIMED_OUT = "TIMED_OUT";
    public static final String INFRASTRUCTURE_ERROR = "INFRASTRUCTURE_ERROR";
    public static final String CANCELLED = "CANCELLED";
}
