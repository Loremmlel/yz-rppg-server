package youzi.lin.server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import youzi.lin.server.dto.VitalsRealtimeDto;
import youzi.lin.server.dto.VitalsTrendDto;
import youzi.lin.server.service.PatientVitalsService;
import youzi.lin.server.service.WardService;
import youzi.lin.server.util.IntervalUtils;

import java.time.Instant;
import java.util.List;

/**
 * 患者生命体征查询 REST Controller。
 *
 * <p>核心接口：</p>
 * <ul>
 *     <li>{@code GET /api/vitals/realtime} — 实时明细数据（原始采样点）</li>
 *     <li>{@code GET /api/vitals/trend}    — 历史趋势聚合（TimescaleDB time_bucket）</li>
 *     <li>{@code GET /api/vitals/latest}   — 最新单条记录（实时大屏）</li>
 * </ul>
 *
 * <p>查询维度支持 {@code bedId} 和 {@code patientId} 二选一。</p>
 */
@RestController
@RequestMapping("/api/vitals")
public class VitalsController {

    private static final Logger log = LoggerFactory.getLogger(VitalsController.class);

    private final PatientVitalsService vitalsService;
    private final WardService wardService;

    public VitalsController(PatientVitalsService vitalsService, WardService wardService) {
        this.vitalsService = vitalsService;
        this.wardService = wardService;
    }

    // =========================================================
    // 实时明细查询
    // =========================================================

