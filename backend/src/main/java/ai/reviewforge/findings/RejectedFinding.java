package ai.reviewforge.findings;

/** Why a proposed finding was discarded. Kept so a review's silence stays explainable. */
public record RejectedFinding(
        String reasonCode,
        String reasonDetail,
        String filePath,
        Integer startLine,
        Integer endLine
) {
}
