package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Patient;

import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByIdCardNo(String idCardNo);

    Optional<Patient> findByPhoneNo(String phoneNo);
}

