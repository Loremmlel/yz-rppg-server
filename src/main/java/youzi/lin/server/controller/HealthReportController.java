package youzi.lin.server.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import youzi.lin.server.dto.HealthReportRequest;
import youzi.lin.server.service.HealthReportService;
import youzi.lin.server.util.IntervalUtils;

/**
 * 健康报告生成接口。
 */
@RestController
@RequestMapping("/api/report")
public class HealthReportController {

    private final HealthReportService healthReportService;

    public HealthReportController(HealthReportService healthReportService) {
        this.healthReportService = healthReportService;
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateReport(@RequestBody HealthReportRequest request) {
        if (!isValidRequest(request)) {
            return ResponseEntity.badRequest().body("<html><body><h3>参数不合法</h3></body></html>");
        }

        try {
            request.setInterval(IntervalUtils.parseOrDefault(request.getInterval(), "1 minute"));
            String html = healthReportService.generateReportHtml(request);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception ex) {
            String fallback = healthReportService.renderEmergencyFallback(request, ex);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(fallback);
        }
    }

    private boolean isValidRequest(HealthReportRequest request) {
        if (request == null) {
            return false;
        }
        if (request.getBedId() == null || request.getPatientId() == null) {
            return false;
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return false;
        }
        return !request.getStartTime().isAfter(request.getEndTime());
    }
}
