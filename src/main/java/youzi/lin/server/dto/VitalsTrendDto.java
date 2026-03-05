package youzi.lin.server.dto;

import java.time.Instant;

/**
 * 历史趋势聚合接口（{@code GET /api/vitals/trend}）的单个时间桶响应 DTO。
 * <p>
 * 按业务含义将聚合指标分为三组：基础生命体征、HRV 时域、HRV 频域。
 * </p>
 *
 * <pre>
 * {
 *   "bucketTime": "2026-03-04T10:00:00Z",
 *   "basicVitals": { "hrAvg": 75.5, "brAvg": 16.0, "sqiAvg": 0.85 },
 *   "hrvTimeDomain": { "sdnnMedian": 60.1, "rmssdMedian": 45.2, ... },
 *   "hrvFreqDomain": { "lfHfRatio": 1.5, ... }
 * }
 * </pre>
 */
public class VitalsTrendDto {

    /** time_bucket 时间窗口起始时刻（UTC） */
    private Instant bucketTime;

    /** 基础生命体征聚合 */
    private BasicVitals basicVitals;

    /** HRV 时域聚合（中位数，抗毛刺）*/
    private HrvTimeDomain hrvTimeDomain;

    /** HRV 频域聚合 */
    private HrvFreqDomain hrvFreqDomain;

    // ── 内部分组类 ────────────────────────────────────────────

    public static class BasicVitals {
        /** 心率均值（bpm）*/
        private Double hrAvg;
        /** 呼吸率均值（Hz）*/
        private Double brAvg;
        /** 信号质量指数均值 */
        private Double sqiAvg;

        public BasicVitals() {}

        public BasicVitals(Double hrAvg, Double brAvg, Double sqiAvg) {
            this.hrAvg = hrAvg;
            this.brAvg = brAvg;
            this.sqiAvg = sqiAvg;
        }

        public Double getHrAvg() { return hrAvg; }
        public void setHrAvg(Double hrAvg) { this.hrAvg = hrAvg; }

        public Double getBrAvg() { return brAvg; }
        public void setBrAvg(Double brAvg) { this.brAvg = brAvg; }

        public Double getSqiAvg() { return sqiAvg; }
        public void setSqiAvg(Double sqiAvg) { this.sqiAvg = sqiAvg; }
    }

    public static class HrvTimeDomain {
        /** SDNN 中位数（ms）*/
        private Double sdnnMedian;
        /** RMSSD 中位数（ms）*/
        private Double rmssdMedian;
        /** SDSD 中位数（ms）*/
        private Double sdsdMedian;
        /** pNN50 中位数 */
        private Double pnn50Median;
        /** pNN20 中位数 */
        private Double pnn20Median;

        public HrvTimeDomain() {}

        public HrvTimeDomain(Double sdnnMedian, Double rmssdMedian, Double sdsdMedian,
                             Double pnn50Median, Double pnn20Median) {
            this.sdnnMedian = sdnnMedian;
            this.rmssdMedian = rmssdMedian;
            this.sdsdMedian = sdsdMedian;
            this.pnn50Median = pnn50Median;
            this.pnn20Median = pnn20Median;
        }

        public Double getSdnnMedian() { return sdnnMedian; }
        public void setSdnnMedian(Double sdnnMedian) { this.sdnnMedian = sdnnMedian; }

        public Double getRmssdMedian() { return rmssdMedian; }
        public void setRmssdMedian(Double rmssdMedian) { this.rmssdMedian = rmssdMedian; }

        public Double getSdsdMedian() { return sdsdMedian; }
        public void setSdsdMedian(Double sdsdMedian) { this.sdsdMedian = sdsdMedian; }

        public Double getPnn50Median() { return pnn50Median; }
        public void setPnn50Median(Double pnn50Median) { this.pnn50Median = pnn50Median; }

        public Double getPnn20Median() { return pnn20Median; }
        public void setPnn20Median(Double pnn20Median) { this.pnn20Median = pnn20Median; }
    }

    public static class HrvFreqDomain {
        /** LF/HF 比值（自主神经平衡指标）*/
        private Double lfHfRatio;
        /** HF 均值（副交感活动）*/
        private Double hfAvg;
        /** LF 均值（交感 + 副交感活动）*/
        private Double lfAvg;
        /** VLF 均值 */
        private Double vlfAvg;
        /** 总功率均值 */
        private Double tpAvg;

        public HrvFreqDomain() {}

        public HrvFreqDomain(Double lfHfRatio, Double hfAvg, Double lfAvg,
                             Double vlfAvg, Double tpAvg) {
            this.lfHfRatio = lfHfRatio;
            this.hfAvg = hfAvg;
            this.lfAvg = lfAvg;
            this.vlfAvg = vlfAvg;
            this.tpAvg = tpAvg;
        }

        public Double getLfHfRatio() { return lfHfRatio; }
        public void setLfHfRatio(Double lfHfRatio) { this.lfHfRatio = lfHfRatio; }

        public Double getHfAvg() { return hfAvg; }
        public void setHfAvg(Double hfAvg) { this.hfAvg = hfAvg; }

        public Double getLfAvg() { return lfAvg; }
        public void setLfAvg(Double lfAvg) { this.lfAvg = lfAvg; }

        public Double getVlfAvg() { return vlfAvg; }
        public void setVlfAvg(Double vlfAvg) { this.vlfAvg = vlfAvg; }

        public Double getTpAvg() { return tpAvg; }
        public void setTpAvg(Double tpAvg) { this.tpAvg = tpAvg; }
    }

    // ── 构造器 / Getters / Setters ────────────────────────────

    public VitalsTrendDto() {}

    public Instant getBucketTime() { return bucketTime; }
    public void setBucketTime(Instant bucketTime) { this.bucketTime = bucketTime; }

    public BasicVitals getBasicVitals() { return basicVitals; }
    public void setBasicVitals(BasicVitals basicVitals) { this.basicVitals = basicVitals; }

    public HrvTimeDomain getHrvTimeDomain() { return hrvTimeDomain; }
    public void setHrvTimeDomain(HrvTimeDomain hrvTimeDomain) { this.hrvTimeDomain = hrvTimeDomain; }

    public HrvFreqDomain getHrvFreqDomain() { return hrvFreqDomain; }
    public void setHrvFreqDomain(HrvFreqDomain hrvFreqDomain) { this.hrvFreqDomain = hrvFreqDomain; }
}

