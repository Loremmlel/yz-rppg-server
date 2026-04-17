package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.enums.BedStatus;

import java.util.List;
import java.util.Optional;

/**
 * {@link Bed} 的 Spring Data JPA Repository。
 */
@Repository
@SuppressWarnings("unused")
public interface BedRepository extends JpaRepository<Bed, Long> {

    /**
     * 判断病区编码是否存在。
     */
    boolean existsByWardCode(String wardCode);

    /**
     * 按病区查询床位。
     */
    List<Bed> findByWardCode(String wardCode);

    /**
     * 按床位状态查询。
     */
    //noinspection unused
    List<Bed> findByStatus(BedStatus status);

    /**
     * 按病区与状态查询床位。
     */
    //noinspection unused
    List<Bed> findByWardCodeAndStatus(String wardCode, BedStatus status);

    /**
     * 按病区与病房号查询床位。
     */
    List<Bed> findByWardCodeAndRoomNo(String wardCode, String roomNo);

    /**
     * 按设备序列号查询床位（WebSocket 连接时用于设备身份识别）。
     */
    //noinspection unused
    Optional<Bed> findByDeviceSn(String deviceSn);

    /**
     * 按病区/病房/床号定位唯一床位。
     */
    //noinspection unused
    Optional<Bed> findByWardCodeAndRoomNoAndBedNo(String wardCode, String roomNo, String bedNo);

    /**
     * 查询所有不重复的病区代码，用于构建病区选择列表。
     */
    @Query("SELECT DISTINCT b.wardCode FROM Bed b ORDER BY b.wardCode")
    List<String> findDistinctWardCodes();

    /**
     * 查询某病区下所有不重复的房间号，用于构建病房列表。
     */
    @Query("SELECT DISTINCT b.roomNo FROM Bed b WHERE b.wardCode = :wardCode ORDER BY b.roomNo")
    List<String> findDistinctRoomNosByWardCode(@Param("wardCode") String wardCode);
}

