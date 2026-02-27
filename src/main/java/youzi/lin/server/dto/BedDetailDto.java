package youzi.lin.server.dto;

/**
 * 床位详情 DTO：包含床位基本信息、状态，以及当前在住患者（若有）
 * status 取值：EMPTY / OCCUPIED / MAINTAINING / RESERVED
 * currentPatient 仅在 status=OCCUPIED 时不为 null
 */
public record BedDetailDto(
        Long id,
        String bedNo,
        String deviceSn,
        String status,
        PatientBriefDto currentPatient
) {}
