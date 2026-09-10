package ai.reviewforge.analysis;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.findings.Finding;
import ai.reviewforge.findings.FindingRepository;
import ai.reviewforge.findings.RejectedFinding;
import ai.reviewforge.github.persistence.PullRequestRepository;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.jobs.JobQueue;
import ai.reviewforge.jobs.JobType;
import ai.reviewforge.review.llm.LlmClient;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Application service for review requests. Each request pins the pull request's current head
 * SHA before queueing, so an analysis always names the commit it examined.
 */
@Service
public class AnalysisService {

    private final AnalysisJobRepository analysisJobRepository;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestService pullRequestService;
    private final FindingRepository findingRepository;
    private final JobQueue jobQueue;
    private final LlmClient llmClient;

    public AnalysisService(
            AnalysisJobRepository analysisJobRepository,
            PullRequestRepository pullRequestRepository,
            PullRequestService pullRequestService,
            FindingRepository findingRepository,
            JobQueue jobQueue,
            LlmClient llmClient
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.pullRequestService = pullRequestService;
        this.findingRepository = findingRepository;
        this.jobQueue = jobQueue;
        this.llmClient = llmClient;
    }

    public AnalysisView request(UUID userId, UUID pullRequestId) {
        if (!llmClient.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_NOT_CONFIGURED",
                    "No model provider credential is configured on the server.");
        }

        PullRequestService.Resolved resolved = pullRequestService.resolve(userId, pullRequestId, true);
        analysisJobRepository.findActiveForPullRequest(pullRequestId).ifPresent(active -> {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS",
                    "Analysis " + active.id() + " is already running for this pull request.");
        });

        AnalysisJob job;
        try {
            job = analysisJobRepository.create(pullRequestId, userId, resolved.pullRequest().headSha());
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ANALYSIS_IN_PROGRESS",
                    "Another analysis was started for this pull request at the same time.");
        }
        try {
            jobQueue.enqueue(JobType.ANALYSIS, job.id());
        } catch (RuntimeException exception) {
            // Without this the row would hold the one active-analysis slot for a job that
            // nothing is going to run.
            analysisJobRepository.markFailed(job.id(), "QUEUE_UNAVAILABLE",
                    "The analysis could not be queued for processing.");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_UNAVAILABLE",
                    "The job queue is unavailable; the analysis was not started.");
        }
        return AnalysisView.of(job, resolved.pullRequest().headSha(), 0);
    }

    public AnalysisView get(UUID userId, UUID analysisId) {
        AnalysisJob job = analysisJobRepository.requireForUser(analysisId, userId);
        return view(userId, job);
    }

    public List<AnalysisView> listForPullRequest(UUID userId, UUID pullRequestId) {
        PullRequestRepository.PullRequestRecord pullRequest =
                pullRequestRepository.requireForUser(pullRequestId, userId);
        return analysisJobRepository.findByPullRequest(pullRequestId).stream()
                .map(job -> AnalysisView.of(job, pullRequest.headSha(), findingRepository.countByAnalysis(job.id())))
                .toList();
    }

    public List<Finding> findings(UUID userId, UUID analysisId) {
        AnalysisJob job = analysisJobRepository.requireForUser(analysisId, userId);
        return findingRepository.findByAnalysis(job.id());
    }

    public List<RejectedFinding> rejectedFindings(UUID userId, UUID analysisId) {
        AnalysisJob job = analysisJobRepository.requireForUser(analysisId, userId);
        return findingRepository.findRejectedByAnalysis(job.id());
    }

    AnalysisView view(UUID userId, AnalysisJob job) {
        PullRequestRepository.PullRequestRecord pullRequest =
                pullRequestRepository.requireForUser(job.pullRequestId(), userId);
        return AnalysisView.of(job, pullRequest.headSha(), findingRepository.countByAnalysis(job.id()));
    }
}
