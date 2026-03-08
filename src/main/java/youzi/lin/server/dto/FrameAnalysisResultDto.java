package youzi.lin.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python gRPC 服务返回的完整分析结果 DTO。
 * <p>
 * 字段名与 Python 端 JSON 的键名严格对应（通过 {@code @JsonProperty} 映射）。
 * 当 SQI &lt; 0.5 时，Python 端返回 {@code "hrv": null}，此时 {@link #hrv} 为 {@code null}。
 * </p>
 */
public class FrameAnalysisResultDto {

    @JsonProperty("hr")
    private Double hr;

    /** 信号质量指数（Python 端 key 为 "SQI"）*/
    @JsonProperty("SQI")
    private Double sqi;

    @JsonProperty("latency")
    private Double latency;

    @JsonProperty("hrv")
    private HrvData hrv;

    // ── Getters / Setters ────────────────────────────────────

    public Double getHr() { return hr; }
    public void setHr(Double hr) { this.hr = hr; }

    public Double getSqi() { return sqi; }
    public void setSqi(Double sqi) { this.sqi = sqi; }

    public Double getLatency() { return latency; }
    public void setLatency(Double latency) { this.latency = latency; }

    public HrvData getHrv() { return hrv; }
    public void setHrv(HrvData hrv) { this.hrv = hrv; }

    /**
     * HRV 详细指标，当 SQI &lt; 0.5 时 Python 端不返回此对象（为 null）。
     */
    public static class HrvData {

        @JsonProperty("bpm")
        private Double bpm;

        @JsonProperty("ibi")
        private Double ibi;

        @JsonProperty("sdnn")
        private Double sdnn;

        @JsonProperty("sdsd")
        private Double sdsd;

        @JsonProperty("rmssd")
        private Double rmssd;

        @JsonProperty("pnn20")
        private Double pnn20;

        @JsonProperty("pnn50")
        private Double pnn50;

        @JsonProperty("hr_mad")
        private Double hrMad;

        @JsonProperty("sd1")
        private Double sd1;

        @JsonProperty("sd2")
        private Double sd2;

        @JsonProperty("s")
        private Double s;

        @JsonProperty("sd1/sd2")
        private Double sd1Sd2;

        @JsonProperty("breathingrate")
        private Double breathingRate;

        @JsonProperty("VLF")
        private Double vlf;

        @JsonProperty("TP")
        private Double tp;

        @JsonProperty("HF")
        private Double hf;

        @JsonProperty("LF")
        private Double lf;

        @JsonProperty("LF/HF")
        private Double lfHf;

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

        public Double getBreathingRate() { return breathingRate; }
        public void setBreathingRate(Double breathingRate) { this.breathingRate = breathingRate; }

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
}

