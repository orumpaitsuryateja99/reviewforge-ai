package ai.reviewforge.github.web;

import ai.reviewforge.config.GitHubProperties;
import ai.reviewforge.github.auth.CurrentSessionService;
import ai.reviewforge.github.auth.GitHubAuthService;
import ai.reviewforge.github.auth.GitHubSession;
import ai.reviewforge.github.auth.GitHubSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth/github")
public class GitHubAuthController {

    private final GitHubAuthService authService;
    private final GitHubSessionStore sessionStore;
    private final CurrentSessionService currentSessionService;
    private final GitHubProperties properties;

    public GitHubAuthController(
            GitHubAuthService authService,
            GitHubSessionStore sessionStore,
            CurrentSessionService currentSessionService,
            GitHubProperties properties
    ) {
        this.authService = authService;
        this.sessionStore = sessionStore;
        this.currentSessionService = currentSessionService;
        this.properties = properties;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND).location(authService.beginLogin()).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        GitHubSessionStore.CreatedSession created = authService.completeLogin(code, state);
        ResponseCookie cookie = sessionCookie(created.id(), properties.sessionTtl());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authService.frontendRedirect("connected"))
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @GetMapping("/session")
    public SessionResponse session(HttpServletRequest request) {
        return currentSessionService.current(request)
                .map(session -> SessionResponse.authenticated(properties.configured(), session))
                .orElseGet(() -> SessionResponse.anonymous(properties.configured()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessionStore.delete(currentSessionService.sessionId(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(GitHubSessionStore.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    public record SessionResponse(boolean configured, boolean authenticated, UserResponse user) {
        static SessionResponse anonymous(boolean configured) {
            return new SessionResponse(configured, false, null);
        }

        static SessionResponse authenticated(boolean configured, GitHubSession session) {
            return new SessionResponse(configured, true,
                    new UserResponse(session.userId(), session.login(), session.avatarUrl()));
        }
    }

    public record UserResponse(java.util.UUID id, String login, String avatarUrl) {
    }
}
