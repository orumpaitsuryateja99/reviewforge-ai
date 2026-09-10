package ai.reviewforge.runner.run;

/** Bounded execution outcome. Output is truncated rather than streamed back in full. */
public record RunResult(
        String status,
        String commandProfile,
        int exitCode,
        int testsRun,
        int testsPassed,
        int testsFailed,
        int testsSkipped,
        String stdout,
        String stderr,
        boolean timedOut,
        long durationMillis
) {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String TIMED_OUT = "TIMED_OUT";
    public static final String INFRASTRUCTURE_ERROR = "INFRASTRUCTURE_ERROR";
}
