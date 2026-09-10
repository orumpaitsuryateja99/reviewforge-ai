package ai.reviewforge.runner;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.RunnerProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

/**
 * Client for the isolated runner. The payload is deliberately narrow: a snapshot, one patch,
 * a profile name, and a selector. No token, key, or connection string crosses this call.
 */
@Component
public class RunnerClient {

    private final RestClient restClient;
    private final RunnerProperties properties;

    @Autowired
    public RunnerClient(RestClient.Builder builder, RunnerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = builder.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    /** Test seam for a RestClient backed by MockRestServiceServer. */
    RunnerClient(RestClient restClient, RunnerProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public RunnerResult execute(byte[] snapshot, RunnerRequest request) {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_NOT_CONFIGURED",
                    "The isolated test runner is not configured on this deployment.");
        }

        MultiValueMap<String, HttpEntity<?>> parts = new LinkedMultiValueMap<>();
        parts.add("snapshot", filePart(snapshot));
        parts.add("request", jsonPart(request));

        try {
            RunnerResult result = restClient.post()
                    .uri(properties.url().replaceAll("/$", "") + "/runner/v1/runs")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(RunnerResult.class);
            if (result == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "RUNNER_EMPTY_RESPONSE",
                        "The runner returned no result.");
            }
            return result;
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RUNNER_UNAVAILABLE",
                    "The isolated test runner could not be reached.");
        }
    }

    public Duration timeout() {
        return properties.timeout();
    }

    private HttpEntity<ByteArrayResource> filePart(byte[] snapshot) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ByteArrayResource resource = new ByteArrayResource(snapshot) {
            @Override
            public String getFilename() {
                return "snapshot.tar.gz";
            }
        };
        return new HttpEntity<>(resource, headers);
    }

    private HttpEntity<RunnerRequest> jsonPart(RunnerRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }

    public record RunnerRequest(
            String patchPath,
            String patchContent,
            String profile,
            String testSelector,
            Integer timeoutSeconds
    ) {
    }

    public record RunnerResult(
            String status,
            String commandProfile,
            int exitCode,
            int testsRun,
            int testsPassed,
            int testsFailed,
            int testsSkipped,
            String stdout,
            String stderr,
            boolean timedOut,
            long durationMillis
    ) {
    }
}
