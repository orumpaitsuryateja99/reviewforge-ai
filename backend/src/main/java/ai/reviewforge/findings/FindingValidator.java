package ai.reviewforge.findings;

import ai.reviewforge.config.ReviewProperties;
import ai.reviewforge.context.ReviewContext;
import ai.reviewforge.review.ModelReview;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks model output against the files actually retrieved from the analyzed commit. Nothing
 * reaches the database until its path exists in the diff, its lines exist in the head
 * revision, its range touches changed code, and its evidence matches the real source line.
 */
@Component
public class FindingValidator {

    private static final Set<String> CATEGORIES = Set.of(
            "CORRECTNESS", "SECURITY", "RELIABILITY", "PERFORMANCE", "CONCURRENCY", "DATA_INTEGRITY", "TEST_GAP");
    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "CRITICAL", 0, "HIGH", 1, "MEDIUM", 2, "LOW", 3);
    private static final int MAX_TITLE_LENGTH = 300;

    private final ReviewProperties properties;

    public FindingValidator(ReviewProperties properties) {
        this.properties = properties;
    }

    public Result validate(ReviewContext context, List<ModelReview.ModelFinding> proposed) {
        Map<String, ReviewContext.ContextFile> filesByPath = context.files().stream()
                .collect(Collectors.toMap(ReviewContext.ContextFile::path, Function.identity()));

        List<ValidatedFinding> accepted = new ArrayList<>();
        List<RejectedFinding> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ModelReview.ModelFinding finding : proposed) {
            String path = normalizePath(finding.filePath());

            if (isBlank(finding.title()) || isBlank(finding.explanation()) || isBlank(finding.evidence())
                    || isBlank(finding.failureScenario()) || isBlank(finding.suggestedFix()) || isBlank(path)) {
                rejected.add(reject("MISSING_FIELD", "A required field was empty.", finding));
                continue;
            }
            String category = upper(finding.category());
            String severity = upper(finding.severity());
            if (!CATEGORIES.contains(category) || !SEVERITIES.contains(severity)) {
                rejected.add(reject("INVALID_ENUM",
                        "Unknown category '" + finding.category() + "' or severity '" + finding.severity() + "'.", finding));
                continue;
            }
            if (!Double.isFinite(finding.confidence())
                    || finding.confidence() < 0 || finding.confidence() > 1) {
                rejected.add(reject("INVALID_CONFIDENCE",
                        "Confidence " + finding.confidence() + " is outside [0, 1].", finding));
                continue;
            }
            if (finding.confidence() < properties.minConfidence()) {
                rejected.add(reject("INVALID_CONFIDENCE",
                        "Confidence " + finding.confidence() + " is below the configured floor.", finding));
                continue;
            }

            ReviewContext.ContextFile file = filesByPath.get(path);
            if (file == null) {
                rejected.add(reject("UNKNOWN_FILE", "No file with that path was supplied for this analysis.", finding));
                continue;
            }
            if (finding.startLine() < 1 || finding.endLine() < finding.startLine()
                    || finding.endLine() > file.lineCount()) {
                rejected.add(reject("LINE_OUT_OF_RANGE",
                        "Lines " + finding.startLine() + "-" + finding.endLine()
                                + " do not exist in the analyzed revision of the file.", finding));
                continue;
            }
            if (!file.diff().covers(finding.startLine(), finding.endLine())) {
                rejected.add(reject("UNCHANGED_LINES",
                        "The range does not intersect any line this pull request changed.", finding));
                continue;
            }
            if (!evidenceMatches(file, finding)) {
                rejected.add(reject("EVIDENCE_MISMATCH",
                        "The quoted evidence does not appear on any line in the range.", finding));
                continue;
            }

            String key = path + ":" + finding.startLine() + ":" + finding.endLine() + ":"
                    + finding.title().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                rejected.add(reject("DUPLICATE", "An identical finding was already accepted.", finding));
                continue;
            }

            accepted.add(new ValidatedFinding(
                    category, severity, truncate(finding.title().trim()), finding.explanation().trim(),
                    path, finding.startLine(), finding.endLine(), finding.evidence().trim(),
                    finding.failureScenario().trim(), finding.suggestedFix().trim(), finding.confidence()
            ));
        }

        List<ValidatedFinding> ranked = accepted.stream()
                .sorted(Comparator
                        .comparingInt((ValidatedFinding finding) -> SEVERITY_RANK.get(finding.severity()))
                        .thenComparing(Comparator.comparingDouble(ValidatedFinding::confidence).reversed())
                        .thenComparing(ValidatedFinding::filePath)
                        .thenComparingInt(ValidatedFinding::startLine))
                .limit(properties.maxFindings())
                .toList();

        accepted.stream()
                .filter(finding -> !ranked.contains(finding))
                .forEach(finding -> rejected.add(new RejectedFinding("DUPLICATE",
                        "Discarded beyond the configured maximum finding count.",
                        finding.filePath(), finding.startLine(), finding.endLine())));

        return new Result(ranked, List.copyOf(rejected));
    }

    private boolean evidenceMatches(ReviewContext.ContextFile file, ModelReview.ModelFinding finding) {
        String evidence = normalizeWhitespace(finding.evidence());
        if (evidence.isEmpty()) {
            return false;
        }
        for (int line = finding.startLine(); line <= finding.endLine(); line++) {
            String source = normalizeWhitespace(file.lineAt(line));
            // The prompt asks for one complete source line. Requiring equality after whitespace
            // normalization prevents invented prose from passing merely because it contains a
            // short token from the source (for example a closing brace).
            if (!source.isEmpty() && source.equals(evidence)) {
                return true;
            }
        }
        return false;
    }

    private RejectedFinding reject(String code, String detail, ModelReview.ModelFinding finding) {
        return new RejectedFinding(code, detail, normalizePath(finding.filePath()),
                finding.startLine() <= 0 ? null : finding.startLine(),
                finding.endLine() <= 0 ? null : finding.endLine());
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("./") ? trimmed.substring(2) : trimmed;
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String title) {
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
    }

    public record ValidatedFinding(
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
    }

    public record Result(List<ValidatedFinding> accepted, List<RejectedFinding> rejected) {
    }
}
