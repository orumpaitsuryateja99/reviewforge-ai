package ai.reviewforge.findings;

import ai.reviewforge.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class FindingRepository {

    private final JdbcClient jdbcClient;

    public FindingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void replaceForAnalysis(
            UUID analysisJobId,
            List<FindingValidator.ValidatedFinding> findings,
            List<RejectedFinding> rejected
    ) {
        jdbcClient.sql("DELETE FROM findings WHERE analysis_job_id = :analysisJobId")
                .param("analysisJobId", analysisJobId)
                .update();
        jdbcClient.sql("DELETE FROM rejected_findings WHERE analysis_job_id = :analysisJobId")
                .param("analysisJobId", analysisJobId)
                .update();

        for (int ordinal = 0; ordinal < findings.size(); ordinal++) {
            FindingValidator.ValidatedFinding finding = findings.get(ordinal);
            jdbcClient.sql("""
                            INSERT INTO findings (
                                id, analysis_job_id, ordinal, category, severity, title, explanation,
                                file_path, start_line, end_line, evidence, failure_scenario, suggested_fix, confidence
                            ) VALUES (
                                :id, :analysisJobId, :ordinal, :category, :severity, :title, :explanation,
                                :filePath, :startLine, :endLine, :evidence, :failureScenario, :suggestedFix, :confidence
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("analysisJobId", analysisJobId)
                    .param("ordinal", ordinal)
                    .param("category", finding.category())
                    .param("severity", finding.severity())
                    .param("title", finding.title())
                    .param("explanation", finding.explanation())
                    .param("filePath", finding.filePath())
                    .param("startLine", finding.startLine())
                    .param("endLine", finding.endLine())
                    .param("evidence", finding.evidence())
                    .param("failureScenario", finding.failureScenario())
                    .param("suggestedFix", finding.suggestedFix())
                    .param("confidence", finding.confidence())
                    .update();
        }

        for (RejectedFinding rejection : rejected) {
            jdbcClient.sql("""
                            INSERT INTO rejected_findings (
                                id, analysis_job_id, reason_code, reason_detail, file_path, start_line, end_line
                            ) VALUES (
                                :id, :analysisJobId, :reasonCode, :reasonDetail, :filePath, :startLine, :endLine
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("analysisJobId", analysisJobId)
                    .param("reasonCode", rejection.reasonCode())
                    .param("reasonDetail", rejection.reasonDetail())
                    .param("filePath", rejection.filePath())
                    .param("startLine", rejection.startLine())
                    .param("endLine", rejection.endLine())
                    .update();
        }
    }

    public List<Finding> findByAnalysis(UUID analysisJobId) {
        return jdbcClient.sql("""
                        SELECT id, analysis_job_id, ordinal, category, severity, title, explanation, file_path,
                               start_line, end_line, evidence, failure_scenario, suggested_fix, confidence, created_at
                        FROM findings
                        WHERE analysis_job_id = :analysisJobId
                        ORDER BY ordinal
                        """)
                .param("analysisJobId", analysisJobId)
                .query(this::map)
                .list();
    }

    public List<RejectedFinding> findRejectedByAnalysis(UUID analysisJobId) {
        return jdbcClient.sql("""
                        SELECT reason_code, reason_detail, file_path, start_line, end_line
                        FROM rejected_findings
                        WHERE analysis_job_id = :analysisJobId
                        ORDER BY created_at
                        """)
                .param("analysisJobId", analysisJobId)
                .query((rs, rowNum) -> new RejectedFinding(
                        rs.getString("reason_code"),
                        rs.getString("reason_detail"),
                        rs.getString("file_path"),
                        (Integer) rs.getObject("start_line"),
                        (Integer) rs.getObject("end_line")
                ))
                .list();
    }

    public int countByAnalysis(UUID analysisJobId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM findings WHERE analysis_job_id = :analysisJobId")
                .param("analysisJobId", analysisJobId)
                .query(Integer.class)
                .single();
    }

    public Finding requireForUser(UUID findingId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT f.id, f.analysis_job_id, f.ordinal, f.category, f.severity, f.title, f.explanation,
                               f.file_path, f.start_line, f.end_line, f.evidence, f.failure_scenario,
                               f.suggested_fix, f.confidence, f.created_at
                        FROM findings f
                        JOIN analysis_jobs a ON a.id = f.analysis_job_id
                        JOIN pull_requests pr ON pr.id = a.pull_request_id
                        JOIN repositories r ON r.id = pr.repository_id
                        JOIN github_installations i ON i.id = r.github_installation_id
                        WHERE f.id = :findingId AND i.installed_by_user_id = :userId
                        """)
                .param("findingId", findingId)
                .param("userId", userId)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINDING_NOT_FOUND",
                        "The finding does not exist or is not available to this user."));
    }

    private Finding map(ResultSet rs, int rowNum) throws SQLException {
        return new Finding(
                rs.getObject("id", UUID.class),
                rs.getObject("analysis_job_id", UUID.class),
                rs.getInt("ordinal"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("explanation"),
                rs.getString("file_path"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getString("evidence"),
                rs.getString("failure_scenario"),
                rs.getString("suggested_fix"),
                rs.getDouble("confidence"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
