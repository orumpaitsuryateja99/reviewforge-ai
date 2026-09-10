package ai.reviewforge.review.llm;

/** Provider-neutral reasoning effort. Adapters map this onto whatever their API exposes. */
public enum LlmEffort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX
}
