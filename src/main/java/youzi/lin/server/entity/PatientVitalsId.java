package youzi.lin.server.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code PatientVitals} 的复合主键类。
 * <p>
 * TimescaleDB 超表不使用自增主键；
 * 使用 {@code time} + {@code bedId} 作为联合主键，满足 JPA 要求，
 * 同时与 TimescaleDB 按时间分区的设计保持一致。
 * </p>
 * 注意：字段名必须与 {@code PatientVitals} 中带 {@code @Id} 的字段名完全匹配。
 */
public class PatientVitalsId implements Serializable {

    private Instant time;
    private Long bedId;

    public PatientVitalsId() {}

    public PatientVitalsId(Instant time, Long bedId) {
        this.time = time;
        this.bedId = bedId;
    }

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }

    public Long getBedId() { return bedId; }
    public void setBedId(Long bedId) { this.bedId = bedId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatientVitalsId that)) return false;
        return Objects.equals(time, that.time) && Objects.equals(bedId, that.bedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, bedId);
    }
}


