package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.BedStatus;

@Entity
@Table(name = "bed")
public class Bed {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 病区代码，如“内科一区”
     */
    private String wardCode;
    /**
     * 房间号，如“101”
     */
    private String roomNo;
    /**
     * 床位号，如“1”或“A”
     */
    private String bedNo;
    /**
     * 绑定的设备序列号
     */
    private String deviceSn;
    @Enumerated(EnumType.STRING)
    private BedStatus status;
}
