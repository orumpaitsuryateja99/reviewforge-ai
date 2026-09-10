package ai.reviewforge.review.llm;

/**
 * Provider-neutral model port. Implementations return schema-valid values or throw
 * {@link LlmException}; free-form text never reaches the domain.
 */
public interface LlmClient {

    String provider();

    String model();

    boolean available();

    <T> LlmResult<T> complete(LlmRequest request, Class<T> responseType);
}
