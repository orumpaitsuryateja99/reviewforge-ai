package ai.reviewforge.jobs;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.review.llm.LlmException;

/** Classifies a thrown failure into the queue's retry decision. */
public record JobFailure(String code, String message, boolean retryable) {

    public static JobFailure of(Throwable throwable) {
        if (throwable instanceof LlmException llmException) {
            return new JobFailure(llmException.code(), llmException.getMessage(), llmException.retryable());
        }
        if (throwable instanceof ApiException apiException) {
            boolean retryable = apiException.status().is5xxServerError();
            return new JobFailure(apiException.code(), apiException.getMessage(), retryable);
        }
        return new JobFailure("JOB_FAILED",
                throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage(),
                true);
    }
}
