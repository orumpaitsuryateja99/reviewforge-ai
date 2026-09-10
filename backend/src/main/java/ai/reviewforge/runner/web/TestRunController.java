package ai.reviewforge.runner.web;

import ai.reviewforge.common.api.JobAccepted;
import ai.reviewforge.github.auth.CurrentSessionService;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository;
import ai.reviewforge.runner.TestRun;
import ai.reviewforge.runner.TestRunService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TestRunController {

    private final CurrentSessionService currentSessionService;
    private final TestRunService testRunService;

    public TestRunController(CurrentSessionService currentSessionService, TestRunService testRunService) {
        this.currentSessionService = currentSessionService;
        this.testRunService = testRunService;
    }

    @PostMapping("/generated-tests/{generatedTestId}/runs")
    public ResponseEntity<JobAccepted> run(HttpServletRequest request, @PathVariable UUID generatedTestId) {
        TestRun testRun = testRunService.request(userId(request), generatedTestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JobAccepted.of(testRun.id(), "/api/v1/test-runs/" + testRun.id()));
    }

    @GetMapping("/generated-tests/{generatedTestId}/runs")
    public List<TestRun> list(HttpServletRequest request, @PathVariable UUID generatedTestId) {
        return testRunService.listForGeneratedTest(userId(request), generatedTestId);
    }

    @GetMapping("/test-runs/{testRunId}")
    public TestRun get(HttpServletRequest request, @PathVariable UUID testRunId) {
        return testRunService.get(userId(request), testRunId);
    }

    @PostMapping("/repositories/{repositoryId}/test-execution")
    public TestExecutionResponse setTestExecution(
            HttpServletRequest request,
            @PathVariable UUID repositoryId,
            @RequestBody TestExecutionRequest body
    ) {
        GitHubRepositoryRepository.RepositoryRecord repository = testRunService.setTestExecutionAllowed(
                userId(request), repositoryId, body.allowed()
        );
        return new TestExecutionResponse(repository.id(), repository.fullName(), repository.testExecutionAllowed());
    }

    private UUID userId(HttpServletRequest request) {
        return currentSessionService.require(request).userId();
    }

    public record TestExecutionRequest(@NotNull Boolean allowed) {
    }

    public record TestExecutionResponse(UUID repositoryId, String fullName, boolean testExecutionAllowed) {
    }
}
