package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.AlarmStatus;
import youzi.lin.server.enums.AlarmType;

import java.time.Instant;

/**
 * 报警事件持久化实体。
 */
@Entity
//noinspection SqlResolve
@Table(name = "alarm_event", indexes = {
        @Index(name = "ix_alarm_event_bed_type_status", columnList = "bed_id, alarm_type, status"),
        @Index(name = "ix_alarm_event_patient_trigger_time", columnList = "patient_id, trigger_time DESC")
})
public class AlarmEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //noinspection SqlResolve
    @Column(name = "patient_id")
    private Long patientId;

    //noinspection SqlResolve
    @Column(name = "bed_id", nullable = false)
    private Long bedId;

    @Enumerated(EnumType.STRING)
    //noinspection SqlResolve
    @Column(name = "alarm_type", nullable = false, length = 32)
    private AlarmType alarmType;

    @Enumerated(EnumType.STRING)
    //noinspection SqlResolve
    @Column(name = "status", nullable = false, length = 16)
    private AlarmStatus status;

    //noinspection SqlResolve
    @Column(name = "trigger_time", nullable = false)
    private Instant triggerTime;

    //noinspection SqlResolve
    @Column(name = "resolve_time")
    private Instant resolveTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPatientId() { return patientId; }
    public void setPatientId(Long patientId) { this.patientId = patientId; }

    public Long getBedId() { return bedId; }
    public void setBedId(Long bedId) { this.bedId = bedId; }

    public AlarmType getAlarmType() { return alarmType; }
    public void setAlarmType(AlarmType alarmType) { this.alarmType = alarmType; }

    public AlarmStatus getStatus() { return status; }
    public void setStatus(AlarmStatus status) { this.status = status; }

    public Instant getTriggerTime() { return triggerTime; }
    public void setTriggerTime(Instant triggerTime) { this.triggerTime = triggerTime; }

    public Instant getResolveTime() { return resolveTime; }
    public void setResolveTime(Instant resolveTime) { this.resolveTime = resolveTime; }
}



