package ai.reviewforge.reports;

import ai.reviewforge.github.auth.CurrentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analyses")
public class ReportController {

    private final CurrentSessionService currentSessionService;
    private final ReportService reportService;

    public ReportController(CurrentSessionService currentSessionService, ReportService reportService) {
        this.currentSessionService = currentSessionService;
        this.reportService = reportService;
    }

    @GetMapping("/{analysisId}/report")
    public ReviewReport report(HttpServletRequest request, @PathVariable UUID analysisId) {
        return reportService.build(currentSessionService.require(request).userId(), analysisId);
    }
}
