package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Patient;

import java.util.Optional;

/**
 * {@link Patient} 的 Spring Data JPA Repository。
 */
@Repository
public interface PatientRepository extends JpaRepository<Patient, Long> {

    /**
     * 按身份证号查找患者（用于入院登记时的重复检查）。
     */
    Optional<Patient> findByIdCardNo(String idCardNo);

    /**
     * 按手机号查找患者（用于快速检索）。
     */
    Optional<Patient> findByPhoneNo(String phoneNo);
}
