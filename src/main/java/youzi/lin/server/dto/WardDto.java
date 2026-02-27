package youzi.lin.server.dto;

import java.util.List;

/**
 * 病区 DTO：包含病区代码以及该病区内所有病房
 */
public record WardDto(
        String wardCode,
        List<RoomDto> rooms
) {}
