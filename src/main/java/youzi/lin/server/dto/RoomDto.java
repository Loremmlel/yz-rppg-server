package youzi.lin.server.dto;

import java.util.List;

/**
 * 病房 DTO：包含房间号以及该房间内所有床位详情
 */
public record RoomDto(
        String roomNo,
        List<BedDetailDto> beds
) {}
