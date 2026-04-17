package youzi.lin.server.service;

import org.springframework.stereotype.Component;
import youzi.lin.server.enums.AlarmType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报警内存状态追踪器。
 * <p>
 * 基于床位维度维护报警状态机，负责：
 * <ul>
 *     <li>触发/恢复持续时间防抖</li>
 *     <li>在线/离线状态切换</li>
 *     <li>输出报警边沿变化（触发或恢复）</li>
 * </ul>
 * </p>
 */
@Component
public class AlarmStateTracker {

    private static final long TACHY_TRIGGER_MS = 15_000L;
    private static final long TACHY_RESOLVE_MS = 10_000L;
    private static final long BRADY_TRIGGER_MS = 10_000L;
    private static final long BRADY_RESOLVE_MS = 10_000L;
    private static final long LOW_SQI_TRIGGER_MS = 20_000L;
    private static final long LOW_SQI_RESOLVE_MS = 10_000L;

    private static final long OFFLINE_TIMEOUT_MS = 30_000L;

    private final ConcurrentHashMap<Long, BedAlarmState> bedStates = new ConcurrentHashMap<>();

    /**
     * 会话建立时标记在线并刷新最后活动时间。
     */
    public void onSessionConnected(Long bedId, Long patientId, Instant now) {
        if (bedId == null) {
            return;
        }
        var state = bedStates.computeIfAbsent(bedId, _ -> new BedAlarmState(bedId));
        synchronized (state) {
            state.patientId = patientId;
            state.connected = true;
            state.lastSeenAtMs = now.toEpochMilli();
        }
    }

    /**
     * 会话断开时强制恢复生命体征类报警，并触发离线报警。
     */
    public List<Transition> onSessionDisconnected(Long bedId, Long patientId, Instant now) {
        if (bedId == null) {
            return List.of();
        }
        var state = bedStates.computeIfAbsent(bedId, _ -> new BedAlarmState(bedId));
        synchronized (state) {
            state.patientId = patientId;
            state.connected = false;
            var transitions = new ArrayList<Transition>();
            forceResolveVitalsAlarms(state, now, transitions);
            triggerOfflineIfInactive(state, now, transitions);
            return transitions;
        }
    }

    /**
     * 根据最新生命体征输入推进状态机。
     */
    public List<Transition> evaluateVitals(Long bedId,
                                           Long patientId,
                                           Double hr,
                                           Double sqi,
                                           Instant now) {
        if (bedId == null) {
            return List.of();
        }

        var state = bedStates.computeIfAbsent(bedId, _ -> new BedAlarmState(bedId));
        synchronized (state) {
            state.patientId = patientId;
            state.connected = true;
            long nowMs = now.toEpochMilli();
            state.lastSeenAtMs = nowMs;

            var transitions = new ArrayList<Transition>();

            if (isOfflineActive(state)) {
                resolveOfflineIfActive(state, now, transitions);
            }

            boolean tachyTrigger = hr != null && sqi != null && hr > 120.0 && sqi >= 0.5;
            boolean tachyResolve = hr != null && sqi != null && (hr < 110.0 || sqi < 0.4);
            evaluateRule(state, AlarmType.TACHYCARDIA,
                    tachyTrigger, tachyResolve,
                    TACHY_TRIGGER_MS, TACHY_RESOLVE_MS,
                    nowMs, now, transitions);

            boolean bradyTrigger = hr != null && sqi != null && hr < 50.0 && sqi >= 0.6;
            boolean bradyResolve = hr != null && sqi != null && (hr > 55.0 || sqi < 0.5);
            evaluateRule(state, AlarmType.BRADYCARDIA,
                    bradyTrigger, bradyResolve,
                    BRADY_TRIGGER_MS, BRADY_RESOLVE_MS,
                    nowMs, now, transitions);

            boolean lowSqiTrigger = sqi != null && sqi < 0.4;
            boolean lowSqiResolve = sqi != null && sqi > 0.5;
            evaluateRule(state, AlarmType.LOW_SQI,
                    lowSqiTrigger, lowSqiResolve,
                    LOW_SQI_TRIGGER_MS, LOW_SQI_RESOLVE_MS,
                    nowMs, now, transitions);

            return transitions;
        }
    }

    /**
     * 定时离线巡检：超过离线超时阈值的床位将触发离线边沿。
     */
    public List<Transition> checkTimeout(Instant now) {
        long nowMs = now.toEpochMilli();
        var transitions = new ArrayList<Transition>();

        for (var state : bedStates.values()) {
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (state) {
                if (!state.connected || state.lastSeenAtMs == 0L) {
                    continue;
                }
                if (nowMs - state.lastSeenAtMs < OFFLINE_TIMEOUT_MS) {
                    continue;
                }
                forceResolveVitalsAlarms(state, now, transitions);
                triggerOfflineIfInactive(state, now, transitions);
            }
        }

        return transitions;
    }

