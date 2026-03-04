package youzi.lin.server.dto;

import java.time.Instant;

/**
 * JPA 投影接口，用于接收 {@code patient_vitals} 表的 TimescaleDB 原生聚合查询结果。
 * <p>
 * 每个方法名必须与原生 SQL 中的列别名（大小写不敏感）严格对应。
 * </p>
 */
public interface VitalsAggregationRow {

    /** time_bucket 时间窗口起始时刻 */
    Instant getBucketTime();

    // ── 基础生命体征聚合 ──────────────────────────────────────

    Double getHrAvg();
    Double getBrAvg();
    Double getSqiAvg();

    // ── HRV 时域中位数（抗毛刺） ─────────────────────────────

    Double getSdnnMedian();
    Double getRmssdMedian();
    Double getSdsdMedian();
    Double getPnn50Median();
    Double getPnn20Median();

    // ── HRV 频域均值 ─────────────────────────────────────────

    Double getLfHfRatio();
    Double getHfAvg();
    Double getLfAvg();
    Double getVlfAvg();
    Double getTpAvg();
}

