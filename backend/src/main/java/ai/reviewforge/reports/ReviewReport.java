package ai.reviewforge.reports;

import ai.reviewforge.analysis.AnalysisView;
import ai.reviewforge.analysis.web.FindingResponse;
import ai.reviewforge.findings.RejectedFinding;
import ai.reviewforge.generation.web.GeneratedTestResponse;
import ai.reviewforge.runner.TestRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stable projection of one analysis: what was examined, what was found, and what was proven. */
public record ReviewReport(
        AnalysisView analysis,
        String repositoryFullName,
        int pullRequestNumber,
        String pullRequestTitle,
        Map<String, Integer> findingsBySeverity,
        List<FindingResponse> findings,
        List<RejectedFinding> rejectedFindings,
        List<GeneratedTestResponse> generatedTests,
        List<TestRun> testRuns,
        Instant generatedAt
) {
}