    /**
     * 调试接口：返回床位当前状态机内部状态快照。
     */
    public BedDebugState debugState(Long bedId) {
        if (bedId == null) {
            return null;
        }
        var state = bedStates.get(bedId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            var byType = new EnumMap<AlarmType, SingleAlarmDebugState>(AlarmType.class);
            for (var entry : state.byType.entrySet()) {
                var single = entry.getValue();
                byType.put(entry.getKey(), new SingleAlarmDebugState(
                        single.active,
                        single.triggerStartedAtMs,
                        single.resolveStartedAtMs
                ));
            }
            return new BedDebugState(
                    state.bedId,
                    state.patientId,
                    state.connected,
                    state.lastSeenAtMs,
                    byType
            );
        }
    }

    /**
     * 通用防抖判定：在“触发态”和“激活态”之间切换。
     */
    private void evaluateRule(BedAlarmState state,
                              AlarmType alarmType,
                              boolean triggerCondition,
                              boolean resolveCondition,
                              long triggerDurationMs,
                              long resolveDurationMs,
                              long nowMs,
                              Instant now,
                              List<Transition> transitions) {
        var alarmState = state.byType.get(alarmType);

        if (!alarmState.active) {
            if (triggerCondition) {
                if (alarmState.triggerStartedAtMs == 0L) {
                    alarmState.triggerStartedAtMs = nowMs;
                }
                if (nowMs - alarmState.triggerStartedAtMs >= triggerDurationMs) {
                    alarmState.active = true;
                    alarmState.triggerStartedAtMs = 0L;
                    alarmState.resolveStartedAtMs = 0L;
                    transitions.add(new Transition(state.bedId, state.patientId, alarmType, true, now));
                }
            } else {
                alarmState.triggerStartedAtMs = 0L;
            }
            return;
        }

        if (resolveCondition) {
            if (alarmState.resolveStartedAtMs == 0L) {
                alarmState.resolveStartedAtMs = nowMs;
            }
            if (nowMs - alarmState.resolveStartedAtMs >= resolveDurationMs) {
                alarmState.active = false;
                alarmState.resolveStartedAtMs = 0L;
                alarmState.triggerStartedAtMs = 0L;
                transitions.add(new Transition(state.bedId, state.patientId, alarmType, false, now));
            }
        } else {
            alarmState.resolveStartedAtMs = 0L;
        }
    }

    private void forceResolveVitalsAlarms(BedAlarmState state, Instant now, List<Transition> transitions) {
        resolveTypeIfActive(state, AlarmType.TACHYCARDIA, now, transitions);
        resolveTypeIfActive(state, AlarmType.BRADYCARDIA, now, transitions);
        resolveTypeIfActive(state, AlarmType.LOW_SQI, now, transitions);
    }

    private void resolveTypeIfActive(BedAlarmState state,
                                     AlarmType alarmType,
                                     Instant now,
                                     List<Transition> transitions) {
        var alarmState = state.byType.get(alarmType);
        if (!alarmState.active) {
            return;
        }
        alarmState.active = false;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        transitions.add(new Transition(state.bedId, state.patientId, alarmType, false, now));
    }

    private void triggerOfflineIfInactive(BedAlarmState state,
                                          Instant now,
                                          List<Transition> transitions) {
        var alarmState = state.byType.get(AlarmType.DEVICE_OFFLINE);
        if (alarmState.active) {
            return;
        }
        alarmState.active = true;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        transitions.add(new Transition(state.bedId, state.patientId, AlarmType.DEVICE_OFFLINE, true, now));
    }

    private void resolveOfflineIfActive(BedAlarmState state,
                                        Instant now,
                                        List<Transition> transitions) {
        var alarmState = state.byType.get(AlarmType.DEVICE_OFFLINE);
        if (!alarmState.active) {
            return;
        }
        alarmState.active = false;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        transitions.add(new Transition(state.bedId, state.patientId, AlarmType.DEVICE_OFFLINE, false, now));
    }

    private boolean isOfflineActive(BedAlarmState state) {
        return state.byType.get(AlarmType.DEVICE_OFFLINE).active;
    }

    private static final class BedAlarmState {
        private final Long bedId;
        private Long patientId;
        private boolean connected;
        private long lastSeenAtMs;
        private final EnumMap<AlarmType, SingleAlarmState> byType = new EnumMap<>(AlarmType.class);

        private BedAlarmState(Long bedId) {
            this.bedId = bedId;
            for (var type : AlarmType.values()) {
                byType.put(type, new SingleAlarmState());
            }
        }
    }

    private static final class SingleAlarmState {
        private boolean active;
        private long triggerStartedAtMs;
        private long resolveStartedAtMs;
    }

    public record Transition(Long bedId,
                             Long patientId,
                             AlarmType alarmType,
                             boolean triggered,
                             Instant eventTime) {
    }

    public record BedDebugState(Long bedId,
                                Long patientId,
                                boolean connected,
                                long lastSeenAtMs,
                                Map<AlarmType, SingleAlarmDebugState> byType) {
    }

    public record SingleAlarmDebugState(boolean active,
                                        long triggerStartedAtMs,
                                        long resolveStartedAtMs) {
    }
}







