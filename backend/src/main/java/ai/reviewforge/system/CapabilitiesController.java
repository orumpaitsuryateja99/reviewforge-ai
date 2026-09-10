package ai.reviewforge.system;

import ai.reviewforge.config.GitHubProperties;
import ai.reviewforge.config.RunnerProperties;
import ai.reviewforge.review.llm.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can actually do. The UI uses it to disable actions rather than letting
 * them fail late; no credential value is ever exposed here.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {

    private final GitHubProperties gitHubProperties;
    private final RunnerProperties runnerProperties;
    private final LlmClient llmClient;

    public CapabilitiesController(
            GitHubProperties gitHubProperties,
            RunnerProperties runnerProperties,
            LlmClient llmClient
    ) {
        this.gitHubProperties = gitHubProperties;
        this.runnerProperties = runnerProperties;
        this.llmClient = llmClient;
    }

    @GetMapping
    public Capabilities capabilities() {
        return new Capabilities(
                gitHubProperties.configured(),
                new ModelCapability(llmClient.available(), llmClient.provider(), llmClient.model()),
                runnerProperties.configured()
        );
    }

    public record Capabilities(boolean github, ModelCapability review, boolean testRunner) {
    }

    public record ModelCapability(boolean configured, String provider, String model) {
    }
}
