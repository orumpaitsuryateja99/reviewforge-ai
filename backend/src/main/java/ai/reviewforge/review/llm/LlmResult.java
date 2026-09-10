package ai.reviewforge.review.llm;

/** A parsed, schema-valid model response plus the accounting the analysis record stores. */
public record LlmResult<T>(T value, String model, int inputTokens, int outputTokens) {
}
