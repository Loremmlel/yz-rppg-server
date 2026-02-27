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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWardCode() { return wardCode; }
    public void setWardCode(String wardCode) { this.wardCode = wardCode; }

    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }

    public String getBedNo() { return bedNo; }
    public void setBedNo(String bedNo) { this.bedNo = bedNo; }

    public String getDeviceSn() { return deviceSn; }
    public void setDeviceSn(String deviceSn) { this.deviceSn = deviceSn; }

    public BedStatus getStatus() { return status; }
    public void setStatus(BedStatus status) { this.status = status; }
}
