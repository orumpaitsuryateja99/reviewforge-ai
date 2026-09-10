package ai.reviewforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Provider-neutral model settings. The API key never leaves the control plane and is
 * never included in a response, log line, or runner payload.
 */
@ConfigurationProperties(prefix = "reviewforge.llm")
public record LlmProperties(
        String provider,
        String apiKey,
        String baseUrl,
        String model,
        String effort,
        int maxOutputTokens,
        Duration timeout,
        int maxAttempts
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
