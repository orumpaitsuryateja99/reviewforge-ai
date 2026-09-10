package ai.reviewforge.review.llm;

import ai.reviewforge.generation.ModelGeneratedTest;
import ai.reviewforge.review.ModelReview;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordJsonSchemaTest {

    @Test
    void createsNestedSchemaForReviewResponse() {
        Map<String, Object> schema = RecordJsonSchema.from(ModelReview.class);

        assertThat(schema).containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(schema.get("required")).isEqualTo(List.of("findings"));

        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        Map<?, ?> findings = (Map<?, ?>) properties.get("findings");
        assertThat(findings.get("type")).isEqualTo("array");

        Map<?, ?> item = (Map<?, ?>) findings.get("items");
        assertThat(item.get("type")).isEqualTo("object");
        assertThat(item.get("required")).isEqualTo(List.of(
                "category", "severity", "title", "explanation", "filePath", "startLine",
                "endLine", "evidence", "failureScenario", "suggestedFix", "confidence"));
    }

    @Test
    void includesRecordComponentDescriptions() {
        Map<String, Object> schema = RecordJsonSchema.from(ModelGeneratedTest.class);
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        Map<?, ?> source = (Map<?, ?>) properties.get("source");

        assertThat(source.get("type")).isEqualTo("string");
        assertThat(source.get("description")).isEqualTo(
                "Complete Java source of the test class, including its package declaration and imports.");
    }
}
