package ai.reviewforge.generation;

import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisJobRepository;
import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.config.LlmProperties;
import ai.reviewforge.context.ContextBuilder;
import ai.reviewforge.findings.Finding;
import ai.reviewforge.findings.FindingRepository;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.jobs.JobQueue;
import ai.reviewforge.jobs.JobType;
import ai.reviewforge.review.llm.LlmClient;
import ai.reviewforge.review.llm.LlmEffort;
import ai.reviewforge.review.llm.LlmException;
import ai.reviewforge.review.llm.LlmRequest;
import ai.reviewforge.review.llm.LlmResult;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates one targeted JUnit test per finding. The model contributes source; the server
 * contributes the path, the patch, and the approval gate.
 */
@Service
public class TestGenerationService {

    private final FindingRepository findingRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final PullRequestService pullRequestService;
    private final GitHubApiClient apiClient;
    private final TestGenerationPromptFactory promptFactory;
    private final TestPatchFactory patchFactory;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final JobProperties jobProperties;
    private final JobQueue jobQueue;
    private final Clock clock;

    public TestGenerationService(
            FindingRepository findingRepository,
            AnalysisJobRepository analysisJobRepository,
            GeneratedTestRepository generatedTestRepository,
            PullRequestService pullRequestService,
            GitHubApiClient apiClient,
            TestGenerationPromptFactory promptFactory,
            TestPatchFactory patchFactory,
            LlmClient llmClient,
            LlmProperties llmProperties,
            JobProperties jobProperties,
            JobQueue jobQueue,
            Clock clock
    ) {
        this.findingRepository = findingRepository;
        this.analysisJobRepository = analysisJobRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.pullRequestService = pullRequestService;
        this.apiClient = apiClient;
        this.promptFactory = promptFactory;
        this.patchFactory = patchFactory;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.jobProperties = jobProperties;
        this.jobQueue = jobQueue;
        this.clock = clock;
    }

    public GeneratedTest request(UUID userId, UUID findingId) {
        if (!llmClient.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_NOT_CONFIGURED",
                    "No model provider credential is configured on the server.");
        }

        Finding finding = findingRepository.requireForUser(findingId, userId);
        AnalysisJob analysis = analysisJobRepository.requireForUser(finding.analysisJobId(), userId);
        requireCurrentHead(userId, analysis);

