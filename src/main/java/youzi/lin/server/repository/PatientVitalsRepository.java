package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import youzi.lin.server.dto.VitalsAggregationRow;
import youzi.lin.server.entity.PatientVitals;
import youzi.lin.server.entity.PatientVitalsId;

import java.time.Instant;
import java.util.List;

/**
 * {@link PatientVitals} 的 Spring Data JPA Repository。
 * <p>
 * 提供两类查询：
 * <ol>
 *     <li>实时明细查询（Raw Data）：按 bedId 或 patientId 获取最近 N 秒的原始数据</li>
 *     <li>时序聚合查询（Aggregated）：使用 TimescaleDB {@code time_bucket} 降采样，
 *         对 HRV 关键指标使用 {@code percentile_cont} 中位数以抵抗异常毛刺</li>
 * </ol>
 */
@Repository
public interface PatientVitalsRepository extends JpaRepository<PatientVitals, PatientVitalsId> {

    // =========================================================
    // A. 实时明细查询（Raw Data）
    // =========================================================

    /**
     * 按床位 ID 获取指定时间范围内的原始数据，按时间倒序。
     *
     * @param bedId 床位 ID
     * @param since 起始时刻（通常为 now - N 秒）
     * @return 原始数据列表（时间倒序）
     */
    List<PatientVitals> findByBedIdAndTimeAfterOrderByTimeDesc(Long bedId, Instant since);

    /**
     * 按患者 ID 获取指定时间范围内的原始数据，按时间倒序。
     *
     * @param patientId 患者 ID
     * @param since     起始时刻
     * @return 原始数据列表（时间倒序）
     */
    List<PatientVitals> findByPatientIdAndTimeAfterOrderByTimeDesc(Long patientId, Instant since);

    /**
     * 按床位 ID 和患者 ID 获取指定时间范围内的原始数据，按时间倒序。
     *
     * @param bedId     床位 ID
     * @param patientId 患者 ID
     * @param since     起始时刻
     * @return 原始数据列表（时间倒序）
     */
    List<PatientVitals> findByBedIdAndPatientIdAndTimeAfterOrderByTimeDesc(Long bedId, Long patientId, Instant since);

    /**
     * 按床位 ID 和时间范围查询原始数据（用于历史回溯），按时间倒序。
     */
    List<PatientVitals> findByBedIdAndTimeBetweenOrderByTimeDesc(Long bedId, Instant start, Instant end);

    /**
     * 按患者 ID 和时间范围查询原始数据（用于历史回溯），按时间倒序。
     */
    List<PatientVitals> findByPatientIdAndTimeBetweenOrderByTimeDesc(Long patientId, Instant start, Instant end);

    // =========================================================
    // B. 时序聚合查询（Aggregated — TimescaleDB native queries）
    // =========================================================

    /**
     * 按床位 ID 和患者 ID 进行时间窗口聚合查询。
     * <p>
     * HRV 时域指标（sdnn、rmssd 等）使用 {@code percentile_cont(0.5)} 中位数，
     * 以提升对异常毛刺的鲁棒性。
     * </p>
     *
     * @param bedId    床位 ID
     * @param start    查询起始时刻（含）
     * @param end      查询结束时刻（含）
     * @param interval TimescaleDB 时间桶大小，如 {@code '1 minute'}、{@code '5 minutes'}、{@code '1 hour'}
     * @return 聚合结果列表（时间升序）
     */
    @Query(value = """
            SELECT
                time_bucket(CAST(:interval AS INTERVAL), pv."time") AS bucket_time,
                AVG(pv.hr)                                          AS hr_avg,
                AVG(pv.hrv_breathingrate)                           AS br_avg,
                AVG(pv.sqi)                                         AS sqi_avg,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.hrv_sdnn)  AS sdnn_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.hrv_rmssd) AS rmssd_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.hrv_sdsd)  AS sdsd_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.hrv_pnn50) AS pnn50_median,
                percentile_cont(0.5) WITHIN GROUP (ORDER BY pv.hrv_pnn20) AS pnn20_median,
                AVG(pv.hrv_lf_hf)                                   AS lf_hf_ratio,
                AVG(pv.hrv_hf)                                      AS hf_avg,
                AVG(pv.hrv_lf)                                      AS lf_avg,
                AVG(pv.hrv_vlf)                                     AS vlf_avg,
                AVG(pv.hrv_tp)                                      AS tp_avg
            FROM patient_vitals pv
            WHERE pv.bed_id = :bedId
              AND pv.patient_id = :patientId
              AND pv."time" BETWEEN :start AND :end
            GROUP BY bucket_time
            ORDER BY bucket_time ASC
            """, nativeQuery = true)
    List<VitalsAggregationRow> aggregateByBedIdAndPatientId(
            @Param("bedId") Long bedId,
            @Param("patientId") Long patientId,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("interval") String interval);

    /**
     * 获取指定床位最新一条数据（用于实时监控大屏）。
     */
    @Query(value = """
            SELECT * FROM patient_vitals
            WHERE bed_id = :bedId
            ORDER BY "time" DESC
            LIMIT 1
            """, nativeQuery = true)
    PatientVitals findLatestByBedId(@Param("bedId") Long bedId);

    /**
     * 获取指定患者最新一条数据。
     */
    @Query(value = """
            SELECT * FROM patient_vitals
            WHERE patient_id = :patientId
            ORDER BY "time" DESC
            LIMIT 1
            """, nativeQuery = true)
    PatientVitals findLatestByPatientId(@Param("patientId") Long patientId);
}

