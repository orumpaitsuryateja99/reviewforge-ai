package ai.reviewforge.workflow;

import ai.reviewforge.ReviewForgeApplication;
import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisService;
import ai.reviewforge.analysis.AnalysisView;
import ai.reviewforge.findings.Finding;
import ai.reviewforge.findings.RejectedFinding;
import ai.reviewforge.generation.GeneratedTest;
import ai.reviewforge.generation.ModelGeneratedTest;
import ai.reviewforge.generation.TestGenerationService;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.client.InstallationTokenService;
import ai.reviewforge.review.ModelReview;
import ai.reviewforge.review.llm.LlmClient;
import ai.reviewforge.review.llm.LlmRequest;
import ai.reviewforge.review.llm.LlmResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Drives the queued review workflow against real PostgreSQL and Redis. GitHub and the model
 * provider are the only stubbed boundaries, so the queue, engine, validator, persistence, and
 * generation paths all run for real.
 */
@SpringBootTest(classes = ReviewForgeApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class AnalysisWorkflowIT {

    private static final String HEAD_SHA = "1".repeat(40);
    private static final String BASE_SHA = "2".repeat(40);
    private static final String SOURCE_PATH = "src/main/java/com/acme/OrderService.java";
    private static final String SOURCE = """
            package com.acme;

            class OrderService {
                void reserve(String sku, int quantity) {
                    if (inventory.available(sku)) {
                        inventory.reserve(sku, quantity);
                    }
                }
            }
            """;
    private static final String PATCH = """
            @@ -4,3 +4,5 @@ class OrderService {
                 void reserve(String sku, int quantity) {
            +        if (inventory.available(sku)) {
            +            inventory.reserve(sku, quantity);
            +        }
                 }""";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("reviewforge")
            .withUsername("reviewforge")
            .withPassword("reviewforge");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("reviewforge.jobs.poll-timeout", () -> "1s");
        registry.add("reviewforge.jobs.max-attempts", () -> "1");
    }

    @MockitoBean
    GitHubApiClient apiClient;

    @MockitoBean
    InstallationTokenService tokenService;

    @MockitoBean
    LlmClient llmClient;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    AnalysisService analysisService;

    @Autowired
    TestGenerationService generationService;

    private UUID userId;
    private UUID pullRequestId;

    @BeforeEach
    void seedAndStub() {
        jdbcClient.sql("TRUNCATE app_users, github_installations, repositories, pull_requests, "
                + "analysis_jobs, findings, rejected_findings, generated_tests, test_runs CASCADE").update();

        userId = UUID.randomUUID();
        UUID installationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        pullRequestId = UUID.randomUUID();

        jdbcClient.sql("INSERT INTO app_users (id, github_user_id, github_login) VALUES (:id, 4242, 'octo')")
                .param("id", userId).update();
        jdbcClient.sql("""
                        INSERT INTO github_installations (id, installation_id, account_id, account_login,
                                                          account_type, installed_by_user_id)
                        VALUES (:id, 99, 7, 'acme', 'ORGANIZATION', :userId)
                        """)
                .param("id", installationId).param("userId", userId).update();
        jdbcClient.sql("""
                        INSERT INTO repositories (id, github_installation_id, github_repository_id, owner_login,
                                                  name, full_name, default_branch, private, test_execution_allowed)
                        VALUES (:id, :installationId, 555, 'acme', 'orders', 'acme/orders', 'main', FALSE, FALSE)
                        """)
                .param("id", repositoryId).param("installationId", installationId).update();
        jdbcClient.sql("""
                        INSERT INTO pull_requests (id, repository_id, github_pull_request_id, number, title,
                                                   author_login, state, base_ref, base_sha, head_ref, head_sha,
                                                   github_updated_at)
                        VALUES (:id, :repositoryId, 900, 7, 'Reserve inventory', 'octo', 'OPEN',
                                'main', :baseSha, 'feature', :headSha, :updatedAt)
                        """)
                .param("id", pullRequestId).param("repositoryId", repositoryId)
                .param("baseSha", BASE_SHA).param("headSha", HEAD_SHA)
                .param("updatedAt", Timestamp.from(Instant.now())).update();

        when(tokenService.get(anyLong())).thenReturn("installation-token");
        when(llmClient.available()).thenReturn(true);
        when(llmClient.provider()).thenReturn("stub");
        when(llmClient.model()).thenReturn("stub-model");
        when(apiClient.getPullRequest(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubApiClient.PullRequest(
                        900, 7, "Reserve inventory", "open", new GitHubApiClient.User("octo"),
                        new GitHubApiClient.GitReference("main", BASE_SHA),
                        new GitHubApiClient.GitReference("feature", HEAD_SHA),
                        Instant.now(), null));
        when(apiClient.compareFiles(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new GitHubApiClient.ChangedFilesResult(List.of(new GitHubApiClient.ChangedFile(
                        SOURCE_PATH, null, "modified", 3, 1, PATCH)), false));
        when(apiClient.getFileContent(anyString(), anyString(), anyString(), eq(SOURCE_PATH), anyString()))
                .thenReturn(Optional.of(SOURCE));
        when(apiClient.getFileContent(anyString(), anyString(), anyString(), eq("pom.xml"), anyString()))
                .thenReturn(Optional.of("<project><artifactId>orders</artifactId></project>"));
        when(apiClient.listTreePaths(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of("pom.xml", SOURCE_PATH));
    }

    @Test
    void storesOnlyFindingsThatSurviveValidationAndThenGeneratesATest() {
        stubReview(new ModelReview(List.of(
                new ModelReview.ModelFinding("CONCURRENCY", "HIGH", "Check-then-act race can oversell inventory",
                        "Availability is checked before reserving without holding a lock.", SOURCE_PATH,
                        5, 6, "if (inventory.available(sku)) {",
                        "Two threads reserve the last unit concurrently.", "Reserve atomically.", 0.92),
                new ModelReview.ModelFinding("SECURITY", "CRITICAL", "Credentials logged in a file we never saw",
                        "Invented.", "src/main/java/com/acme/Ghost.java", 1, 2, "log.info(secret)",
                        "None.", "None.", 0.99))));

        AnalysisView accepted = analysisService.request(userId, pullRequestId);
        assertThat(accepted.status()).isEqualTo(AnalysisJob.QUEUED);

        AnalysisView completed = await(() -> {
            AnalysisView view = analysisService.get(userId, accepted.id());
            return AnalysisJob.COMPLETED.equals(view.status()) ? Optional.of(view) : Optional.empty();
        });

        assertThat(completed.headSha()).isEqualTo(HEAD_SHA);
        assertThat(completed.stale()).isFalse();
        assertThat(completed.findingCount()).isEqualTo(1);
        assertThat(completed.contextFileCount()).isEqualTo(1);

        List<Finding> findings = analysisService.findings(userId, accepted.id());
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.filePath()).isEqualTo(SOURCE_PATH);
            assertThat(finding.severity()).isEqualTo("HIGH");
            assertThat(finding.startLine()).isEqualTo(5);
        });

        List<RejectedFinding> rejected = analysisService.rejectedFindings(userId, accepted.id());
        assertThat(rejected).singleElement()
                .extracting(RejectedFinding::reasonCode).isEqualTo("UNKNOWN_FILE");

        stubGeneration();
        GeneratedTest requested = generationService.request(userId, findings.getFirst().id());
        GeneratedTest proposed = await(() -> {
            GeneratedTest test = generationService.get(userId, requested.id());
            return GeneratedTest.PROPOSED.equals(test.status()) ? Optional.of(test) : Optional.empty();
        });

        assertThat(proposed.targetFilePath())
                .isEqualTo("src/test/java/com/acme/OrderServiceReviewForgeTest.java");
        assertThat(proposed.unifiedDiff()).contains("--- /dev/null");
        assertThat(proposed.fileContent()).contains("@Test");

        GeneratedTest approvedTest = generationService.approve(userId, proposed.id(), true);
        assertThat(approvedTest.status()).isEqualTo(GeneratedTest.APPROVED);
        assertThat(approvedTest.approvedAt()).isNotNull();
    }

    @Test
    void marksTheAnalysisStaleWhenTheHeadCommitMovesWhileItIsQueued() {
        stubReview(new ModelReview(List.of()));
        AnalysisView accepted = analysisService.request(userId, pullRequestId);

        String movedSha = "3".repeat(40);
        when(apiClient.getPullRequest(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubApiClient.PullRequest(
                        900, 7, "Reserve inventory", "open", new GitHubApiClient.User("octo"),
                        new GitHubApiClient.GitReference("main", BASE_SHA),
                        new GitHubApiClient.GitReference("feature", movedSha),
                        Instant.now(), null));

        AnalysisView stale = await(() -> {
            AnalysisView view = analysisService.get(userId, accepted.id());
            return view.status().equals(AnalysisJob.STALE) || view.status().equals(AnalysisJob.COMPLETED)
                    ? Optional.of(view)
                    : Optional.empty();
        });

        assertThat(stale.status()).isEqualTo(AnalysisJob.STALE);
        assertThat(stale.stale()).isTrue();
        assertThat(stale.error().code()).isEqualTo("HEAD_MOVED");
    }

    @SuppressWarnings("unchecked")
    private void stubReview(ModelReview review) {
        when(llmClient.complete(any(LlmRequest.class), eq(ModelReview.class)))
                .thenReturn(new LlmResult<>(review, "stub-model", 1200, 300));
    }

    private void stubGeneration() {
        when(llmClient.complete(any(LlmRequest.class), eq(ModelGeneratedTest.class)))
                .thenReturn(new LlmResult<>(new ModelGeneratedTest(
                        "OrderServiceReviewForgeTest",
                        """
                        package com.acme;

                        import org.junit.jupiter.api.Test;

                        class OrderServiceReviewForgeTest {
                            @Test
                            void oversellsWhenTwoReservationsInterleave() {
                            }
                        }
                        """,
                        "Fails because availability is checked outside the lock."), "stub-model", 900, 400));
    }

    private <T> T await(Supplier<Optional<T>> condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        while (Instant.now().isBefore(deadline)) {
            Optional<T> value = condition.get();
            if (value.isPresent()) {
                return value.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("The workflow did not reach the expected state in time.");
    }
}
