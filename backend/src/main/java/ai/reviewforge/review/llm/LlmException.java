package ai.reviewforge.review.llm;

/**
 * Failure from a model provider. {@code retryable} distinguishes rate limits, overload, and
 * transport faults from schema or authentication failures that will fail again identically.
 */
public class LlmException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public LlmException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public LlmException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
