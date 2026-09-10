package ai.reviewforge.generation.web;

import ai.reviewforge.common.api.JobAccepted;
import ai.reviewforge.generation.GeneratedTest;
import ai.reviewforge.generation.TestGenerationService;
import ai.reviewforge.github.auth.CurrentSessionService;
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
public class GeneratedTestController {

    private final CurrentSessionService currentSessionService;
    private final TestGenerationService generationService;

    public GeneratedTestController(
            CurrentSessionService currentSessionService,
            TestGenerationService generationService
    ) {
        this.currentSessionService = currentSessionService;
        this.generationService = generationService;
    }

    @PostMapping("/findings/{findingId}/generated-tests")
    public ResponseEntity<JobAccepted> generate(HttpServletRequest request, @PathVariable UUID findingId) {
        GeneratedTest generatedTest = generationService.request(userId(request), findingId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JobAccepted.of(generatedTest.id(), "/api/v1/generated-tests/" + generatedTest.id()));
    }

    @GetMapping("/findings/{findingId}/generated-tests")
    public List<GeneratedTestResponse> list(HttpServletRequest request, @PathVariable UUID findingId) {
        return generationService.listForFinding(userId(request), findingId).stream()
                .map(GeneratedTestResponse::from)
                .toList();
    }

    @GetMapping("/generated-tests/{generatedTestId}")
    public GeneratedTestResponse get(HttpServletRequest request, @PathVariable UUID generatedTestId) {
        return GeneratedTestResponse.from(generationService.get(userId(request), generatedTestId));
    }

    @PostMapping("/generated-tests/{generatedTestId}/approval")
    public GeneratedTestResponse approve(
            HttpServletRequest request,
            @PathVariable UUID generatedTestId,
            @RequestBody ApprovalRequest approval
    ) {
        return GeneratedTestResponse.from(
                generationService.approve(userId(request), generatedTestId, approval.approved())
        );
    }

    private UUID userId(HttpServletRequest request) {
        return currentSessionService.require(request).userId();
    }

    public record ApprovalRequest(@NotNull Boolean approved) {
    }
}
