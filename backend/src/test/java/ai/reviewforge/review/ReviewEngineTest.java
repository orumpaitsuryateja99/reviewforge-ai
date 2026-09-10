package ai.reviewforge.review;

import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisJobRepository;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.config.LlmProperties;
import ai.reviewforge.config.ReviewProperties;
import ai.reviewforge.context.ContextBuilder;
import ai.reviewforge.context.ReviewContext;
import ai.reviewforge.context.UnifiedDiffParser;
import ai.reviewforge.findings.FindingRepository;
import ai.reviewforge.findings.FindingValidator;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository.RepositoryRecord;
import ai.reviewforge.github.persistence.PullRequestRepository.PullRequestRecord;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.review.llm.LlmClient;
import ai.reviewforge.review.llm.LlmException;
import ai.reviewforge.review.llm.LlmRequest;
import ai.reviewforge.review.llm.LlmResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewEngineTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID PULL_REQUEST_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String HEAD_SHA = "1".repeat(40);
    private static final String PATH = "src/main/java/com/acme/OrderService.java";

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final PullRequestService pullRequestService = mock(PullRequestService.class);
    private final ContextBuilder contextBuilder = mock(ContextBuilder.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);

    private final ReviewProperties reviewProperties =
            new ReviewProperties(List.of(".java"), 12, 120_000, 400_000, 20, 15, 0.55);
    private final LlmProperties llmProperties = new LlmProperties(
            "gemini", "key", null, "gemini-2.5-flash", "HIGH", 16_000, Duration.ofMinutes(5), 2);
    private final JobProperties jobProperties = new JobProperties(
            true, 2, 3, Duration.ofMinutes(15), Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofSeconds(2));

    private ReviewEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ReviewEngine(
                analysisJobRepository, pullRequestService, contextBuilder,
                new ReviewPromptFactory(reviewProperties), llmClient, llmProperties,
                new FindingValidator(reviewProperties), findingRepository, jobProperties,
                Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC));

        when(llmClient.provider()).thenReturn("gemini");
        when(llmClient.model()).thenReturn("gemini-2.5-flash");
    }

    @Test
    void storesOnlyClaimsTheAnalyzedCommitSupports() {
        givenQueuedJob();
        givenResolvedPullRequest(HEAD_SHA);
        when(contextBuilder.build(anyString(), any(), any())).thenReturn(context());
        when(llmClient.complete(any(LlmRequest.class), eq(ModelReview.class))).thenReturn(new LlmResult<>(
                new ModelReview(List.of(
                        finding(PATH, 5, 6, "if (inventory.available(sku)) {", 0.9),
                        finding("src/main/java/com/acme/Ghost.java", 1, 2, "log.info(secret)", 0.99))),
                "gemini-2.5-flash", 1200, 300));

        engine.run(ANALYSIS_ID);

        ArgumentCaptor<List<FindingValidator.ValidatedFinding>> accepted = ArgumentCaptor.captor();
        verify(findingRepository).replaceForAnalysis(eq(ANALYSIS_ID), accepted.capture(), any());
        assertThat(accepted.getValue()).singleElement()
                .satisfies(value -> assertThat(value.filePath()).isEqualTo(PATH));
        verify(analysisJobRepository).markCompleted(ANALYSIS_ID, "gemini", "gemini-2.5-flash", 1, 1200, 300);
    }

    @Test
    void walksTheJobThroughEveryReportedPhase() {
        givenQueuedJob();
        givenResolvedPullRequest(HEAD_SHA);
        when(contextBuilder.build(anyString(), any(), any())).thenReturn(context());
        when(llmClient.complete(any(LlmRequest.class), eq(ModelReview.class)))
                .thenReturn(new LlmResult<>(new ModelReview(List.of()), "gemini-2.5-flash", 10, 5));

        engine.run(ANALYSIS_ID);

        verify(analysisJobRepository).markRunning(eq(ANALYSIS_ID), eq(AnalysisJob.BUILDING_CONTEXT), anyInt(), any());
        verify(analysisJobRepository).markRunning(eq(ANALYSIS_ID), eq(AnalysisJob.ANALYZING), anyInt(), any());
        verify(analysisJobRepository).markRunning(eq(ANALYSIS_ID), eq(AnalysisJob.VALIDATING), anyInt(), any());
    }

    @Test
    void marksTheAnalysisStaleWhenTheHeadCommitMovedFirst() {
        givenQueuedJob();
        givenResolvedPullRequest("9".repeat(40));

        engine.run(ANALYSIS_ID);

        verify(analysisJobRepository).markStale(eq(ANALYSIS_ID), anyString());
        verifyNoInteractions(contextBuilder);
        verify(llmClient, never()).complete(any(), any());
        verify(findingRepository, never()).replaceForAnalysis(any(), any(), any());
    }

    @Test
    void completesWithoutCallingTheModelWhenNothingReviewableChanged() {
        givenQueuedJob();
        givenResolvedPullRequest(HEAD_SHA);
        when(contextBuilder.build(anyString(), any(), any())).thenReturn(new ReviewContext(
                "acme/orders", 7, "Docs only", "main", "feature", HEAD_SHA, "pom.xml",
                List.of(), List.of("README.md (not a reviewable source type)"), true));

        engine.run(ANALYSIS_ID);

        verify(llmClient, never()).complete(any(), any());
        verify(findingRepository).replaceForAnalysis(ANALYSIS_ID, List.of(), List.of());
        verify(analysisJobRepository).markCompleted(ANALYSIS_ID, "gemini", "gemini-2.5-flash", 0, 0, 0);
    }

    @Test
    void skipsWorkForAnAlreadyTerminalAnalysis() {
        when(analysisJobRepository.require(ANALYSIS_ID)).thenReturn(job(AnalysisJob.COMPLETED));

        engine.run(ANALYSIS_ID);

        verifyNoInteractions(pullRequestService, contextBuilder, findingRepository);
        verify(llmClient, never()).complete(any(), any());
    }

    @Test
    void letsAProviderFailureReachTheQueueSoItCanBeRetried() {
        givenQueuedJob();
        givenResolvedPullRequest(HEAD_SHA);
        when(contextBuilder.build(anyString(), any(), any())).thenReturn(context());
        when(llmClient.complete(any(LlmRequest.class), eq(ModelReview.class)))
                .thenThrow(new LlmException("MODEL_RATE_LIMITED", "Slow down.", true));

        assertThatThrownBy(() -> engine.run(ANALYSIS_ID))
                .isInstanceOf(LlmException.class)
                .satisfies(thrown -> assertThat(((LlmException) thrown).retryable()).isTrue());
        verify(analysisJobRepository, never()).markCompleted(any(), any(), any(), anyInt(), any(), any());
    }

    private void givenQueuedJob() {
        when(analysisJobRepository.require(ANALYSIS_ID)).thenReturn(job(AnalysisJob.QUEUED));
    }

    private void givenResolvedPullRequest(String currentHeadSha) {
        RepositoryRecord repository = new RepositoryRecord(
                UUID.randomUUID(), UUID.randomUUID(), 555L, "acme", "orders", "acme/orders",
                "main", false, false, false);
        PullRequestRecord pullRequest = new PullRequestRecord(
                PULL_REQUEST_ID, repository.id(), 900L, 7, "Reserve inventory", "octo", "OPEN",
                "main", "2".repeat(40), "feature", currentHeadSha, Instant.now());
        when(pullRequestService.resolve(USER_ID, PULL_REQUEST_ID, true))
                .thenReturn(new PullRequestService.Resolved(repository, pullRequest, "installation-token"));
    }

    private AnalysisJob job(String status) {
        return new AnalysisJob(ANALYSIS_ID, PULL_REQUEST_ID, USER_ID, HEAD_SHA, status, 0,
                null, null, null, null, 1, 0, null, null, null, null, Instant.now());
    }

    private ReviewContext context() {
        String patch = """
                @@ -4,3 +4,5 @@
                     void reserve(String sku, int quantity) {
                +        if (inventory.available(sku)) {
                +            inventory.reserve(sku, quantity);
                +        }
                     }""";
        List<String> source = List.of(
                "package com.acme;", "", "class OrderService {",
                "    void reserve(String sku, int quantity) {",
                "        if (inventory.available(sku)) {",
                "            inventory.reserve(sku, quantity);",
                "        }", "    }", "}");
        return new ReviewContext("acme/orders", 7, "Reserve inventory", "main", "feature", HEAD_SHA, "pom.xml",
                List.of(new ReviewContext.ContextFile(PATH, "MODIFIED", patch,
                        UnifiedDiffParser.parse(patch), source, null, null)),
                List.of(), false);
    }

    private ModelReview.ModelFinding finding(String path, int start, int end, String evidence, double confidence) {
        return new ModelReview.ModelFinding("CONCURRENCY", "HIGH", "Check-then-act race",
                "Availability is checked outside the lock.", path, start, end, evidence,
                "Two reservations interleave.", "Reserve atomically.", confidence);
    }
}
