package youzi.lin.server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import youzi.lin.server.dto.HealthReportRequest;
import youzi.lin.server.service.HealthReportService;
import youzi.lin.server.service.WardService;
import youzi.lin.server.util.IntervalUtils;

/**
 * 健康报告生成接口。
 *
 * <p>示例请求：</p>
 * <pre>
 * POST /api/report/generate
 * Content-Type: application/json
 *
 * {
 *   "bedId": 1,
 *   "patientId": 3,
 *   "startTime": "2026-03-04T00:00:00Z",
 *   "endTime": "2026-03-04T12:00:00Z",
 *   "interval": "5m"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/report")
public class HealthReportController {

    private static final Logger log = LoggerFactory.getLogger(HealthReportController.class);

    private final HealthReportService healthReportService;
    private final WardService wardService;

    public HealthReportController(HealthReportService healthReportService, WardService wardService) {
        this.healthReportService = healthReportService;
        this.wardService = wardService;
    }

    /**
     * 生成健康报告 HTML。
     * <p>
     * 调用链路：参数校验 → 时间窗口聚合查询 → 规则分析 → LLM 调用 → Markdown 转 HTML。
     * LLM 调用失败时自动降级为基于规则分析结果的静态模板报告。
     * </p>
     *
     * @param request 报告生成请求参数
     * @return 报告 HTML 内容；参数非法时返回 400，其他情况始终返回 200（含降级报告）
     */
    @PostMapping(value = "/generate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> generateReport(@RequestBody HealthReportRequest request) {
        if (!isValidRequest(request)) {
            log.info("[Report] POST /generate → 400（请求参数不合法：bedId={}, patientId={}, startTime={}, endTime={}）",
                    request != null ? request.getBedId() : null,
                    request != null ? request.getPatientId() : null,
                    request != null ? request.getStartTime() : null,
                    request != null ? request.getEndTime() : null);
            return ResponseEntity.badRequest().body("<html><body><h3>参数不合法</h3></body></html>");
        }

        if (request.getPatientId() == null) {
            var currentPatientOpt = wardService.getCurrentPatientByBedId(request.getBedId());
            if (currentPatientOpt.isEmpty()) {
                log.info("[Report] POST /generate bedId={} → 400（床位当前无在住患者，且未指定 patientId）", request.getBedId());
                return ResponseEntity.badRequest().body("<html><body><h3>床位当前无在住患者，无法生成报告</h3></body></html>");
            }
            request.setPatientId(currentPatientOpt.get().id());
        }

        try {
            request.setInterval(IntervalUtils.parseOrDefault(request.getInterval(), "1 minute"));
            String html = healthReportService.generateReportHtml(request);
            log.info("[Report] POST /generate bedId={} patientId={} → 200（正常生成）",
                    request.getBedId(), request.getPatientId());
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (Exception ex) {
            // 兜底：即使 Service 层出现未预期的异常，也返回可读的降级报告而非 500
            log.warn("[Report] POST /generate bedId={} patientId={} → 200（降级报告，原因：{}）",
                    request.getBedId(), request.getPatientId(), ex.getMessage());
            String fallback = healthReportService.renderEmergencyFallback(request, ex);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(fallback);
        }
    }

    /**
     * 校验报告请求必填字段：bedId、patientId、startTime、endTime 均不为 null，
     * 且 startTime 不晚于 endTime。
     */
    private boolean isValidRequest(HealthReportRequest request) {
        if (request == null) {
            return false;
        }
        if (request.getBedId() == null) {
            return false;
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            return false;
        }
        return !request.getStartTime().isAfter(request.getEndTime());
    }
}
