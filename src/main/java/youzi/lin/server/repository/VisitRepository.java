package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.entity.Patient;
import youzi.lin.server.entity.Visit;
import youzi.lin.server.enums.VisitStatus;

import java.util.List;
import java.util.Optional;

/**
 * {@link Visit} 的 Spring Data JPA Repository。
 */
@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {

    List<Visit> findByPatient(Patient patient);

    List<Visit> findByPatientId(Long patientId);

    List<Visit> findByBed(Bed bed);

    List<Visit> findByBedId(Long bedId);

    List<Visit> findByStatus(VisitStatus status);

    /**
     * 查询指定床位上处于特定状态的就诊记录（如查询当前在院患者）。
     * <p>
     * 由于业务约束同一床位同时只有一条 ADMITTED 记录，返回 Optional 而非 List。
     * </p>
     */
    Optional<Visit> findByBedIdAndStatus(Long bedId, VisitStatus status);
}
