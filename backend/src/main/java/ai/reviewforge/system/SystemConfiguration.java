package ai.reviewforge.system;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SystemConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}

