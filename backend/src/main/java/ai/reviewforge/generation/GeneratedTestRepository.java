package ai.reviewforge.generation;

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
public class GeneratedTestRepository {

    private static final String COLUMNS = """
            g.id, g.finding_id, g.analysis_job_id, g.status, g.target_file_path, g.unified_diff,
            g.file_content, g.rationale, g.head_sha, g.approved_by_user_id, g.approved_at,
            g.error_message, g.attempts, g.created_at
            """;

    private final JdbcClient jdbcClient;

    public GeneratedTestRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public GeneratedTest create(UUID findingId, UUID analysisJobId, String headSha, String targetFilePath) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO generated_tests (
                            id, finding_id, analysis_job_id, status, target_file_path,
                            unified_diff, rationale, head_sha
                        ) VALUES (
                            :id, :findingId, :analysisJobId, 'GENERATING', :targetFilePath, '', '', :headSha
                        )
                        """)
                .param("id", id)
                .param("findingId", findingId)
                .param("analysisJobId", analysisJobId)
                .param("targetFilePath", targetFilePath)
                .param("headSha", headSha)
                .update();
        return require(id);
    }

    public void markProposed(UUID id, String targetFilePath, String unifiedDiff, String fileContent, String rationale) {
        jdbcClient.sql("""
                        UPDATE generated_tests
                        SET status = 'PROPOSED',
                            target_file_path = :targetFilePath,
                            unified_diff = :unifiedDiff,
                            file_content = :fileContent,
                            rationale = :rationale,
                            error_message = NULL,
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("targetFilePath", targetFilePath)
                .param("unifiedDiff", unifiedDiff)
                .param("fileContent", fileContent)
                .param("rationale", rationale)
                .update();
    }

    public void markFailed(UUID id, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE generated_tests
                        SET status = 'FAILED', error_message = :errorMessage,
                            lease_expires_at = NULL, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("errorMessage", errorMessage)
                .update();
    }

    public void markGenerating(UUID id, Instant leaseExpiresAt) {
        jdbcClient.sql("""
                        UPDATE generated_tests
                        SET status = 'GENERATING', attempts = attempts + 1,
                            lease_expires_at = :lease, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("lease", Timestamp.from(leaseExpiresAt))
                .update();
    }

    public void markApproval(UUID id, boolean approved, UUID userId) {
        int updated = jdbcClient.sql("""
                        UPDATE generated_tests
                        SET status = :status,
                            approved_by_user_id = :userId,
                            approved_at = :approvedAt,
                            updated_at = NOW()
                        WHERE id = :id AND status = 'PROPOSED'
                        """)
                .param("id", id)
                .param("status", approved ? GeneratedTest.APPROVED : GeneratedTest.REJECTED)
                .param("userId", approved ? userId : null)
                .param("approvedAt", approved ? Timestamp.from(Instant.now()) : null)
                .update();
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_NOT_PROPOSED",
                    "The test proposal was already decided by another request.");
        }
    }

    public void markStale(UUID id) {
        jdbcClient.sql("""
                        UPDATE generated_tests
                        SET status = 'STALE', updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public GeneratedTest require(UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM generated_tests g WHERE g.id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(this::notFound);
    }

    public GeneratedTest requireForUser(UUID id, UUID userId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM generated_tests g
                        JOIN findings f ON f.id = g.finding_id
                        JOIN analysis_jobs a ON a.id = f.analysis_job_id
                        JOIN pull_requests pr ON pr.id = a.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE g.id = :id AND i.installed_by_user_id = :userId
                        """)
                .param("id", id)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(this::notFound);
    }

    public Optional<GeneratedTest> findActiveForFinding(UUID findingId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM generated_tests g
                        WHERE g.finding_id = :findingId AND g.status IN ('GENERATING', 'PROPOSED', 'APPROVED')
                        ORDER BY g.created_at DESC
                        LIMIT 1
                        """)
                .param("findingId", findingId)
                .query(this::map)
                .optional();
    }

    public List<GeneratedTest> findByFinding(UUID findingId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM generated_tests g
                        WHERE g.finding_id = :findingId
                        ORDER BY g.created_at DESC
                        """)
                .param("findingId", findingId)
                .query(this::map)
                .list();
    }

    public List<GeneratedTest> findByAnalysis(UUID analysisJobId) {
        return jdbcClient.sql("SELECT " + COLUMNS + """
                        FROM generated_tests g
                        WHERE g.analysis_job_id = :analysisJobId
                        ORDER BY g.created_at
                        """)
                .param("analysisJobId", analysisJobId)
                .query(this::map)
                .list();
    }

    /** Paths already claimed by other proposals in the same analysis. */
    public List<String> existingTargetPaths(UUID analysisJobId, UUID excludingId) {
        return jdbcClient.sql("""
                        SELECT target_file_path FROM generated_tests
                        WHERE analysis_job_id = :analysisJobId AND id <> :excludingId
                        """)
                .param("analysisJobId", analysisJobId)
                .param("excludingId", excludingId)
                .query(String.class)
                .list();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "GENERATED_TEST_NOT_FOUND",
                "The generated test does not exist or is not available to this user.");
    }

    private GeneratedTest map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        String headSha = rs.getString("head_sha");
        return new GeneratedTest(
                rs.getObject("id", UUID.class),
                rs.getObject("finding_id", UUID.class),
                rs.getObject("analysis_job_id", UUID.class),
                rs.getString("status"),
                rs.getString("target_file_path"),
                rs.getString("unified_diff"),
                rs.getString("file_content"),
                rs.getString("rationale"),
                headSha == null ? null : headSha.trim(),
                rs.getObject("approved_by_user_id", UUID.class),
                approvedAt == null ? null : approvedAt.toInstant(),
                rs.getString("error_message"),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
