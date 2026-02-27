package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    List<Bed> findByWardCodeAndRoomNo(String wardCode, String roomNo);

    Optional<Bed> findByDeviceSn(String deviceSn);

    Optional<Bed> findByWardCodeAndRoomNoAndBedNo(String wardCode, String roomNo, String bedNo);

    /**
     * 查询所有不重复的病区代码
     */
    @Query("SELECT DISTINCT b.wardCode FROM Bed b ORDER BY b.wardCode")
    List<String> findDistinctWardCodes();

    /**
     * 查询某病区下所有不重复的房间号
     */
    @Query("SELECT DISTINCT b.roomNo FROM Bed b WHERE b.wardCode = :wardCode ORDER BY b.roomNo")
    List<String> findDistinctRoomNosByWardCode(@Param("wardCode") String wardCode);
}

