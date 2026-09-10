package ai.reviewforge.generation;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * What the model is allowed to propose: a class name, its source, and why it reproduces the
 * finding. The file path, the patch, and the command that runs it are chosen by the server.
 */
@JsonClassDescription("A single self-contained JUnit 5 test class that fails because of the reported defect.")
public record ModelGeneratedTest(
        @JsonPropertyDescription("Simple class name of the test, matching the class declared in source.")
        String className,
        @JsonPropertyDescription("Complete Java source of the test class, including its package declaration and imports.")
        String source,
        @JsonPropertyDescription("Why this test fails against the current code and passes once the defect is fixed.")
        String rationale
) {
}
