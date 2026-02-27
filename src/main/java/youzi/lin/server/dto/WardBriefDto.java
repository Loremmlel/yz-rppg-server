package youzi.lin.server.dto;

/**
 * 病区简要信息 DTO：仅包含病区代码，用于列表展示
 */
public record WardBriefDto(
        String wardCode
) {}

