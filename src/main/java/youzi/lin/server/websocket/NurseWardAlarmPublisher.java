package youzi.lin.server.websocket;

import youzi.lin.server.enums.AlarmStatus;
import youzi.lin.server.enums.AlarmType;

import java.time.Instant;

/**
 * 护士站报警推送能力抽象。
 */
public interface NurseWardAlarmPublisher {

    void publishAlarm(Long bedId,
                      Long patientId,
                      AlarmType alarmType,
                      AlarmStatus status,
                      String message,
                      Instant eventTime,
                      Long alarmEventId);
}

