package ai.reviewforge.findings;

import ai.reviewforge.config.ReviewProperties;
import ai.reviewforge.context.ReviewContext;
import ai.reviewforge.context.UnifiedDiffParser;
import ai.reviewforge.review.ModelReview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingValidatorTest {

    private static final String PATH = "src/main/java/com/acme/OrderService.java";
    private static final List<String> SOURCE = List.of(
            "package com.acme;",                            // 1
            "",                                             // 2
            "class OrderService {",                         // 3
            "    void reserve(String sku, int quantity) {",  // 4
            "        if (inventory.available(sku)) {",       // 5
            "            inventory.reserve(sku, quantity);", // 6
            "        }",                                     // 7
            "    }",                                         // 8
            "}"                                              // 9
    );

    private final ReviewProperties properties = new ReviewProperties(
            List.of(".java"), 12, 120_000, 400_000, 20, 3, 0.5
    );
    private final FindingValidator validator = new FindingValidator(properties);

    @Test
    void acceptsAFindingBackedByChangedLinesAndRealEvidence() {
        FindingValidator.Result result = validator.validate(context(), List.of(finding(5, 6,
                "if (inventory.available(sku)) {", 0.9)));

        assertThat(result.rejected()).isEmpty();
        assertThat(result.accepted()).singleElement().satisfies(finding -> {
            assertThat(finding.filePath()).isEqualTo(PATH);
            assertThat(finding.severity()).isEqualTo("HIGH");
            assertThat(finding.startLine()).isEqualTo(5);
        });
    }

    @Test
    void rejectsAFileThatWasNotSupplied() {
        FindingValidator.Result result = validator.validate(context(), List.of(new ModelReview.ModelFinding(
                "CORRECTNESS", "HIGH", "Invented", "Explanation", "src/main/java/com/acme/Ghost.java",
                1, 2, "class Ghost {", "Scenario", "Fix", 0.9)));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("UNKNOWN_FILE");
    }

    @Test
    void rejectsLinesBeyondTheAnalyzedRevision() {
        FindingValidator.Result result = validator.validate(context(), List.of(
                finding(40, 41, "anything", 0.9)));

        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("LINE_OUT_OF_RANGE");
    }

    @Test
    void rejectsFindingsOnLinesThePullRequestDidNotTouch() {
        FindingValidator.Result result = validator.validate(context(), List.of(
                finding(1, 1, "package com.acme;", 0.9)));

        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("UNCHANGED_LINES");
    }

    @Test
    void rejectsEvidenceThatDoesNotAppearInTheRange() {
        FindingValidator.Result result = validator.validate(context(), List.of(
                finding(5, 6, "inventory.releaseAll();", 0.9)));

        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsInventedEvidenceThatOnlyContainsAShortSourceLine() {
        FindingValidator.Result result = validator.validate(context(), List.of(
                finding(7, 7, "invented explanation ending in }", 0.9)));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsUnknownEnumsAndOutOfRangeConfidence() {
        FindingValidator.Result unknownCategory = validator.validate(context(), List.of(
                new ModelReview.ModelFinding("VIBES", "HIGH", "Title", "Explanation", PATH,
                        5, 5, "if (inventory.available(sku)) {", "Scenario", "Fix", 0.9)));
        FindingValidator.Result badConfidence = validator.validate(context(), List.of(
                finding(5, 5, "if (inventory.available(sku)) {", 4.2)));

        assertThat(unknownCategory.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("INVALID_ENUM");
        assertThat(badConfidence.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("INVALID_CONFIDENCE");
    }

    @Test
    void rejectsNonFiniteConfidence() {
        FindingValidator.Result nan = validator.validate(context(), List.of(
                finding(5, 5, "if (inventory.available(sku)) {", Double.NaN)));
        FindingValidator.Result infinity = validator.validate(context(), List.of(
                finding(5, 5, "if (inventory.available(sku)) {", Double.POSITIVE_INFINITY)));

        assertThat(nan.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("INVALID_CONFIDENCE");
        assertThat(infinity.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("INVALID_CONFIDENCE");
    }

    @Test
    void dropsFindingsBelowTheConfidenceFloor() {
        FindingValidator.Result result = validator.validate(context(), List.of(
                finding(5, 5, "if (inventory.available(sku)) {", 0.2)));

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("INVALID_CONFIDENCE");
    }

    @Test
    void collapsesDuplicatesAndCapsTheResultSet() {
        ModelReview.ModelFinding duplicate = finding(5, 5, "if (inventory.available(sku)) {", 0.9);
        FindingValidator.Result result = validator.validate(context(), List.of(
                duplicate, duplicate,
                severity("CRITICAL", 6, "inventory.reserve(sku, quantity);", 0.8),
                severity("LOW", 7, "}", 0.7),
                severity("MEDIUM", 5, "if (inventory.available(sku)) {", 0.6)));

        assertThat(result.accepted()).hasSize(3);
        assertThat(result.accepted().getFirst().severity()).isEqualTo("CRITICAL");
        assertThat(result.rejected()).anySatisfy(rejection ->
                assertThat(rejection.reasonCode()).isEqualTo("DUPLICATE"));
    }

    private ReviewContext context() {
        String patch = """
                @@ -4,3 +4,5 @@
                     void reserve(String sku, int quantity) {
                +        if (inventory.available(sku)) {
                +            inventory.reserve(sku, quantity);
                +        }
                     }""";
        ReviewContext.ContextFile file = new ReviewContext.ContextFile(
                PATH, "MODIFIED", patch, UnifiedDiffParser.parse(patch), SOURCE, null, null
        );
        return new ReviewContext("acme/orders", 7, "Reserve inventory", "main", "feature",
                "a".repeat(40), "pom.xml", List.of(file), List.of(), false);
    }

    private ModelReview.ModelFinding finding(int startLine, int endLine, String evidence, double confidence) {
        return new ModelReview.ModelFinding("CORRECTNESS", "HIGH", "Check-then-act race",
                "Availability is checked outside the lock.", PATH, startLine, endLine, evidence,
                "Two concurrent reservations both pass the check.", "Reserve atomically.", confidence);
    }

    private ModelReview.ModelFinding severity(String severity, int line, String evidence, double confidence) {
        return new ModelReview.ModelFinding("CORRECTNESS", severity, "Finding at line " + line,
                "Explanation", PATH, line, line, evidence, "Scenario", "Fix", confidence);
    }
}
