package ai.reviewforge.generation;

import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisJobRepository;
import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.config.LlmProperties;
import ai.reviewforge.config.RunnerProperties;
import ai.reviewforge.findings.Finding;
import ai.reviewforge.findings.FindingRepository;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository.RepositoryRecord;
import ai.reviewforge.github.persistence.PullRequestRepository.PullRequestRecord;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.jobs.JobQueue;
import ai.reviewforge.jobs.JobType;
import ai.reviewforge.review.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The approval and staleness gates that stand between a finding and a generated test. */
class TestGenerationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID FINDING_ID = UUID.randomUUID();
    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID PULL_REQUEST_ID = UUID.randomUUID();
    private static final UUID GENERATED_TEST_ID = UUID.randomUUID();
    private static final String HEAD_SHA = "1".repeat(40);

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final GeneratedTestRepository generatedTestRepository = mock(GeneratedTestRepository.class);
    private final PullRequestService pullRequestService = mock(PullRequestService.class);
    private final GitHubApiClient apiClient = mock(GitHubApiClient.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final JobQueue jobQueue = mock(JobQueue.class);

    private TestGenerationService service;

    @BeforeEach
    void setUp() {
        service = new TestGenerationService(
                findingRepository, analysisJobRepository, generatedTestRepository, pullRequestService,
                apiClient, new TestGenerationPromptFactory(), new TestPatchFactory(), llmClient,
                new LlmProperties("gemini", "key", null, "gemini-2.5-flash", "HIGH", 16_000,
                        Duration.ofMinutes(5), 2),
                new JobProperties(true, 2, 3, Duration.ofMinutes(15), Duration.ofSeconds(10),
                        Duration.ofMinutes(5), Duration.ofSeconds(2)),
                jobQueue,
                Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC));

        when(llmClient.available()).thenReturn(true);
        when(findingRepository.requireForUser(FINDING_ID, USER_ID)).thenReturn(finding());
    }

    @Test
    void queuesGenerationAgainstTheAnalyzedCommit() {
        when(analysisJobRepository.requireForUser(ANALYSIS_ID, USER_ID)).thenReturn(job(AnalysisJob.COMPLETED));
        givenCurrentHead(HEAD_SHA);
        when(generatedTestRepository.findActiveForFinding(FINDING_ID)).thenReturn(Optional.empty());
        when(generatedTestRepository.create(any(), any(), anyString(), anyString()))
                .thenReturn(generatedTest(GeneratedTest.GENERATING));

        GeneratedTest created = service.request(USER_ID, FINDING_ID);

        assertThat(created.status()).isEqualTo(GeneratedTest.GENERATING);
        verify(generatedTestRepository).create(FINDING_ID, ANALYSIS_ID, HEAD_SHA,
                "src/test/java/com/acme/OrderServiceReviewForgeTest.java");
        verify(jobQueue).enqueue(JobType.TEST_GENERATION, GENERATED_TEST_ID);
    }

    @Test
    void refusesToGenerateFromAnAnalysisThatHasNotCompleted() {
        when(analysisJobRepository.requireForUser(ANALYSIS_ID, USER_ID)).thenReturn(job(AnalysisJob.ANALYZING));

        assertThatThrownBy(() -> service.request(USER_ID, FINDING_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ANALYZING")
                .extracting(thrown -> ((ApiException) thrown).status())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(jobQueue, never()).enqueue(any(), any());
    }

    @Test
    void refusesToGenerateOnceThePullRequestHasMovedOn() {
        when(analysisJobRepository.requireForUser(ANALYSIS_ID, USER_ID)).thenReturn(job(AnalysisJob.COMPLETED));
        givenCurrentHead("9".repeat(40));

        assertThatThrownBy(() -> service.request(USER_ID, FINDING_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("run a new analysis")
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo("ANALYSIS_STALE");
    }

    @Test
    void refusesASecondTestWhileOneAlreadyCoversTheFinding() {
        when(analysisJobRepository.requireForUser(ANALYSIS_ID, USER_ID)).thenReturn(job(AnalysisJob.COMPLETED));
        givenCurrentHead(HEAD_SHA);
        when(generatedTestRepository.findActiveForFinding(FINDING_ID))
                .thenReturn(Optional.of(generatedTest(GeneratedTest.PROPOSED)));

        assertThatThrownBy(() -> service.request(USER_ID, FINDING_ID))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo("GENERATED_TEST_EXISTS");
    }

    @Test
    void refusesGenerationWithNoModelProviderConfigured() {
        when(llmClient.available()).thenReturn(false);

        assertThatThrownBy(() -> service.request(USER_ID, FINDING_ID))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo("LLM_NOT_CONFIGURED");
    }

    @Test
    void approvesOnlyAProposedPatch() {
        when(generatedTestRepository.requireForUser(GENERATED_TEST_ID, USER_ID))
                .thenReturn(generatedTest(GeneratedTest.FAILED));

        assertThatThrownBy(() -> service.approve(USER_ID, GENERATED_TEST_ID, true))
                .isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code())
                .isEqualTo("GENERATED_TEST_NOT_PROPOSED");
    }

    @Test
    void rejectsWithoutRequiringTheCommitToStillBeCurrent() {
        when(generatedTestRepository.requireForUser(GENERATED_TEST_ID, USER_ID))
                .thenReturn(generatedTest(GeneratedTest.PROPOSED));
        when(analysisJobRepository.requireForUser(ANALYSIS_ID, USER_ID)).thenReturn(job(AnalysisJob.COMPLETED));
        when(generatedTestRepository.require(GENERATED_TEST_ID))
                .thenReturn(generatedTest(GeneratedTest.REJECTED));

        GeneratedTest rejected = service.approve(USER_ID, GENERATED_TEST_ID, false);

        assertThat(rejected.status()).isEqualTo(GeneratedTest.REJECTED);
        verify(generatedTestRepository).markApproval(GENERATED_TEST_ID, false, USER_ID);
        verify(pullRequestService, never()).resolve(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private void givenCurrentHead(String currentHeadSha) {
        RepositoryRecord repository = new RepositoryRecord(
                UUID.randomUUID(), UUID.randomUUID(), 555L, "acme", "orders", "acme/orders",
                "main", false, false, false);
        PullRequestRecord pullRequest = new PullRequestRecord(
                PULL_REQUEST_ID, repository.id(), 900L, 7, "Reserve inventory", "octo", "OPEN",
                "main", "2".repeat(40), "feature", currentHeadSha, Instant.now());
        when(pullRequestService.resolve(USER_ID, PULL_REQUEST_ID, true))
                .thenReturn(new PullRequestService.Resolved(repository, pullRequest, "installation-token"));
    }

    private Finding finding() {
        return new Finding(FINDING_ID, ANALYSIS_ID, 0, "CONCURRENCY", "HIGH", "Check-then-act race",
                "Availability is checked outside the lock.", "src/main/java/com/acme/OrderService.java",
                5, 6, "if (inventory.available(sku)) {", "Two reservations interleave.",
                "Reserve atomically.", 0.92, Instant.now());
    }

    private AnalysisJob job(String status) {
        return new AnalysisJob(ANALYSIS_ID, PULL_REQUEST_ID, USER_ID, HEAD_SHA, status, 100,
                "gemini", "gemini-2.5-flash", null, null, 1, 1, 1200, 300,
                Instant.now(), Instant.now(), Instant.now());
    }

    private GeneratedTest generatedTest(String status) {
        return new GeneratedTest(GENERATED_TEST_ID, FINDING_ID, ANALYSIS_ID, status,
                "src/test/java/com/acme/OrderServiceReviewForgeTest.java", "", null, "", HEAD_SHA,
                null, null, null, 0, Instant.now());
    }
}
