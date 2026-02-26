package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.VisitStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "visit")
public class Visit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "id")
    private Patient patient;
    @ManyToOne
    @JoinColumn(name = "id")
    private Bed bed;
    private LocalDateTime admissionTime;
    private LocalDateTime dischargeTime;
    private VisitStatus status;
}
