package ai.reviewforge.review;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Schema the model must fill. Values are still untrusted until the validator clears them. */
@JsonClassDescription("Review findings for the changed lines of one pull request.")
public record ModelReview(
        @JsonPropertyDescription("Findings supported by evidence in the supplied source. Empty when the change looks correct.")
        List<ModelFinding> findings
) {

    public List<ModelFinding> safeFindings() {
        return findings == null ? List.of() : findings;
    }

    @JsonClassDescription("A single defect anchored to changed lines of one file.")
    public record ModelFinding(
            @JsonPropertyDescription("One of CORRECTNESS, SECURITY, RELIABILITY, PERFORMANCE, CONCURRENCY, DATA_INTEGRITY, TEST_GAP.")
            String category,
            @JsonPropertyDescription("One of CRITICAL, HIGH, MEDIUM, LOW.")
            String severity,
            @JsonPropertyDescription("Short defect statement, at most 300 characters.")
            String title,
            @JsonPropertyDescription("Why the code is wrong, in terms of the supplied source only.")
            String explanation,
            @JsonPropertyDescription("Repository-relative path exactly as given in the context.")
            String filePath,
            @JsonPropertyDescription("First line of the defect, using the numbering of the supplied head source.")
            int startLine,
            @JsonPropertyDescription("Last line of the defect; greater than or equal to startLine.")
            int endLine,
            @JsonPropertyDescription("Verbatim copy of one supplied source line inside the range.")
            String evidence,
            @JsonPropertyDescription("Concrete inputs or interleaving that produce the wrong behaviour.")
            String failureScenario,
            @JsonPropertyDescription("The change that fixes it.")
            String suggestedFix,
            @JsonPropertyDescription("Confidence between 0 and 1.")
            double confidence
    ) {
    }
}
