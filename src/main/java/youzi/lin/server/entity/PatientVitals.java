package youzi.lin.server.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 患者生命体征时序数据实体，对应 TimescaleDB 超表 {@code patient_vitals}。
 * <p>
 * 采用 {@code @IdClass} 复合主键（{@code time} + {@code bedId}），
 * 避免为超表添加不必要的自增序列。
 * </p>
 */
@Entity
@Table(name = "patient_vitals")
@IdClass(PatientVitalsId.class)
public class PatientVitals {

    /**
     * 数据采集时间戳（UTC），TimescaleDB 分区维度。
     */
    @Id
    @Column(name = "time", nullable = false)
    private Instant time;

    /**
     * 床位 ID，与 {@code bed} 表逻辑关联（不声明外键约束，避免超表限制）。
     */
    @Id
    @Column(name = "bed_id", nullable = false)
    private Long bedId;

    /**
     * 患者 ID，记录时拍摄快照，与 {@code patient} 表逻辑关联。
     */
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    // ── 基础生命体征 ──────────────────────────────────────────

    /** 心率（bpm）*/
    @Column(name = "hr")
    private Double hr;

    /** 信号质量指数（0~1，<0.5 时 HRV 字段为 null）*/
    @Column(name = "sqi")
    private Double sqi;

    /** 算法延迟（秒）*/
    @Column(name = "latency")
    private Double latency;

    // ── HRV 时域指标 ─────────────────────────────────────────

    @Column(name = "hrv_bpm")
    private Double hrvBpm;

    @Column(name = "hrv_ibi")
    private Double hrvIbi;

    @Column(name = "hrv_sdnn")
    private Double hrvSdnn;

    @Column(name = "hrv_sdsd")
    private Double hrvSdsd;

    @Column(name = "hrv_rmssd")
    private Double hrvRmssd;

    @Column(name = "hrv_pnn20")
    private Double hrvPnn20;

    @Column(name = "hrv_pnn50")
    private Double hrvPnn50;

    @Column(name = "hrv_hr_mad")
    private Double hrvHrMad;

    @Column(name = "hrv_sd1")
    private Double hrvSd1;

    @Column(name = "hrv_sd2")
    private Double hrvSd2;

    @Column(name = "hrv_s")
    private Double hrvS;

    @Column(name = "hrv_sd1_sd2")
    private Double hrvSd1Sd2;

    @Column(name = "hrv_breathingrate")
    private Double hrvBreathingrate;

    // ── HRV 频域指标 ─────────────────────────────────────────

    @Column(name = "hrv_vlf")
    private Double hrvVlf;

    @Column(name = "hrv_tp")
    private Double hrvTp;

    @Column(name = "hrv_hf")
    private Double hrvHf;

    @Column(name = "hrv_lf")
    private Double hrvLf;

    @Column(name = "hrv_lf_hf")
    private Double hrvLfHf;

    // ── Getters / Setters ────────────────────────────────────

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }

    public Long getBedId() { return bedId; }
    public void setBedId(Long bedId) { this.bedId = bedId; }

    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }

    public Double getHr() { return hr; }
    public void setHr(Double hr) { this.hr = hr; }

    public Double getSqi() { return sqi; }
    public void setSqi(Double sqi) { this.sqi = sqi; }

    public Double getLatency() { return latency; }
    public void setLatency(Double latency) { this.latency = latency; }

    public Double getHrvBpm() { return hrvBpm; }
    public void setHrvBpm(Double hrvBpm) { this.hrvBpm = hrvBpm; }

    public Double getHrvIbi() { return hrvIbi; }
    public void setHrvIbi(Double hrvIbi) { this.hrvIbi = hrvIbi; }

    public Double getHrvSdnn() { return hrvSdnn; }
    public void setHrvSdnn(Double hrvSdnn) { this.hrvSdnn = hrvSdnn; }

    public Double getHrvSdsd() { return hrvSdsd; }
    public void setHrvSdsd(Double hrvSdsd) { this.hrvSdsd = hrvSdsd; }

    public Double getHrvRmssd() { return hrvRmssd; }
    public void setHrvRmssd(Double hrvRmssd) { this.hrvRmssd = hrvRmssd; }

    public Double getHrvPnn20() { return hrvPnn20; }
    public void setHrvPnn20(Double hrvPnn20) { this.hrvPnn20 = hrvPnn20; }

    public Double getHrvPnn50() { return hrvPnn50; }
    public void setHrvPnn50(Double hrvPnn50) { this.hrvPnn50 = hrvPnn50; }

    public Double getHrvHrMad() { return hrvHrMad; }
    public void setHrvHrMad(Double hrvHrMad) { this.hrvHrMad = hrvHrMad; }

    public Double getHrvSd1() { return hrvSd1; }
    public void setHrvSd1(Double hrvSd1) { this.hrvSd1 = hrvSd1; }

    public Double getHrvSd2() { return hrvSd2; }
    public void setHrvSd2(Double hrvSd2) { this.hrvSd2 = hrvSd2; }

    public Double getHrvS() { return hrvS; }
    public void setHrvS(Double hrvS) { this.hrvS = hrvS; }

    public Double getHrvSd1Sd2() { return hrvSd1Sd2; }
    public void setHrvSd1Sd2(Double hrvSd1Sd2) { this.hrvSd1Sd2 = hrvSd1Sd2; }

    public Double getHrvBreathingrate() { return hrvBreathingrate; }
    public void setHrvBreathingrate(Double hrvBreathingrate) { this.hrvBreathingrate = hrvBreathingrate; }

    public Double getHrvVlf() { return hrvVlf; }
    public void setHrvVlf(Double hrvVlf) { this.hrvVlf = hrvVlf; }

    public Double getHrvTp() { return hrvTp; }
    public void setHrvTp(Double hrvTp) { this.hrvTp = hrvTp; }

    public Double getHrvHf() { return hrvHf; }
    public void setHrvHf(Double hrvHf) { this.hrvHf = hrvHf; }

    public Double getHrvLf() { return hrvLf; }
    public void setHrvLf(Double hrvLf) { this.hrvLf = hrvLf; }

    public Double getHrvLfHf() { return hrvLfHf; }
    public void setHrvLfHf(Double hrvLfHf) { this.hrvLfHf = hrvLfHf; }
}

