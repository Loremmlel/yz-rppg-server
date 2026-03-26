package youzi.lin.server.dto;

import java.time.Instant;

/**
 * 病区快照查询投影：每名在院患者最新一条 HR/SQI。
 */
public interface WardLatestVitalsRow {

    Long getPatientId();

    Long getBedId();

    String getRoomNo();

    String getBedNo();

    Double getHr();

    Double getSqi();

    Instant getEventTime();
}

