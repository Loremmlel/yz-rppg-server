package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.enums.BedStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface BedRepository extends JpaRepository<Bed, Long> {

    List<Bed> findByWardCode(String wardCode);

    List<Bed> findByStatus(BedStatus status);

    List<Bed> findByWardCodeAndStatus(String wardCode, BedStatus status);

    Optional<Bed> findByDeviceSn(String deviceSn);

    Optional<Bed> findByWardCodeAndRoomNoAndBedNo(String wardCode, String roomNo, String bedNo);
}

