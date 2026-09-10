package ai.reviewforge.benchmark;

import ai.reviewforge.config.ReviewProperties;
import ai.reviewforge.context.ReviewContext;
import ai.reviewforge.context.UnifiedDiffParser;
import ai.reviewforge.findings.FindingValidator;
import ai.reviewforge.findings.RejectedFinding;
import ai.reviewforge.review.ModelReview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic benchmark of the validation layer over labelled model proposals. It runs on
 * every build with no provider credential: the cases record what a model proposed, and the
 * benchmark measures whether ReviewForge keeps the supported claims and drops the rest.
 *
 * <p>Live benchmarking against a real provider is documented in {@code docs/benchmark.md}.
 */
class ValidationBenchmarkTest {

    private static final double REQUIRED_PRECISION = 1.0;
    private static final double REQUIRED_RECALL = 1.0;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FindingValidator validator = new FindingValidator(
            new ReviewProperties(List.of(".java"), 12, 120_000, 400_000, 20, 15, 0.55));

    @Test
    void keepsEverySupportedClaimAndDropsEveryUnsupportedOne() throws IOException {
        Score score = new Score();
        List<String> report = new ArrayList<>();

        for (JsonNode caseNode : load().get("cases")) {
            String filePath = caseNode.get("filePath").asText();
            ReviewContext context = context(caseNode, filePath);
            List<JsonNode> proposals = new ArrayList<>();
            caseNode.get("proposals").forEach(proposals::add);

            FindingValidator.Result result = validator.validate(
                    context, proposals.stream().map(node -> finding(node, filePath)).toList());

            // Each accepted finding may only account for one proposal, so a duplicate
            // proposal is scored as the rejection it actually received.
            List<FindingValidator.ValidatedFinding> unmatched = new ArrayList<>(result.accepted());

            for (JsonNode proposal : proposals) {
                boolean expectedAccept = "ACCEPT".equals(proposal.get("expected").asText());
                boolean accepted = unmatched.removeIf(
                        finding -> finding.title().equals(proposal.get("title").asText())
                                && finding.startLine() == proposal.get("startLine").asInt());
                score.record(expectedAccept, accepted);

                String outcome = expectedAccept == accepted ? "ok" : "MISMATCH";
                report.add("  [%s] %s :: %s".formatted(outcome, caseNode.get("id").asText(),
                        proposal.get("label").asText()));

                if (!expectedAccept && proposal.hasNonNull("expectedReason")) {
                    assertThat(result.rejected())
                            .as("rejection reason for '%s'", proposal.get("label").asText())
                            .extracting(RejectedFinding::reasonCode)
                            .contains(proposal.get("expectedReason").asText());
                }
            }
        }

        System.out.println(render(score, report));

        assertThat(score.precision())
                .as("precision: accepted findings that were genuinely supported")
                .isGreaterThanOrEqualTo(REQUIRED_PRECISION);
        assertThat(score.recall())
                .as("recall: supported findings that survived validation")
                .isGreaterThanOrEqualTo(REQUIRED_RECALL);
    }

    private JsonNode load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/benchmark/validation-cases.json")) {
            assertThat(input).as("benchmark fixtures").isNotNull();
            return objectMapper.readTree(input);
        }
    }

    private ReviewContext context(JsonNode caseNode, String filePath) {
        List<String> source = new ArrayList<>();
        caseNode.get("source").forEach(line -> source.add(line.asText()));
        String patch = caseNode.get("patch").asText();

        ReviewContext.ContextFile file = new ReviewContext.ContextFile(
                filePath, "MODIFIED", patch, UnifiedDiffParser.parse(patch), source, null, null);
        return new ReviewContext("acme/orders", 7, caseNode.get("id").asText(), "main", "feature",
                "a".repeat(40), "pom.xml", List.of(file), List.of(), false);
    }

    private ModelReview.ModelFinding finding(JsonNode node, String filePath) {
        return new ModelReview.ModelFinding(
                node.get("category").asText(),
                node.get("severity").asText(),
                node.get("title").asText(),
                node.get("explanation").asText(),
                node.hasNonNull("filePathOverride") ? node.get("filePathOverride").asText() : filePath,
                node.get("startLine").asInt(),
                node.get("endLine").asInt(),
                node.get("evidence").asText(),
                node.get("failureScenario").asText(),
                node.get("suggestedFix").asText(),
                node.get("confidence").asDouble()
        );
    }

    private String render(Score score, List<String> report) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("proposals", String.valueOf(score.total()));
        summary.put("supported (expected accept)", String.valueOf(score.expectedAccepts()));
        summary.put("accepted", String.valueOf(score.accepted()));
        summary.put("precision", "%.3f".formatted(score.precision()));
        summary.put("recall", "%.3f".formatted(score.recall()));

        StringBuilder output = new StringBuilder("\nReviewForge validation benchmark\n");
        summary.forEach((key, value) -> output.append("  %-28s %s%n".formatted(key, value)));
        output.append('\n');
        report.forEach(line -> output.append(line).append('\n'));
        return output.toString();
    }

    private static final class Score {

        private int total;
        private int expectedAccepts;
        private int accepted;
        private int truePositives;

        void record(boolean expectedAccept, boolean accepted) {
            total++;
            if (expectedAccept) {
                expectedAccepts++;
            }
            if (accepted) {
                this.accepted++;
            }
            if (expectedAccept && accepted) {
                truePositives++;
            }
        }

        int total() {
            return total;
        }

        int expectedAccepts() {
            return expectedAccepts;
        }

        int accepted() {
            return accepted;
        }

        double precision() {
            return accepted == 0 ? 1.0 : (double) truePositives / accepted;
        }

        double recall() {
            return expectedAccepts == 0 ? 1.0 : (double) truePositives / expectedAccepts;
        }
    }
}
