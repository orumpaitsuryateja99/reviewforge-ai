package ai.reviewforge.reports;

import ai.reviewforge.analysis.AnalysisService;
import ai.reviewforge.analysis.AnalysisView;
import ai.reviewforge.analysis.web.FindingResponse;
import ai.reviewforge.findings.Finding;
import ai.reviewforge.generation.GeneratedTestRepository;
import ai.reviewforge.generation.web.GeneratedTestResponse;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import ai.reviewforge.github.persistence.PullRequestRepository;
import ai.reviewforge.runner.TestRunRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportService {

    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final AnalysisService analysisService;
    private final PullRequestRepository pullRequestRepository;
    private final GitHubRepositoryRepository repositoryRepository;
    private final GeneratedTestRepository generatedTestRepository;
    private final TestRunRepository testRunRepository;
    private final Clock clock;

    public ReportService(
            AnalysisService analysisService,
            PullRequestRepository pullRequestRepository,
            GitHubRepositoryRepository repositoryRepository,
            GeneratedTestRepository generatedTestRepository,
            TestRunRepository testRunRepository,
            Clock clock
    ) {
        this.analysisService = analysisService;
        this.pullRequestRepository = pullRequestRepository;
        this.repositoryRepository = repositoryRepository;
        this.generatedTestRepository = generatedTestRepository;
        this.testRunRepository = testRunRepository;
        this.clock = clock;
    }

    public ReviewReport build(UUID userId, UUID analysisId) {
        AnalysisView analysis = analysisService.get(userId, analysisId);
        List<Finding> findings = analysisService.findings(userId, analysisId);
        PullRequestRepository.PullRequestRecord pullRequest =
                pullRequestRepository.requireForUser(analysis.pullRequestId(), userId);
        GitHubRepositoryRepository.RepositoryRecord repository =
                repositoryRepository.requireForUser(pullRequest.repositoryId(), userId);

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        SEVERITIES.forEach(severity -> bySeverity.put(severity, 0));
        findings.forEach(finding -> bySeverity.merge(finding.severity(), 1, Integer::sum));

        return new ReviewReport(
                analysis,
                repository.fullName(),
                pullRequest.number(),
                pullRequest.title(),
                bySeverity,
                findings.stream().map(FindingResponse::from).toList(),
                analysisService.rejectedFindings(userId, analysisId),
                generatedTestRepository.findByAnalysis(analysisId).stream()
                        .map(GeneratedTestResponse::from)
                        .toList(),
                testRunRepository.findByAnalysis(analysisId),
                clock.instant()
        );
    }
}