        generatedTestRepository.findActiveForFinding(findingId).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_EXISTS",
                    "Generated test " + existing.id() + " already covers this finding.");
        });

        String provisionalPath = patchFactory.targetPath(finding.filePath(), defaultClassName(finding.filePath()));
        GeneratedTest generatedTest;
        try {
            generatedTest = generatedTestRepository.create(
                    findingId, analysis.id(), analysis.headSha(), provisionalPath
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_EXISTS",
                    "Another generated test already covers this finding.");
        }
        try {
            jobQueue.enqueue(JobType.TEST_GENERATION, generatedTest.id());
        } catch (RuntimeException exception) {
            generatedTestRepository.markFailed(generatedTest.id(),
                    "QUEUE_UNAVAILABLE: the generation job could not be queued.");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE",
                    "The job queue is unavailable; generation was not started.");
        }
        return generatedTest;
    }

    public void generate(UUID generatedTestId) {
        GeneratedTest generatedTest = generatedTestRepository.require(generatedTestId);
        if (!GeneratedTest.GENERATING.equals(generatedTest.status())) {
            return;
        }
        generatedTestRepository.markGenerating(generatedTestId, clock.instant().plus(jobProperties.lease()));

        Finding finding = findingRepository.findByAnalysis(generatedTest.analysisJobId()).stream()
                .filter(candidate -> candidate.id().equals(generatedTest.findingId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINDING_NOT_FOUND",
                        "The finding backing this generated test no longer exists."));
        AnalysisJob analysis = analysisJobRepository.require(generatedTest.analysisJobId());

        PullRequestService.Resolved resolved = pullRequestService.resolve(
                analysis.requestedByUserId(), analysis.pullRequestId(), true
        );
        if (!resolved.pullRequest().headSha().equals(analysis.headSha())) {
            generatedTestRepository.markStale(generatedTestId);
            return;
        }

        String sourceContent = apiClient.getFileContent(
                resolved.token(), resolved.repository().owner(), resolved.repository().name(),
                finding.filePath(), analysis.headSha()
        ).orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE",
                "The file backing this finding could not be read at the analyzed commit."));

        List<String> treePaths = apiClient.listTreePaths(
                resolved.token(), resolved.repository().owner(), resolved.repository().name(),
                analysis.headSha(), ContextBuilder.MAX_TREE_ENTRIES
        );
        String existingTestPath = ContextBuilder.findExistingTest(treePaths, finding.filePath());
        String existingTestSource = existingTestPath == null ? null : apiClient.getFileContent(
                resolved.token(), resolved.repository().owner(), resolved.repository().name(),
                existingTestPath, analysis.headSha()
        ).orElse(null);
        String buildFilePath = ContextBuilder.findBuildFile(treePaths);
        String buildFile = buildFilePath == null ? null : apiClient.getFileContent(
                resolved.token(), resolved.repository().owner(), resolved.repository().name(),
                buildFilePath, analysis.headSha()
        ).orElse(null);

        LlmResult<ModelGeneratedTest> result = llmClient.complete(
                new LlmRequest(
                        promptFactory.systemPolicy(),
                        promptFactory.userContent(finding, finding.filePath(), sourceContent,
                                existingTestPath, existingTestSource, buildFile),
                        llmProperties.maxOutputTokens(),
                        effort()
                ),
                ModelGeneratedTest.class
        );

        ModelGeneratedTest proposal = result.value();
        patchFactory.screen(proposal);

        PullRequestService.Resolved current = pullRequestService.resolve(
                analysis.requestedByUserId(), analysis.pullRequestId(), true
        );
        if (!current.pullRequest().headSha().equals(analysis.headSha())) {
            generatedTestRepository.markStale(generatedTestId);
            return;
        }

        List<String> taken = new ArrayList<>(treePaths);
        taken.addAll(generatedTestRepository.existingTargetPaths(analysis.id(), generatedTestId));
        String path = patchFactory.uniquePath(
                patchFactory.targetPath(finding.filePath(), proposal.className().trim()), taken
        );

        generatedTestRepository.markProposed(
                generatedTestId,
                path,
                patchFactory.newFileDiff(path, proposal.source()),
                proposal.source(),
                proposal.rationale() == null ? "" : proposal.rationale()
        );
    }

    public GeneratedTest approve(UUID userId, UUID generatedTestId, boolean approved) {
        GeneratedTest generatedTest = generatedTestRepository.requireForUser(generatedTestId, userId);
        if (!GeneratedTest.PROPOSED.equals(generatedTest.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "GENERATED_TEST_NOT_PROPOSED",
                    "Only a proposed test can be approved or rejected; this one is " + generatedTest.status() + ".");
        }
        AnalysisJob analysis = analysisJobRepository.requireForUser(generatedTest.analysisJobId(), userId);
        if (approved) {
            requireCurrentHead(userId, analysis);
        }
        generatedTestRepository.markApproval(generatedTestId, approved, userId);
        return generatedTestRepository.require(generatedTestId);
    }

    public GeneratedTest get(UUID userId, UUID generatedTestId) {
        return generatedTestRepository.requireForUser(generatedTestId, userId);
    }

    public List<GeneratedTest> listForFinding(UUID userId, UUID findingId) {
        findingRepository.requireForUser(findingId, userId);
        return generatedTestRepository.findByFinding(findingId);
    }

    private void requireCurrentHead(UUID userId, AnalysisJob analysis) {
        if (!AnalysisJob.COMPLETED.equals(analysis.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_NOT_COMPLETED",
                    "The analysis is " + analysis.status() + "; findings are only actionable once it completes.");
        }
        PullRequestService.Resolved resolved = pullRequestService.resolve(userId, analysis.pullRequestId(), true);
        if (!resolved.pullRequest().headSha().equals(analysis.headSha())) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_STALE",
                    "The pull request advanced to " + resolved.pullRequest().headSha()
                            + "; run a new analysis before acting on these findings.");
        }
    }

    private String defaultClassName(String sourcePath) {
        String fileName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base + "ReviewForgeTest";
    }

    private LlmEffort effort() {
        try {
            return LlmEffort.valueOf(llmProperties.effort().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new LlmException("LLM_CONFIGURATION_INVALID",
                    "reviewforge.llm.effort must be one of LOW, MEDIUM, HIGH, XHIGH, MAX.",
                    false, exception);
        }
    }
}
