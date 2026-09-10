package ai.reviewforge.runner.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * A rejected run request is the caller's mistake, not a runner fault. Unknown profiles and
 * malformed run policies answer 400 so the control plane can record the real reason.
 */
@RestControllerAdvice
public class RunnerExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleRejectedPolicy(IllegalArgumentException exception,
                                                       HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RUN_POLICY_REJECTED", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                   HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "RUN_REQUEST_INVALID",
                "The run request did not pass validation.", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://reviewforge.ai/problems/" + code.toLowerCase()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
