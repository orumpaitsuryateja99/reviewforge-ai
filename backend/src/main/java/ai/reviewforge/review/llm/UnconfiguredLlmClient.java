package ai.reviewforge.review.llm;

/**
 * Stands in when no provider credential is configured. It never silently fabricates a
 * review: every call fails with a non-retryable, explicit error.
 */
public class UnconfiguredLlmClient implements LlmClient {

    private final String provider;

    public UnconfiguredLlmClient(String provider) {
        this.provider = provider;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return null;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest request, Class<T> responseType) {
        throw new LlmException("LLM_NOT_CONFIGURED",
                "No model provider credential is configured on the server.", false);
    }
}
