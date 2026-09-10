package ai.reviewforge.review.llm;

import ai.reviewforge.config.LlmProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;

/** Google Gemini adapter that accepts only schema-constrained JSON responses. */
public class GeminiLlmClient implements LlmClient {

    private final Client client;
    private final ObjectMapper objectMapper;
    private final LlmProperties properties;

    public GeminiLlmClient(Client client, ObjectMapper objectMapper, LlmProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest request, Class<T> responseType) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(request.systemPolicy())))
                .candidateCount(1)
                .maxOutputTokens(Math.min(request.maxOutputTokens(), properties.maxOutputTokens()))
                .responseMimeType("application/json")
                .responseJsonSchema(RecordJsonSchema.from(responseType))
                .thinkingConfig(ThinkingConfig.builder()
                        .includeThoughts(false)
                        .thinkingBudget(thinkingBudget(request.effort())))
                .build();

        try {
            GenerateContentResponse response = client.models.generateContent(
                    properties.model(), request.userContent(), config);
            String json = response.text();
            if (json == null || json.isBlank()) {
                throw new LlmException("EMPTY_MODEL_RESPONSE",
                        "Gemini returned no structured content.", true);
            }

            T value = objectMapper.readValue(json, responseType);
            GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
            int inputTokens = usage == null ? 0 : usage.promptTokenCount().orElse(0);
            int outputTokens = usage == null ? 0 : usage.candidatesTokenCount().orElse(0);
            String model = response.modelVersion().orElse(properties.model());
            return new LlmResult<>(value, model, inputTokens, outputTokens);
        } catch (ApiException exception) {
            throw translate(exception);
        } catch (JsonProcessingException exception) {
            throw new LlmException("INVALID_MODEL_RESPONSE",
                    "Gemini returned content that did not match the required schema.", true, exception);
        } catch (LlmException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmException("MODEL_CALL_FAILED", "Gemini could not be reached.", true, exception);
        }
    }

    private int thinkingBudget(LlmEffort effort) {
        return switch (effort) {
            case LOW -> 1_024;
            case MEDIUM -> 2_048;
            case HIGH -> 4_096;
            case XHIGH -> 8_192;
            case MAX -> 12_288;
        };
    }

    private LlmException translate(ApiException exception) {
        int status = exception.code();
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        String code = switch (status) {
            case 401, 403 -> "MODEL_AUTHENTICATION_FAILED";
            case 429 -> "MODEL_RATE_LIMITED";
            case 400, 422 -> "MODEL_REQUEST_REJECTED";
            default -> retryable ? "MODEL_UNAVAILABLE" : "MODEL_CALL_FAILED";
        };
        return new LlmException(code, "Gemini returned status " + status + ".", retryable, exception);
    }
}
