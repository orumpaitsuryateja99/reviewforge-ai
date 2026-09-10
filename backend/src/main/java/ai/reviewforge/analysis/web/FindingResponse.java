package ai.reviewforge.analysis.web;

import ai.reviewforge.findings.Finding;

import java.util.UUID;

/** Stable API shape for a validated finding. */
public record FindingResponse(
        UUID id,
        UUID analysisId,
        String category,
        String severity,
        String title,
        String explanation,
        String filePath,
        int startLine,
        int endLine,
        String evidence,
        String failureScenario,
        String suggestedFix,
        double confidence
) {

    public static FindingResponse from(Finding finding) {
        return new FindingResponse(
                finding.id(), finding.analysisJobId(), finding.category(), finding.severity(), finding.title(),
                finding.explanation(), finding.filePath(), finding.startLine(), finding.endLine(),
                finding.evidence(), finding.failureScenario(), finding.suggestedFix(), finding.confidence()
        );
    }
}
