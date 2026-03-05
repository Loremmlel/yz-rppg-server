package youzi.lin.server.service;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import youzi.lin.server.dto.*;
import youzi.lin.server.entity.PatientVitals;
import youzi.lin.server.repository.PatientVitalsRepository;

import java.time.Instant;
import java.util.List;

/**
 * 患者生命体征时序数据 Service。
 * <p>
 * 职责：
 * <ul>
 *     <li>将 gRPC 返回的 {@link FrameAnalysisResultDto} 转换为 {@link PatientVitals} 实体并批量持久化</li>
 *     <li>SQI &lt; 0.5 时，HRV 相关字段直接置 {@code null} 入库</li>
 *     <li>组装 Repository 查询结果，转换为分组 DTO 返回给 Controller</li>
 * </ul>
 * </p>
 *
 * <p><b>Hibernate batch insert 配置提示（application.yaml）：</b></p>
 * <pre>
 * spring:
 *   jpa:
 *     properties:
 *       hibernate:
 *         jdbc:
 *           batch_size: 50
 *         order_inserts: true
 *         order_updates: true
 * </pre>
 */
@Service
public class PatientVitalsService {

    private static final Logger log = LoggerFactory.getLogger(PatientVitalsService.class);

    /** SQI 低于此阈值时认为信号质量不足，丢弃 HRV 数据 */
    private static final double SQI_THRESHOLD = 0.5;

    private final PatientVitalsRepository repository;

    public PatientVitalsService(PatientVitalsRepository repository) {
        this.repository = repository;
    }

    // =========================================================
    // 写入逻辑
    // =========================================================

    /**
     * 将单条 gRPC 分析结果转换为实体并保存。
     * <p>
     * 考虑到 1 Hz 高频写入，建议在调用方积攒批次后调用 {@link #saveAll}。
     * </p>
     *
     * @param result    gRPC 返回的分析结果
     * @param bedId     产生数据的床位 ID
     * @param patientId 当前床位在院患者 ID（可为 null）
     * @param time      数据时间戳（通常为当前时刻）
     */
    @Transactional
    public void save(FrameAnalysisResultDto result, Long bedId, Long patientId, Instant time) {
        if (bedId == null || patientId == null) {
            log.debug("[Vitals] bedId 或 patientId 为 null，跳过写入");
            return;
        }
        repository.save(toEntity(result, bedId, patientId, time));
    }

    /**
     * 批量保存多条分析结果（推荐用于 1 Hz 及以上频率的写入场景）。
     * <p>
     * 需在 {@code application.yaml} 中开启 Hibernate batch insert：
     * {@code spring.jpa.properties.hibernate.jdbc.batch_size=50}
     * </p>
     */
    @Transactional
    public void saveAll(List<PatientVitals> entities) {
        if (entities == null || entities.isEmpty()) return;
        repository.saveAll(entities);
        log.debug("[Vitals] 批量写入 {} 条记录", entities.size());
    }

    /**
     * 将 gRPC 返回结果转换为 {@link PatientVitals} 实体（对外暴露，供 gRPC 客户端使用）。
     * 当 SQI &lt; {@value #SQI_THRESHOLD} 时，HRV 字段置 null。
     */
    public PatientVitals toEntity(FrameAnalysisResultDto result,
                                  Long bedId, Long patientId, Instant time) {
        var entity = new PatientVitals();
        entity.setTime(time);
        entity.setBedId(bedId);
        entity.setPatientId(patientId);
        entity.setHr(result.getHr());
        entity.setSqi(result.getSqi());
        entity.setLatency(result.getLatency());

        var hrv = result.getHrv();
        boolean hasValidHrv = hrv != null
                && result.getSqi() != null
                && result.getSqi() >= SQI_THRESHOLD;

        if (hasValidHrv) {
            entity.setHrvBpm(hrv.getBpm());
            entity.setHrvIbi(hrv.getIbi());
            entity.setHrvSdnn(hrv.getSdnn());
            entity.setHrvSdsd(hrv.getSdsd());
            entity.setHrvRmssd(hrv.getRmssd());
            entity.setHrvPnn20(hrv.getPnn20());
            entity.setHrvPnn50(hrv.getPnn50());
            entity.setHrvHrMad(hrv.getHrMad());
            entity.setHrvSd1(hrv.getSd1());
            entity.setHrvSd2(hrv.getSd2());
            entity.setHrvS(hrv.getS());
            entity.setHrvSd1Sd2(hrv.getSd1Sd2());
            entity.setHrvBreathingrate(hrv.getBreathingrate());
            entity.setHrvVlf(hrv.getVlf());
            entity.setHrvTp(hrv.getTp());
            entity.setHrvHf(hrv.getHf());
            entity.setHrvLf(hrv.getLf());
            entity.setHrvLfHf(hrv.getLfHf());
        }
        // SQI 不足或 hrv 为 null 时，HRV 字段保持 null（不赋值即可）

        return entity;
    }

    // =========================================================
    // 查询逻辑
    // =========================================================

