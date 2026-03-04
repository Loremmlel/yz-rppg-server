package youzi.lin.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import youzi.lin.server.dto.VitalsRealtimeDTO;
import youzi.lin.server.dto.VitalsTrendDTO;
import youzi.lin.server.service.PatientVitalsService;

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

    public VitalsController(PatientVitalsService vitalsService) {
        this.vitalsService = vitalsService;
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
    public ResponseEntity<List<VitalsRealtimeDTO>> getRealtime(
            @RequestParam(required = false) Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(defaultValue = "60") int durationSeconds) {

        if (durationSeconds <= 0 || durationSeconds > 86400) {
            return ResponseEntity.badRequest().build();
        }

        if (bedId != null) {
            return ResponseEntity.ok(vitalsService.getRealtimeByBedId(bedId, durationSeconds));
        }
        if (patientId != null) {
            return ResponseEntity.ok(vitalsService.getRealtimeByPatientId(patientId, durationSeconds));
        }
        return ResponseEntity.badRequest().build();
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
     * @param bedId     床位 ID（与 patientId 二选一）
     * @param patientId 患者 ID（与 bedId 二选一）
     * @param startTime 查询起始时刻（ISO-8601 UTC，如 {@code 2026-03-04T00:00:00Z}）
     * @param endTime   查询结束时刻（ISO-8601 UTC）
     * @param interval  时间桶大小，支持 {@code 1m}、{@code 5m}、{@code 15m}、{@code 1h}，默认 {@code 1m}
     * @return 聚合趋势数据列表（时间升序）
     */
    @GetMapping("/trend")
    public ResponseEntity<List<VitalsTrendDTO>> getTrend(
            @RequestParam(required = false) Long bedId,
            @RequestParam(required = false) Long patientId,
            @RequestParam Instant startTime,
            @RequestParam Instant endTime,
            @RequestParam(defaultValue = "1m") String interval) {

        if (startTime.isAfter(endTime)) {
            return ResponseEntity.badRequest().build();
        }

        String pgInterval = parseInterval(interval);
        if (pgInterval == null) {
            return ResponseEntity.badRequest().build();
        }

        if (bedId != null) {
            return ResponseEntity.ok(vitalsService.getTrendByBedId(bedId, startTime, endTime, pgInterval));
        }
        if (patientId != null) {
            return ResponseEntity.ok(vitalsService.getTrendByPatientId(patientId, startTime, endTime, pgInterval));
        }
        return ResponseEntity.badRequest().build();
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
    public ResponseEntity<VitalsRealtimeDTO> getLatest(
            @RequestParam(required = false) Long bedId,
            @RequestParam(required = false) Long patientId) {

        VitalsRealtimeDTO result;
        if (bedId != null) {
            result = vitalsService.getLatestByBedId(bedId);
        } else if (patientId != null) {
            result = vitalsService.getLatestByPatientId(patientId);
        } else {
            return ResponseEntity.badRequest().build();
        }

        return result != null ? ResponseEntity.ok(result) : ResponseEntity.notFound().build();
    }

    // =========================================================
    // 私有工具方法
    // =========================================================

    /**
     * 将前端传入的简写 interval 转换为 PostgreSQL/TimescaleDB 接受的 INTERVAL 字符串。
     *
     * <ul>
     *     <li>{@code 1m}  → {@code 1 minute}</li>
     *     <li>{@code 5m}  → {@code 5 minutes}</li>
     *     <li>{@code 15m} → {@code 15 minutes}</li>
     *     <li>{@code 1h}  → {@code 1 hour}</li>
     *     <li>{@code 6h}  → {@code 6 hours}</li>
     *     <li>{@code 1d}  → {@code 1 day}</li>
     * </ul>
     *
     * @param interval 前端简写（如 {@code 5m}）
     * @return PostgreSQL INTERVAL 字符串；不合法时返回 {@code null}
     */
    private static String parseInterval(String interval) {
        if (interval == null || interval.isBlank()) return null;
        // 允许直接传 PostgreSQL 格式（如 "5 minutes"）
        if (interval.contains(" ")) return interval;

        return switch (interval.toLowerCase()) {
            case "1m"  -> "1 minute";
            case "5m"  -> "5 minutes";
            case "10m" -> "10 minutes";
            case "15m" -> "15 minutes";
            case "30m" -> "30 minutes";
            case "1h"  -> "1 hour";
            case "6h"  -> "6 hours";
            case "12h" -> "12 hours";
            case "1d"  -> "1 day";
            default    -> null;
        };
    }
}




