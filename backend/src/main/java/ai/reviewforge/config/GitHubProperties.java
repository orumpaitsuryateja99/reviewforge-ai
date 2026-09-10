package ai.reviewforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "reviewforge.github")
public record GitHubProperties(
        String appId,
        String appSlug,
        String clientId,
        String clientSecret,
        String privateKeyBase64,
        String apiUrl,
        String apiVersion,
        Duration apiTimeout,
        String oauthCallbackUrl,
        String setupCallbackUrl,
        String frontendUrl,
        Duration sessionTtl,
        boolean secureCookie
) {

    public boolean configured() {
        return hasText(appId)
                && hasText(appSlug)
                && hasText(clientId)
                && hasText(clientSecret)
                && hasText(privateKeyBase64);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