    /**
     * 实时明细查询：获取指定床位最近 N 秒的原始数据。
     *
     * @param bedId           床位 ID
     * @param durationSeconds 时间窗口大小（秒）
     */
    public List<VitalsRealtimeDto> getRealtimeByBedId(Long bedId, int durationSeconds) {
        var since = Instant.now().minusSeconds(durationSeconds);
        var entities = repository.findByBedIdAndTimeAfterOrderByTimeDesc(bedId, since);
        return entities.stream().map(PatientVitalsService::toRealtimeDTO).toList();
    }

    /**
     * 实时明细查询：获取指定患者最近 N 秒的原始数据。
     *
     * @param patientId       患者 ID
     * @param durationSeconds 时间窗口大小（秒）
     */
    public List<VitalsRealtimeDto> getRealtimeByPatientId(Long patientId, int durationSeconds) {
        var since = Instant.now().minusSeconds(durationSeconds);
        var entities = repository.findByPatientIdAndTimeAfterOrderByTimeDesc(patientId, since);
        return entities.stream().map(PatientVitalsService::toRealtimeDTO).toList();
    }

    /**
     * 历史趋势聚合查询（按床位 ID 和患者 ID）。
     *
     * @param bedId    床位 ID
     * @param patientId 患者 ID
     * @param start    查询起始时刻
     * @param end      查询结束时刻
     * @param interval TimescaleDB 时间桶大小字符串，如 {@code "1 minute"}、{@code "5 minutes"}、{@code "1 hour"}
     */
    public List<VitalsTrendDto> getTrendByBedIdAndPatientId(Long bedId, Long patientId, Instant start, Instant end, String interval) {
        var rows = repository.aggregateByBedIdAndPatientId(bedId, patientId, start, end, interval);
        return rows.stream().map(PatientVitalsService::toTrendDTO).toList();
    }

    /**
     * 获取指定床位最新一条记录（实时大屏用）。
     */
    public VitalsRealtimeDto getLatestByBedId(Long bedId) {
        var entity = repository.findLatestByBedId(bedId);
        return entity != null ? toRealtimeDTO(entity) : null;
    }

    /**
     * 获取指定患者最新一条记录。
     */
    public VitalsRealtimeDto getLatestByPatientId(Long patientId) {
        var entity = repository.findLatestByPatientId(patientId);
        return entity != null ? toRealtimeDTO(entity) : null;
    }

    // =========================================================
    // 私有转换方法
    // =========================================================

    private static VitalsRealtimeDto toRealtimeDTO(PatientVitals e) {
        var dto = new VitalsRealtimeDto();
        dto.setTime(e.getTime());
        dto.setBedId(e.getBedId());
        dto.setPatientId(e.getPatientId());

        // 基础生命体征
        var basic = new VitalsRealtimeDto.BasicVitals(
                e.getHr(), e.getSqi(), e.getHrvBreathingrate(), e.getLatency());
        dto.setBasicVitals(basic);

        // HRV 数据仅在存在时填充（SQI 不足时各字段均为 null，整个分组返回全 null 对象）
        if (e.getHrvSdnn() != null || e.getHrvRmssd() != null) {
            var td = getHrvTimeDomain(e);
            dto.setHrvTimeDomain(td);

            var fd = new VitalsRealtimeDto.HrvFreqDomain();
            fd.setVlf(e.getHrvVlf());
            fd.setTp(e.getHrvTp());
            fd.setHf(e.getHrvHf());
            fd.setLf(e.getHrvLf());
            fd.setLfHf(e.getHrvLfHf());
            dto.setHrvFreqDomain(fd);
        }

        return dto;
    }

    private static VitalsRealtimeDto.@NonNull HrvTimeDomain getHrvTimeDomain(PatientVitals e) {
        var td = new VitalsRealtimeDto.HrvTimeDomain();
        td.setBpm(e.getHrvBpm());
        td.setIbi(e.getHrvIbi());
        td.setSdnn(e.getHrvSdnn());
        td.setSdsd(e.getHrvSdsd());
        td.setRmssd(e.getHrvRmssd());
        td.setPnn20(e.getHrvPnn20());
        td.setPnn50(e.getHrvPnn50());
        td.setHrMad(e.getHrvHrMad());
        td.setSd1(e.getHrvSd1());
        td.setSd2(e.getHrvSd2());
        td.setS(e.getHrvS());
        td.setSd1Sd2(e.getHrvSd1Sd2());
        return td;
    }

    private static VitalsTrendDto toTrendDTO(VitalsAggregationRow row) {
        var dto = new VitalsTrendDto();
        dto.setBucketTime(row.getBucketTime());

        dto.setBasicVitals(new VitalsTrendDto.BasicVitals(
                row.getHrAvg(), row.getBrAvg(), row.getSqiAvg()));

        dto.setHrvTimeDomain(new VitalsTrendDto.HrvTimeDomain(
                row.getSdnnMedian(), row.getRmssdMedian(), row.getSdsdMedian(),
                row.getPnn50Median(), row.getPnn20Median()));

        dto.setHrvFreqDomain(new VitalsTrendDto.HrvFreqDomain(
                row.getLfHfRatio(), row.getHfAvg(), row.getLfAvg(),
                row.getVlfAvg(), row.getTpAvg()));

        return dto;
    }
}

