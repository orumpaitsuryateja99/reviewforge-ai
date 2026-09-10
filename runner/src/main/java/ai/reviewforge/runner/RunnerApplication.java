package ai.reviewforge.runner;

import ai.reviewforge.runner.run.RunnerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Credential-free executor for generated tests. It receives a source snapshot, one patch,
 * and a run policy over HTTP, and holds no GitHub token, model key, or database access.
 */
@SpringBootApplication
@EnableConfigurationProperties(RunnerProperties.class)
public class RunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunnerApplication.class, args);
    }
}
