package youzi.lin.server.controller;

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
     * @param bedId           床位 ID（与 patientId 二选一）
     * @param patientId       患者 ID（与 bedId 二选一）
     * @param durationSeconds 时间窗口大小（秒），默认 60
     * @return 原始数据列表（时间倒序）
     */
    @GetMapping("/realtime")
    public ResponseEntity<List<VitalsRealtimeDto>> getRealtime(
            @RequestParam Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "60") int durationSeconds) {

        if (durationSeconds <= 0 || durationSeconds > 86400 || bedId == null) {
            return ResponseEntity.badRequest().build();
        }

        if (patientId != null) {
            return ResponseEntity.ok(vitalsService.getRealtimeByBedIdAndPatientId(bedId, patientId, durationSeconds));
        }
        var currentPatientOpt = wardService.getCurrentPatientByBedId(bedId);
        return currentPatientOpt
                .map(patient ->
                        ResponseEntity.ok(
                                vitalsService.getRealtimeByBedIdAndPatientId(
                                        bedId, patient.id(), durationSeconds
                                )
                        )
                )
                .orElseGet(() -> ResponseEntity.ok(vitalsService.getRealtimeByBedId(bedId, durationSeconds)));
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
     * @param patientId 患者 ID
     * @param startTime 查询起始时刻（ISO-8601 UTC，如 {@code 2026-03-04T00:00:00Z}）
     * @param endTime   查询结束时刻（ISO-8601 UTC）
     * @param interval  时间桶大小，支持 {@code 1m}、{@code 5m}、{@code 15m}、{@code 1h}，默认 {@code 1m}
     * @return 聚合趋势数据列表（时间升序）
     */
    @GetMapping("/trend")
    public ResponseEntity<List<VitalsTrendDto>> getTrend(
            @RequestParam Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime,
            @RequestParam(defaultValue = "1m") String interval) {

        if (startTime.isAfter(endTime) || bedId == null) {
            return ResponseEntity.badRequest().build();
        }

        String pgInterval = IntervalUtils.parseOrNull(interval);
        if (pgInterval == null) {
            return ResponseEntity.badRequest().build();
        }
        if (patientId != null) {
            return ResponseEntity.ok(vitalsService.getTrendByBedIdAndPatientId(bedId, patientId, startTime, endTime, pgInterval));
        }

        var currentPatientOpt = wardService.getCurrentPatientByBedId(bedId);
        return currentPatientOpt
                .map(patient -> ResponseEntity.ok(
                        vitalsService.getTrendByBedIdAndPatientId(
                                bedId, patient.id(), startTime, endTime, pgInterval
                        )
                ))
                .orElseGet(() -> ResponseEntity.badRequest().build());
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
     */
    @GetMapping("/latest")
    public ResponseEntity<VitalsRealtimeDto> getLatest(
            @RequestParam(required = false) Long bedId,
            @RequestParam(required = false) Long patientId) {

        VitalsRealtimeDto result;
        if (bedId != null) {
            result = vitalsService.getLatestByBedId(bedId);
        } else if (patientId != null) {
            result = vitalsService.getLatestByPatientId(patientId);
        } else {
            return ResponseEntity.badRequest().build();
        }

        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }
}
