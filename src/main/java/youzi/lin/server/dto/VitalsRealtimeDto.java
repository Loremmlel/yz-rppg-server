package youzi.lin.server.dto;

import java.time.Instant;

/**
 * 实时监护接口（{@code GET /api/vitals/realtime}）的单条记录响应 DTO。
 * <p>
 * 将数据库平铺字段按业务含义分组后返回给前端，避免灾难性的字段展示。
 * </p>
 */
public class VitalsRealtimeDto {

    /** 数据采集时间戳（UTC ISO-8601） */
    private Instant time;

    /** 床位 ID */
    private Long bedId;

    /** 患者 ID */
    private Long patientId;

    /** 基础生命体征分组 */
    private BasicVitals basicVitals;

    /** HRV 时域指标分组（SQI < 0.5 时为 null）*/
    private HrvTimeDomain hrvTimeDomain;

    /** HRV 频域指标分组（SQI < 0.5 时为 null）*/
    private HrvFreqDomain hrvFreqDomain;

    // ── 内部分组类 ────────────────────────────────────────────

    public static class BasicVitals {
        /** 心率（bpm）*/
        private Double hr;
        /** 信号质量指数（0~1）*/
        private Double sqi;
        /** 呼吸率（Hz）*/
        private Double breathingRate;
        /** 算法延迟（秒）*/
        private Double latency;

        public BasicVitals() {}

        public BasicVitals(Double hr, Double sqi, Double breathingRate, Double latency) {
            this.hr = hr;
            this.sqi = sqi;
            this.breathingRate = breathingRate;
            this.latency = latency;
        }

        public Double getHr() { return hr; }
        public void setHr(Double hr) { this.hr = hr; }

        public Double getSqi() { return sqi; }
        public void setSqi(Double sqi) { this.sqi = sqi; }

        public Double getBreathingRate() { return breathingRate; }
        public void setBreathingRate(Double breathingRate) { this.breathingRate = breathingRate; }

        public Double getLatency() { return latency; }
        public void setLatency(Double latency) { this.latency = latency; }
    }

    public static class HrvTimeDomain {
        private Double bpm;
        private Double ibi;
        private Double sdnn;
        private Double sdsd;
        private Double rmssd;
        private Double pnn20;
        private Double pnn50;
        private Double hrMad;
        private Double sd1;
        private Double sd2;
        private Double s;
        private Double sd1Sd2;

        public HrvTimeDomain() {}

        public Double getBpm() { return bpm; }
        public void setBpm(Double bpm) { this.bpm = bpm; }

        public Double getIbi() { return ibi; }
        public void setIbi(Double ibi) { this.ibi = ibi; }

        public Double getSdnn() { return sdnn; }
        public void setSdnn(Double sdnn) { this.sdnn = sdnn; }

        public Double getSdsd() { return sdsd; }
        public void setSdsd(Double sdsd) { this.sdsd = sdsd; }

        public Double getRmssd() { return rmssd; }
        public void setRmssd(Double rmssd) { this.rmssd = rmssd; }

        public Double getPnn20() { return pnn20; }
        public void setPnn20(Double pnn20) { this.pnn20 = pnn20; }

        public Double getPnn50() { return pnn50; }
        public void setPnn50(Double pnn50) { this.pnn50 = pnn50; }

        public Double getHrMad() { return hrMad; }
        public void setHrMad(Double hrMad) { this.hrMad = hrMad; }

        public Double getSd1() { return sd1; }
        public void setSd1(Double sd1) { this.sd1 = sd1; }

        public Double getSd2() { return sd2; }
        public void setSd2(Double sd2) { this.sd2 = sd2; }

        public Double getS() { return s; }
        public void setS(Double s) { this.s = s; }

        public Double getSd1Sd2() { return sd1Sd2; }
        public void setSd1Sd2(Double sd1Sd2) { this.sd1Sd2 = sd1Sd2; }
    }

    public static class HrvFreqDomain {
        private Double vlf;
        private Double tp;
        private Double hf;
        private Double lf;
        private Double lfHf;

        public HrvFreqDomain() {}

        public Double getVlf() { return vlf; }
        public void setVlf(Double vlf) { this.vlf = vlf; }

        public Double getTp() { return tp; }
        public void setTp(Double tp) { this.tp = tp; }

        public Double getHf() { return hf; }
        public void setHf(Double hf) { this.hf = hf; }

        public Double getLf() { return lf; }
        public void setLf(Double lf) { this.lf = lf; }

        public Double getLfHf() { return lfHf; }
        public void setLfHf(Double lfHf) { this.lfHf = lfHf; }
    }

    // ── 构造器 / Getters / Setters ────────────────────────────

    public VitalsRealtimeDto() {}

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }

    public Long getBedId() { return bedId; }
    public void setBedId(Long bedId) { this.bedId = bedId; }

    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }

    public BasicVitals getBasicVitals() { return basicVitals; }
    public void setBasicVitals(BasicVitals basicVitals) { this.basicVitals = basicVitals; }

    public HrvTimeDomain getHrvTimeDomain() { return hrvTimeDomain; }
    public void setHrvTimeDomain(HrvTimeDomain hrvTimeDomain) { this.hrvTimeDomain = hrvTimeDomain; }

    public HrvFreqDomain getHrvFreqDomain() { return hrvFreqDomain; }
    public void setHrvFreqDomain(HrvFreqDomain hrvFreqDomain) { this.hrvFreqDomain = hrvFreqDomain; }
}

