package ai.reviewforge.review;

import ai.reviewforge.analysis.AnalysisJob;
import ai.reviewforge.analysis.AnalysisJobRepository;
import ai.reviewforge.config.JobProperties;
import ai.reviewforge.config.LlmProperties;
import ai.reviewforge.context.ContextBuilder;
import ai.reviewforge.context.ReviewContext;
import ai.reviewforge.findings.FindingRepository;
import ai.reviewforge.findings.FindingValidator;
import ai.reviewforge.github.service.PullRequestService;
import ai.reviewforge.review.llm.LlmClient;
import ai.reviewforge.review.llm.LlmEffort;
import ai.reviewforge.review.llm.LlmException;
import ai.reviewforge.review.llm.LlmRequest;
import ai.reviewforge.review.llm.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Runs one analysis end to end: pin the commit, build bounded context, ask the model,
 * validate every claim against the retrieved source, then store what survived.
 */
@Service
public class ReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngine.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final PullRequestService pullRequestService;
    private final ContextBuilder contextBuilder;
    private final ReviewPromptFactory promptFactory;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final FindingValidator validator;
    private final FindingRepository findingRepository;
    private final JobProperties jobProperties;
    private final Clock clock;

    public ReviewEngine(
            AnalysisJobRepository analysisJobRepository,
            PullRequestService pullRequestService,
            ContextBuilder contextBuilder,
            ReviewPromptFactory promptFactory,
            LlmClient llmClient,
            LlmProperties llmProperties,
            FindingValidator validator,
            FindingRepository findingRepository,
            JobProperties jobProperties,
            Clock clock
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.pullRequestService = pullRequestService;
        this.contextBuilder = contextBuilder;
        this.promptFactory = promptFactory;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.validator = validator;
        this.findingRepository = findingRepository;
        this.jobProperties = jobProperties;
        this.clock = clock;
    }

    public void run(UUID analysisId) {
        AnalysisJob job = analysisJobRepository.require(analysisId);
        if (job.terminal()) {
            log.debug("Analysis {} is already {}; skipping", analysisId, job.status());
            return;
        }

        lease(job.id(), AnalysisJob.BUILDING_CONTEXT, 15);
        PullRequestService.Resolved resolved = pullRequestService.resolve(
                job.requestedByUserId(), job.pullRequestId(), true
        );
        if (!resolved.pullRequest().headSha().equals(job.headSha())) {
            analysisJobRepository.markStale(job.id(),
                    "The pull request advanced to " + resolved.pullRequest().headSha()
                            + " before this analysis finished.");
            return;
        }

        ReviewContext context = contextBuilder.build(
                resolved.token(), resolved.repository(), resolved.pullRequest()
        );
        if (!stillCurrent(job)) {
            return;
        }
        if (context.isEmpty()) {
            findingRepository.replaceForAnalysis(job.id(), List.of(), List.of());
            analysisJobRepository.markCompleted(job.id(), llmClient.provider(), llmClient.model(), 0, 0, 0);
            return;
        }

        lease(job.id(), AnalysisJob.ANALYZING, 45);
        LlmResult<ModelReview> result = llmClient.complete(
                new LlmRequest(
                        promptFactory.systemPolicy(),
                        promptFactory.userContent(context),
                        llmProperties.maxOutputTokens(),
                        effort()
                ),
                ModelReview.class
        );

        // A model call can take minutes. Never persist its output until GitHub confirms that
        // the commit under review is still the pull request's current head.
        if (!stillCurrent(job)) {
            return;
        }

        lease(job.id(), AnalysisJob.VALIDATING, 85);
        FindingValidator.Result validated = validator.validate(context, result.value().safeFindings());
        findingRepository.replaceForAnalysis(job.id(), validated.accepted(), validated.rejected());

        analysisJobRepository.markCompleted(
                job.id(), llmClient.provider(), result.model(), context.files().size(),
                result.inputTokens(), result.outputTokens()
        );
        log.info("Analysis {} accepted {} findings and rejected {}",
                job.id(), validated.accepted().size(), validated.rejected().size());
    }

    private void lease(UUID id, String status, int progressPercent) {
        analysisJobRepository.markRunning(id, status, progressPercent, clock.instant().plus(jobProperties.lease()));
    }

    private boolean stillCurrent(AnalysisJob job) {
        PullRequestService.Resolved current = pullRequestService.resolve(
                job.requestedByUserId(), job.pullRequestId(), true
        );
        if (current.pullRequest().headSha().equals(job.headSha())) {
            return true;
        }
        analysisJobRepository.markStale(job.id(),
                "The pull request advanced to " + current.pullRequest().headSha()
                        + " while this analysis was running.");
        return false;
    }

    private LlmEffort effort() {
        try {
            return LlmEffort.valueOf(llmProperties.effort().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new LlmException("LLM_CONFIGURATION_INVALID",
                    "reviewforge.llm.effort must be one of LOW, MEDIUM, HIGH, XHIGH, MAX.", false, exception);
        }
    }
}