    /**
     * 获取最近 N 秒的原始采样数据（按秒粒度列表）。
     *
     * <p>示例：</p>
     * <pre>
     * GET /api/vitals/realtime?bedId=1&durationSeconds=60
     * GET /api/vitals/realtime?patientId=3&durationSeconds=300
     * </pre>
     *
     * @param bedId           床位 ID（必填）
     * @param patientId       患者 ID（可选；提供时精确匹配床位+患者，避免换床后数据串号）
     * @param durationSeconds 时间窗口大小（秒），默认 60，上限 86400
     * @return 原始数据列表（时间倒序）；参数非法时返回 400
     */
    @GetMapping("/realtime")
    public ResponseEntity<List<VitalsRealtimeDto>> getRealtime(
            @RequestParam Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "60") int durationSeconds) {

        if (durationSeconds <= 0 || durationSeconds > 86400 || bedId == null) {
            log.info("[Vitals] GET /realtime bedId={} patientId={} duration={}s → 400（参数不合法）",
                    bedId, patientId, durationSeconds);
            return ResponseEntity.badRequest().build();
        }

        List<VitalsRealtimeDto> result;
        if (patientId != null) {
            // 明确指定患者 ID，精确匹配；无需反查当前在住患者
            result = vitalsService.getRealtimeByBedIdAndPatientId(bedId, patientId, durationSeconds);
            log.info("[Vitals] GET /realtime bedId={} patientId={} duration={}s → {} 条（按 bedId+patientId）",
                    bedId, patientId, durationSeconds, result.size());
            return ResponseEntity.ok(result);
        }

        var currentPatientOpt = wardService.getCurrentPatientByBedId(bedId);
        if (currentPatientOpt.isPresent()) {
            long currentPatientId = currentPatientOpt.get().id();
            result = vitalsService.getRealtimeByBedIdAndPatientId(bedId, currentPatientId, durationSeconds);
            log.info("[Vitals] GET /realtime bedId={} duration={}s → {} 条（自动匹配在住患者 id={}）",
                    bedId, durationSeconds, result.size(), currentPatientId);
        } else {
            // 床位当前无在住患者，退回到只按 bedId 查询（历史数据场景）
            result = vitalsService.getRealtimeByBedId(bedId, durationSeconds);
            log.info("[Vitals] GET /realtime bedId={} duration={}s → {} 条（床位无在住患者，仅按 bedId 查询）",
                    bedId, durationSeconds, result.size());
        }
        return ResponseEntity.ok(result);
    }

    // =========================================================
    // 历史趋势聚合查询
    // =========================================================

    /**
     * 获取指定时间范围内按时间窗口聚合的趋势数据。
     *
     * <p>示例：</p>
     * <pre>
     * GET /api/vitals/trend?bedId=1&startTime=2026-03-04T00:00:00Z&endTime=2026-03-04T12:00:00Z&interval=5m
     * GET /api/vitals/trend?patientId=3&startTime=...&endTime=...&interval=1h
     * </pre>
     *
     * @param bedId     床位 ID
     * @param patientId 患者 ID（可选；无值时自动取当前在住患者，若床位无患者则返回 400）
     * @param startTime 查询起始时刻（ISO-8601 UTC，如 {@code 2026-03-04T00:00:00Z}）
     * @param endTime   查询结束时刻（ISO-8601 UTC）
     * @param interval  时间桶大小，支持 {@code 1m}、{@code 5m}、{@code 15m}、{@code 1h}，默认 {@code 1m}
     * @return 聚合趋势数据列表（时间升序）；参数非法或床位无患者时返回 400
     */
    @GetMapping("/trend")
    public ResponseEntity<List<VitalsTrendDto>> getTrend(
            @RequestParam Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime,
            @RequestParam(defaultValue = "1m") String interval) {

        if (startTime.isAfter(endTime) || bedId == null) {
            log.info("[Vitals] GET /trend bedId={} → 400（startTime 晚于 endTime 或 bedId 为空）", bedId);
            return ResponseEntity.badRequest().build();
        }

        String pgInterval = IntervalUtils.parseOrNull(interval);
        if (pgInterval == null) {
            log.info("[Vitals] GET /trend bedId={} interval='{}' → 400（interval 格式不支持）", bedId, interval);
            return ResponseEntity.badRequest().build();
        }

        if (patientId != null) {
            var result = vitalsService.getTrendByBedIdAndPatientId(bedId, patientId, startTime, endTime, pgInterval);
            log.info("[Vitals] GET /trend bedId={} patientId={} interval={} → {} 条", bedId, patientId, interval, result.size());
            return ResponseEntity.ok(result);
        }

        var currentPatientOpt = wardService.getCurrentPatientByBedId(bedId);
        if (currentPatientOpt.isEmpty()) {
            // 趋势聚合需要明确的 patientId 才能保证数据准确，无患者时拒绝请求
            log.info("[Vitals] GET /trend bedId={} interval={} → 400（床位无在住患者，无法确定查询主体）",
                    bedId, interval);
            return ResponseEntity.badRequest().build();
        }

        long currentPatientId = currentPatientOpt.get().id();
        var result = vitalsService.getTrendByBedIdAndPatientId(bedId, currentPatientId, startTime, endTime, pgInterval);
        log.info("[Vitals] GET /trend bedId={} interval={} → {} 条（自动匹配在住患者 id={}）",
                bedId, interval, result.size(), currentPatientId);
        return ResponseEntity.ok(result);
    }

    // =========================================================
    // 最新数据查询（实时大屏）
    // =========================================================

    /**
     * 获取指定床位或患者的最新一条原始记录。
     *
     * <p>示例：</p>
     * <pre>
     * GET /api/vitals/latest?bedId=1
     * GET /api/vitals/latest?patientId=3
     * </pre>
     *
     * @param bedId     床位 ID（与 patientId 二选一）
     * @param patientId 患者 ID（与 bedId 二选一）
     * @return 最新一条记录；无任何参数返回 400，无记录返回 404
     */
    @GetMapping("/latest")
    public ResponseEntity<VitalsRealtimeDto> getLatest(
            @RequestParam(required = false) Long bedId,
            @RequestParam(required = false) Long patientId) {

        VitalsRealtimeDto result;
        if (bedId != null) {
            result = vitalsService.getLatestByBedId(bedId);
            if (result == null) {
                log.info("[Vitals] GET /latest bedId={} → 404（无记录）", bedId);
                return ResponseEntity.notFound().build();
            }
            log.info("[Vitals] GET /latest bedId={} → 200", bedId);
        } else if (patientId != null) {
            result = vitalsService.getLatestByPatientId(patientId);
            if (result == null) {
                log.info("[Vitals] GET /latest patientId={} → 404（无记录）", patientId);
                return ResponseEntity.notFound().build();
            }
            log.info("[Vitals] GET /latest patientId={} → 200", patientId);
        } else {
            log.info("[Vitals] GET /latest → 400（bedId 和 patientId 均未提供）");
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(result);
    }
}
