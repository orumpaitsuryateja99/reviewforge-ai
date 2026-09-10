package ai.reviewforge.runner.web;

import ai.reviewforge.runner.run.RunRequest;
import ai.reviewforge.runner.run.RunResult;
import ai.reviewforge.runner.run.RunService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@RestController
@RequestMapping("/runner/v1")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping(value = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RunResult> run(
            @RequestPart("snapshot") MultipartFile snapshot,
            @Valid @RequestPart("request") RunRequest request
    ) {
        if (snapshot.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(runService.run(snapshot, request));
    }

    @org.springframework.web.bind.annotation.GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("reviewforge-runner", "UP", Instant.now());
    }

    public record HealthResponse(String service, String status, Instant timestamp) {
    }
}
