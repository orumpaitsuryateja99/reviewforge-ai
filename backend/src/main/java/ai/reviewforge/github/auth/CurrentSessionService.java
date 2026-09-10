package ai.reviewforge.github.auth;

import ai.reviewforge.common.api.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.WebUtils;

import java.util.Optional;

@Service
public class CurrentSessionService {

    private final GitHubSessionStore sessionStore;

    public CurrentSessionService(GitHubSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public Optional<GitHubSession> current(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, GitHubSessionStore.COOKIE_NAME);
        return cookie == null ? Optional.empty() : sessionStore.find(cookie.getValue());
    }

    public GitHubSession require(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Sign in with GitHub to access this resource."
        ));
    }

    public String sessionId(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, GitHubSessionStore.COOKIE_NAME);
        return cookie == null ? null : cookie.getValue();
    }
}
