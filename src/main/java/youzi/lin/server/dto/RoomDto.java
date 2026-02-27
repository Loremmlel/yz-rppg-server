package youzi.lin.server.dto;

import java.util.List;

/**
 * 病房 DTO：包含房间号以及该房间内所有床位详情
 */
public class RoomDto {
    private String roomNo;
    private List<BedDetailDto> beds;

    public RoomDto() {}

    public RoomDto(String roomNo, List<BedDetailDto> beds) {
        this.roomNo = roomNo;
        this.beds = beds;
    }

    public String getRoomNo() { return roomNo; }
    public void setRoomNo(String roomNo) { this.roomNo = roomNo; }

    public List<BedDetailDto> getBeds() { return beds; }
    public void setBeds(List<BedDetailDto> beds) { this.beds = beds; }
}

