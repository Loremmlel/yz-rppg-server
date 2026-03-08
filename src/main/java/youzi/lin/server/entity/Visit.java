package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.VisitStatus;

import java.time.Instant;

/**
 * 住院就诊记录实体，对应数据库表 {@code visit}。
 * <p>
 * 一条 Visit 记录代表患者一次完整的住院过程：
 * 从入院（{@link VisitStatus#ADMITTED}）到出院（{@link VisitStatus#DISCHARGED}）
 * 或转科/取消等终态。同一床位同一时刻只允许存在一条 ADMITTED 状态的记录。
 * </p>
 */
@Entity
@Table(name = "visit")
public class Visit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "bed_id")
    private Bed bed;

    private Instant admissionTime;

    /** 出院时间；患者仍在院时为 {@code null} */
    private Instant dischargeTime;

    @Enumerated(EnumType.STRING)
    private VisitStatus status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }

    public Bed getBed() { return bed; }
    public void setBed(Bed bed) { this.bed = bed; }

    public Instant getAdmissionTime() { return admissionTime; }
    public void setAdmissionTime(Instant admissionTime) { this.admissionTime = admissionTime; }

    public Instant getDischargeTime() { return dischargeTime; }
    public void setDischargeTime(Instant dischargeTime) { this.dischargeTime = dischargeTime; }

    public VisitStatus getStatus() { return status; }
    public void setStatus(VisitStatus status) { this.status = status; }
}
