package ai.reviewforge.runner;

import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisJobRepository;
import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.config.RunnerProperties;
import ai.reviewforge.generation.GeneratedTest;
import ai.reviewforge.generation.GeneratedTestRepository;
import ai.reviewforge.generation.TestPatchFactory;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.jobs.JobQueue;
import ai.reviewforge.jobs.JobType;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Runs approved tests through the isolated runner. Execution requires an explicit repository
 * opt-in and an explicit approval; neither is implied by generating a test.
 */
@Service
public class TestRunService {

    private static final String COMMAND_PROFILE = "MAVEN_SINGLE_TEST";

    private final TestRunRepository testRunRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final PullRequestService pullRequestService;
    private final GitHubApiClient apiClient;
    private final RunnerClient runnerClient;
    private final RunnerProperties runnerProperties;
    private final JobProperties jobProperties;
    private final TestPatchFactory patchFactory;
    private final JobQueue jobQueue;
    private final Clock clock;

    public TestRunService(
            TestRunRepository testRunRepository,
            GeneratedTestRepository generatedTestRepository,
            AnalysisJobRepository analysisJobRepository,
            GitHubRepositoryRepository repositoryRepository,
            PullRequestService pullRequestService,
            GitHubApiClient apiClient,
            RunnerClient runnerClient,
            RunnerProperties runnerProperties,
            JobProperties jobProperties,
            TestPatchFactory patchFactory,
            JobQueue jobQueue,
            Clock clock
    ) {
        this.testRunRepository = testRunRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.repositoryRepository = repositoryRepository;
        this.pullRequestService = pullRequestService;
        this.apiClient = apiClient;
        this.runnerClient = runnerClient;
        this.runnerProperties = runnerProperties;
        this.jobProperties = jobProperties;
        this.patchFactory = patchFactory;
        this.jobQueue = jobQueue;
        this.clock = clock;
    }

    public TestRun request(UUID userId, UUID generatedTestId) {
        if (!runnerProperties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RUNNER_NOT_CONFIGURED",
                    "The isolated test runner is not configured on this deployment.");
        }

        GeneratedTest generatedTest = generatedTestRepository.requireForUser(generatedTestId, userId);
        if (!GeneratedTest.APPROVED.equals(generatedTest.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_NOT_APPROVED",
                    "Only an approved test may be executed; this one is " + generatedTest.status() + ".");
        }

        AnalysisJob analysis = analysisJobRepository.requireForUser(generatedTest.analysisJobId(), userId);
        if (!AnalysisJob.COMPLETED.equals(analysis.status())
                || !analysis.headSha().equals(generatedTest.headSha())) {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_STALE",
                    "This test no longer belongs to a current completed analysis.");
        }
        PullRequestService.Resolved resolved = pullRequestService.resolve(userId, analysis.pullRequestId(), true);
        if (!resolved.pullRequest().headSha().equals(analysis.headSha())) {
            generatedTestRepository.markStale(generatedTest.id());
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_STALE",
                    "The pull request advanced; generate a new test from a current analysis.");
        }
        if (!resolved.repository().testExecutionAllowed()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEST_EXECUTION_NOT_ALLOWED",
                    "Enable test execution for " + resolved.repository().fullName() + " before running generated tests.");
        }

        testRunRepository.findActiveForGeneratedTest(generatedTestId).ifPresent(active -> {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_RUN_IN_PROGRESS",
                    "Test run " + active.id() + " is already running for this test.");
        });

        TestRun testRun;
        try {
            testRun = testRunRepository.create(generatedTestId, COMMAND_PROFILE);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_RUN_IN_PROGRESS",
                    "Another run was started for this generated test at the same time.");
        }
        try {
            jobQueue.enqueue(JobType.TEST_RUN, testRun.id());
        } catch (RuntimeException exception) {
            testRunRepository.markTerminal(testRun.id(), TestRun.INFRASTRUCTURE_ERROR,
                    "QUEUE_UNAVAILABLE: the run could not be queued.");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE",
                    "The job queue is unavailable; the run was not started.");
        }
        return testRun;
    }

    public void execute(UUID testRunId) {
        TestRun testRun = testRunRepository.require(testRunId);
        if (!TestRun.QUEUED.equals(testRun.status()) && !TestRun.PREPARING.equals(testRun.status())
                && !TestRun.RUNNING.equals(testRun.status())) {
            return;
        }

        GeneratedTest generatedTest = generatedTestRepository.require(testRun.generatedTestId());
        if (!GeneratedTest.APPROVED.equals(generatedTest.status()) || generatedTest.fileContent() == null) {
            testRunRepository.markTerminal(testRunId, TestRun.CANCELLED,
                    "The generated test is no longer approved.");
            return;
        }

        AnalysisJob analysis = analysisJobRepository.require(generatedTest.analysisJobId());
        testRunRepository.markRunning(testRunId, TestRun.PREPARING, clock.instant().plus(jobProperties.lease()));

        PullRequestService.Resolved resolved = pullRequestService.resolve(
                analysis.requestedByUserId(), analysis.pullRequestId(), true
        );
        if (!resolved.pullRequest().headSha().equals(analysis.headSha())) {
            generatedTestRepository.markStale(generatedTest.id());
            testRunRepository.markTerminal(testRunId, TestRun.CANCELLED,
                    "The pull request advanced past the analyzed commit before this run started.");
            return;
        }
        if (!resolved.repository().testExecutionAllowed()) {
            testRunRepository.markTerminal(testRunId, TestRun.CANCELLED,
                    "Test execution was disabled for this repository before the run started.");
            return;
        }

        byte[] snapshot = apiClient.downloadTarball(
                resolved.token(), resolved.repository().owner(), resolved.repository().name(),
                analysis.headSha(), runnerProperties.maxSnapshotBytes()
        );

        PullRequestService.Resolved current = pullRequestService.resolve(
                analysis.requestedByUserId(), analysis.pullRequestId(), true
        );
        if (!current.pullRequest().headSha().equals(analysis.headSha())) {
            generatedTestRepository.markStale(generatedTest.id());
            testRunRepository.markTerminal(testRunId, TestRun.CANCELLED,
                    "The pull request advanced while its source snapshot was being prepared.");
            return;
        }

        testRunRepository.markRunning(testRunId, TestRun.RUNNING, clock.instant().plus(jobProperties.lease()));
        RunnerClient.RunnerResult result = runnerClient.execute(snapshot, new RunnerClient.RunnerRequest(
                generatedTest.targetFilePath(),
                generatedTest.fileContent(),
                COMMAND_PROFILE,
                patchFactory.fullyQualifiedName(generatedTest.fileContent(), className(generatedTest)),
                runnerProperties.runTimeoutSeconds()
        ));
        testRunRepository.markFinished(testRunId, result);
    }

    public TestRun get(UUID userId, UUID testRunId) {
        return testRunRepository.requireForUser(testRunId, userId);
    }

    public List<TestRun> listForGeneratedTest(UUID userId, UUID generatedTestId) {
        generatedTestRepository.requireForUser(generatedTestId, userId);
        return testRunRepository.findByGeneratedTest(generatedTestId);
    }

    public GitHubRepositoryRepository.RepositoryRecord setTestExecutionAllowed(
            UUID userId, UUID repositoryId, boolean allowed
    ) {
        repositoryRepository.requireForUser(repositoryId, userId);
        return repositoryRepository.setTestExecutionAllowed(repositoryId, allowed);
    }

    private String className(GeneratedTest generatedTest) {
        String fileName = generatedTest.targetFilePath()
                .substring(generatedTest.targetFilePath().lastIndexOf('/') + 1);
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
