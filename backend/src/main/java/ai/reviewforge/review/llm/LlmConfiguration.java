package ai.reviewforge.review.llm;

import ai.reviewforge.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the model adapter at startup. An unknown provider name fails fast rather than
 * degrading to a silently different engine.
 */
@Configuration
public class LlmConfiguration {

    @Bean
    LlmClient llmClient(LlmProperties properties, ObjectMapper objectMapper) {
        String provider = properties.provider() == null ? "" : properties.provider().toLowerCase(Locale.ROOT);
        if (!"gemini".equals(provider)) {
            throw new IllegalStateException("Unsupported reviewforge.llm.provider: '" + properties.provider() + "'");
        }
        if (!properties.configured()) {
            return new UnconfiguredLlmClient(provider);
        }
        return new GeminiLlmClient(geminiClient(properties), objectMapper, properties);
    }

    private Client geminiClient(LlmProperties properties) {
        HttpOptions.Builder httpOptions = HttpOptions.builder()
                .timeout(Math.toIntExact(Math.min(properties.timeout().toMillis(), Integer.MAX_VALUE)))
                .retryOptions(HttpRetryOptions.builder()
                        .attempts(properties.maxAttempts())
                        .httpStatusCodes(408, 409, 429, 500, 502, 503, 504));
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            httpOptions.baseUrl(properties.baseUrl());
        }
        return Client.builder()
                .apiKey(properties.apiKey())
                .httpOptions(httpOptions.build())
                .build();
    }
}
