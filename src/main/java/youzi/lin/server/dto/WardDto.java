package youzi.lin.server.dto;

import java.util.List;

/**
 * 病区 DTO：包含病区代码以及该病区内所有病房
 */
public class WardDto {
    private String wardCode;
    private List<RoomDto> rooms;

    public WardDto() {}

    public WardDto(String wardCode, List<RoomDto> rooms) {
        this.wardCode = wardCode;
        this.rooms = rooms;
    }

    public String getWardCode() { return wardCode; }
    public void setWardCode(String wardCode) { this.wardCode = wardCode; }

    public List<RoomDto> getRooms() { return rooms; }
    public void setRooms(List<RoomDto> rooms) { this.rooms = rooms; }
}

