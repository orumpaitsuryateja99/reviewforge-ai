package ai.reviewforge.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final Clock clock;
    private final String version;

    public HealthController(Clock clock, @Value("${reviewforge.version}") String version) {
        this.clock = clock;
        this.version = version;
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("reviewforge-api", "UP", version, clock.instant());
    }

    public record HealthResponse(String service, String status, String version, Instant timestamp) {
    }
}

