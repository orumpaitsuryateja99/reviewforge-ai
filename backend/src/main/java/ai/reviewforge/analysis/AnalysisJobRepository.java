package ai.reviewforge.analysis;

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
public class AnalysisJobRepository {

    private static final String COLUMNS = """
            a.id, a.pull_request_id, a.requested_by_user_id, a.head_sha, a.status, a.progress_percent,
            a.llm_provider, a.llm_model, a.error_code, a.error_message, a.attempts, a.context_file_count,
            a.input_tokens, a.output_tokens, a.started_at, a.completed_at, a.created_at
            """;

    private final JdbcClient jdbcClient;

    public AnalysisJobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public AnalysisJob create(UUID pullRequestId, UUID userId, String headSha) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO analysis_jobs (id, pull_request_id, requested_by_user_id, head_sha, status)
                        VALUES (:id, :pullRequestId, :userId, :headSha, 'QUEUED')
                        """)
                .param("id", id)
                .param("pullRequestId", pullRequestId)
                .param("userId", userId)
                .param("headSha", headSha)
                .update();
        return require(id);
    }

    public Optional<AnalysisJob> findActiveForPullRequest(UUID pullRequestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM analysis_jobs a
                        WHERE a.pull_request_id = :pullRequestId
                          AND a.status IN ('QUEUED', 'BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING')
                        """)
                .param("pullRequestId", pullRequestId)
                .query(this::map)
                .optional();
    }

    public List<AnalysisJob> findByPullRequest(UUID pullRequestId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM analysis_jobs a
                        WHERE a.pull_request_id = :pullRequestId
                        ORDER BY a.created_at DESC
                        """)
                .param("pullRequestId", pullRequestId)
                .query(this::map)
                .list();
    }

    public AnalysisJob require(UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM analysis_jobs a WHERE a.id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> notFound());
    }

    public AnalysisJob requireForUser(UUID id, UUID userId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM analysis_jobs a
                        JOIN pull_requests pr ON pr.id = a.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE a.id = :id AND i.installed_by_user_id = :userId
                        """)
                .param("id", id)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> notFound());
    }

    public void markRunning(UUID id, String status, int progressPercent, Instant leaseExpiresAt) {
        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = :status,
                            progress_percent = :progress,
                            started_at = COALESCE(started_at, NOW()),
                            lease_expires_at = :lease,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status)
                .param("progress", progressPercent)
                .param("lease", Timestamp.from(leaseExpiresAt))
                .update();
    }

    public void markCompleted(UUID id, String provider, String model, int contextFileCount,
                              Integer inputTokens, Integer outputTokens) {
        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = 'COMPLETED',
                            progress_percent = 100,
                            llm_provider = :provider,
                            llm_model = :model,
                            context_file_count = :contextFileCount,
                            input_tokens = :inputTokens,
                            output_tokens = :outputTokens,
                            completed_at = NOW(),
                            lease_expires_at = NULL,
                            error_code = NULL,
                            error_message = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("provider", provider)
                .param("model", model)
                .param("contextFileCount", contextFileCount)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .update();
    }

    public void markFailed(UUID id, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = 'FAILED',
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            completed_at = NOW(),
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update();
    }

    public void markStale(UUID id, String detail) {
        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = 'STALE',
                            error_code = 'HEAD_MOVED',
                            error_message = :detail,
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("detail", detail)
                .update();
    }

    public int incrementAttempts(UUID id) {
        return jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET attempts = attempts + 1, updated_at = NOW()
                        WHERE id = :id
                        RETURNING attempts
                        """)
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    public void requeue(UUID id) {
        jdbcClient.sql("""
                        UPDATE analysis_jobs
                        SET status = 'QUEUED', progress_percent = 0, lease_expires_at = NULL, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /** Jobs whose worker stopped reporting; the reaper requeues or fails them. */
    public List<AnalysisJob> findExpiredLeases() {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM analysis_jobs a
                        WHERE a.status IN ('BUILDING_CONTEXT', 'ANALYZING', 'VALIDATING')
                          AND a.lease_expires_at IS NOT NULL
                          AND a.lease_expires_at < NOW()
                        """)
                .query(this::map)
                .list();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND",
                "The analysis does not exist or is not available to this user.");
    }

    private AnalysisJob map(ResultSet rs, int rowNum) throws SQLException {
        return new AnalysisJob(
                rs.getObject("id", UUID.class),
                rs.getObject("pull_request_id", UUID.class),
                rs.getObject("requested_by_user_id", UUID.class),
                rs.getString("head_sha").trim(),
                rs.getString("status"),
                rs.getInt("progress_percent"),
                rs.getString("llm_provider"),
                rs.getString("llm_model"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getInt("attempts"),
                rs.getInt("context_file_count"),
                (Integer) rs.getObject("input_tokens"),
                (Integer) rs.getObject("output_tokens"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
