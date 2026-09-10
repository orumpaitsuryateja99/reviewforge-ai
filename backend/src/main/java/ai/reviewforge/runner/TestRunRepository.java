package ai.reviewforge.runner;

import ai.reviewforge.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TestRunRepository {

    private static final String COLUMNS = """
            t.id, t.generated_test_id, t.status, t.command_profile, t.exit_code, t.tests_run, t.tests_passed,
            t.tests_failed, t.tests_skipped, t.stdout, t.stderr, t.timed_out, t.duration_millis,
            t.attempts, t.started_at, t.completed_at, t.created_at
            """;

    private final JdbcClient jdbcClient;

    public TestRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public TestRun create(UUID generatedTestId, String commandProfile) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO test_runs (id, generated_test_id, status, command_profile)
                        VALUES (:id, :generatedTestId, 'QUEUED', :commandProfile)
                        """)
                .param("id", id)
                .param("generatedTestId", generatedTestId)
                .param("commandProfile", commandProfile)
                .update();
        return require(id);
    }

    public void markRunning(UUID id, String status, Instant leaseExpiresAt) {
        jdbcClient.sql("""
                        UPDATE test_runs
                        SET status = :status,
                            attempts = attempts + 1,
                            started_at = COALESCE(started_at, NOW()),
                            lease_expires_at = :lease,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("lease", Timestamp.from(leaseExpiresAt))
                .update();
    }

    public void markFinished(UUID id, RunnerClient.RunnerResult result) {
        jdbcClient.sql("""
                        UPDATE test_runs
                        SET status = :status,
                            exit_code = :exitCode,
                            tests_run = :testsRun,
                            tests_passed = :testsPassed,
                            tests_failed = :testsFailed,
                            tests_skipped = :testsSkipped,
                            stdout = :stdout,
                            stderr = :stderr,
                            timed_out = :timedOut,
                            duration_millis = :durationMillis,
                            completed_at = NOW(),
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", result.status())
                .param("exitCode", result.exitCode())
                .param("testsRun", result.testsRun())
                .param("testsPassed", result.testsPassed())
                .param("testsFailed", result.testsFailed())
                .param("testsSkipped", result.testsSkipped())
                .param("stdout", result.stdout())
                .param("stderr", result.stderr())
                .param("timedOut", result.timedOut())
                .param("durationMillis", (int) Math.min(result.durationMillis(), Integer.MAX_VALUE))
                .update();
    }

    public void markTerminal(UUID id, String status, String detail) {
        jdbcClient.sql("""
                        UPDATE test_runs
                        SET status = :status,
                            stderr = :detail,
                            completed_at = NOW(),
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("detail", detail)
                .update();
    }

    public TestRun require(UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM test_runs t WHERE t.id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(this::notFound);
    }

    public TestRun requireForUser(UUID id, UUID userId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM test_runs t
                        JOIN generated_tests g ON g.id = t.generated_test_id
                        JOIN findings f ON f.id = g.finding_id
                        JOIN analysis_jobs a ON a.id = f.analysis_job_id
                        JOIN pull_requests pr ON pr.id = a.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE t.id = :id AND i.installed_by_user_id = :userId
                        """)
                .param("id", id)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(this::notFound);
    }

    public Optional<TestRun> findActiveForGeneratedTest(UUID generatedTestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM test_runs t
                        WHERE t.generated_test_id = :generatedTestId
                          AND t.status IN ('QUEUED', 'PREPARING', 'RUNNING')
                        ORDER BY t.created_at DESC
                        LIMIT 1
                        """)
                .param("generatedTestId", generatedTestId)
                .query(this::map)
                .optional();
    }

    public List<TestRun> findByGeneratedTest(UUID generatedTestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM test_runs t
                        WHERE t.generated_test_id = :generatedTestId
                        ORDER BY t.created_at DESC
                        """)
                .param("generatedTestId", generatedTestId)
                .query(this::map)
                .list();
    }

    public List<TestRun> findByAnalysis(UUID analysisJobId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM test_runs t
                        JOIN generated_tests g ON g.id = t.generated_test_id
                        WHERE g.analysis_job_id = :analysisJobId
                        ORDER BY t.created_at
                        """)
                .param("analysisJobId", analysisJobId)
                .query(this::map)
                .list();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TEST_RUN_NOT_FOUND",
                "The test run does not exist or is not available to this user.");
    }

    private TestRun map(ResultSet rs, int rowNum) throws SQLException {
        return new TestRun(
                rs.getObject("id", UUID.class),
                rs.getObject("generated_test_id", UUID.class),
                rs.getString("status"),
                rs.getString("command_profile"),
                (Integer) rs.getObject("exit_code"),
                (Integer) rs.getObject("tests_run"),
                (Integer) rs.getObject("tests_passed"),
                (Integer) rs.getObject("tests_failed"),
                (Integer) rs.getObject("tests_skipped"),
                rs.getString("stdout"),
                rs.getString("stderr"),
                rs.getBoolean("timed_out"),
                (Integer) rs.getObject("duration_millis"),
                rs.getInt("attempts"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
