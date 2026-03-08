package youzi.lin.server.dto;

import java.time.Instant;

/**
 * 健康报告生成请求参数。
 * <p>
 * {@code bedId} 和 {@code patientId} 均为必填项；
 * {@code interval} 支持简写格式（如 {@code "5m"}），由 Controller 层统一转换为
 * PostgreSQL INTERVAL 字符串后传入 Service。
 * </p>
 */
public class HealthReportRequest {

    private Long bedId;
    private Long patientId;
    private Instant startTime;
    private Instant endTime;

    /** 时间聚合粒度，如 {@code "1 minute"}、{@code "5 minutes"}、{@code "1 hour"} */
    private String interval;

    public HealthReportRequest() {
    }

    public Long getBedId() {
        return bedId;
    }

    public void setBedId(Long bedId) {
        this.bedId = bedId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }
}
