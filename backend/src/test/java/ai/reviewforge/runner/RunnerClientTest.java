package ai.reviewforge.runner;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.RunnerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the wire contract with the isolated runner: two multipart parts named {@code snapshot}
 * and {@code request}, and no credential of any kind on the call.
 */
class RunnerClientTest {

    private static final RunnerProperties PROPERTIES = new RunnerProperties(
            "http://runner:8090", true, Duration.ofMinutes(12), 300, 157_286_400L);

    private static final String RESULT = """
            {
              "status": "FAILED",
              "commandProfile": "MAVEN_SINGLE_TEST",
              "exitCode": 0,
              "testsRun": 1,
              "testsPassed": 0,
              "testsFailed": 1,
              "testsSkipped": 0,
              "stdout": "Tests run: 1, Failures: 1",
              "stderr": "",
              "timedOut": false,
              "durationMillis": 7131
            }
            """;

    @Test
    void postsTheSnapshotAndRunPolicyAsTwoNamedParts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner:8090/runner/v1/runs"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(request -> {
                    String body = new String(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                    .getBodyAsBytes(), StandardCharsets.UTF_8);
                    assertThat(body).contains("name=\"snapshot\"");
                    assertThat(body).contains("name=\"request\"");
                    assertThat(body).contains("MAVEN_SINGLE_TEST");
                    assertThat(body.toLowerCase()).doesNotContain("authorization");
                    assertThat(body).doesNotContain("installation-token");
                })
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        RunnerClient client = new RunnerClient(builder.build(), PROPERTIES);
        RunnerClient.RunnerResult result = client.execute("archive-bytes".getBytes(StandardCharsets.UTF_8),
                new RunnerClient.RunnerRequest(
                        "src/test/java/com/acme/OrderServiceReviewForgeTest.java",
                        "class OrderServiceReviewForgeTest {}",
                        "MAVEN_SINGLE_TEST",
                        "com.acme.OrderServiceReviewForgeTest",
                        300));

        server.verify();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.testsFailed()).isEqualTo(1);
        assertThat(result.durationMillis()).isEqualTo(7131);
    }

    @Test
    void reportsAnUnreachableRunnerAsABadGateway() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner:8090/runner/v1/runs")).andRespond(withServerError());

        RunnerClient client = new RunnerClient(builder.build(), PROPERTIES);

        assertThatThrownBy(() -> client.execute(new byte[]{1},
                new RunnerClient.RunnerRequest("src/test/java/A.java", "class A {}",
                        "MAVEN_SINGLE_TEST", "A", 300)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not be reached");
    }

    @Test
    void refusesToCallAnUnconfiguredRunner() {
        RunnerClient client = new RunnerClient(RestClient.builder(),
                new RunnerProperties("", false, Duration.ofMinutes(1), 300, 1L));

        assertThatThrownBy(() -> client.execute(new byte[]{1},
                new RunnerClient.RunnerRequest("src/test/java/A.java", "class A {}",
                        "MAVEN_SINGLE_TEST", "A", 300)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not configured");
    }
}
