package youzi.lin.server.dto;

import java.time.Instant;

/**
 * 护士站病区快照项。
 */
public record NurseWardPatientVitalsDto(
        Long patientId,
        Long bedId,
        String roomNo,
        String bedNo,
        Double hr,
        Double sqi,
        Instant eventTime
) {
}

