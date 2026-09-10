package ai.reviewforge.review.llm;

/**
 * One model call. {@code systemPolicy} carries ReviewForge instructions; {@code userContent}
 * carries repository text, which is always treated as data.
 */
public record LlmRequest(String systemPolicy, String userContent, int maxOutputTokens, LlmEffort effort) {
}
