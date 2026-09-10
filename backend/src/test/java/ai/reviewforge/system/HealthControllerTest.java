package ai.reviewforge.system;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsServiceStatusUsingUtcClock() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");
        HealthController controller = new HealthController(Clock.fixed(now, ZoneOffset.UTC), "test-version");

        HealthController.HealthResponse response = controller.health();

        assertThat(response.service()).isEqualTo("reviewforge-api");
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.version()).isEqualTo("test-version");
        assertThat(response.timestamp()).isEqualTo(now);
    }
}

