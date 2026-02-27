package youzi.lin.server.dto;

/**
 * 患者简要信息，用于床位详情中展示
 */
public record PatientBriefDto(
        Long id,
        String name,
        String gender
) {}
