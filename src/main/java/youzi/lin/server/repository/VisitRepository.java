package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.entity.Patient;
import youzi.lin.server.entity.Visit;
import youzi.lin.server.enums.VisitStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisitRepository extends JpaRepository<Visit, Long> {

    List<Visit> findByPatient(Patient patient);

    List<Visit> findByPatientId(Long patientId);

    List<Visit> findByBed(Bed bed);

    List<Visit> findByBedId(Long bedId);

    List<Visit> findByStatus(VisitStatus status);

    Optional<Visit> findByBedIdAndStatus(Long bedId, VisitStatus status);
}

