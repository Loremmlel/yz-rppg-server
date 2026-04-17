package youzi.lin.server.websocket;

import youzi.lin.server.enums.AlarmStatus;
import youzi.lin.server.enums.AlarmType;

import java.time.Instant;

/**
 * 护士站报警推送能力抽象。
 */
public interface NurseWardAlarmPublisher {

    /**
     * 向订阅护士站会话发布报警边沿事件。
     *
     * @param bedId 床位 ID
     * @param patientId 患者 ID，可为 {@code null}
     * @param alarmType 报警类型
     * @param status 报警状态（触发或恢复）
     * @param message 报警提示文案
     * @param eventTime 报警事件时间，可为 {@code null}
     * @param alarmEventId 报警事件主键，可为 {@code null}
     */
    void publishAlarm(Long bedId,
                      Long patientId,
                      AlarmType alarmType,
                      AlarmStatus status,
                      String message,
                      Instant eventTime,
                      Long alarmEventId);
}

