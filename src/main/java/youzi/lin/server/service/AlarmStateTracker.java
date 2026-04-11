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
 * 报警内存状态追踪器：负责持续时间防抖与触发/恢复判定。
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

    private void evaluateRule(BedAlarmState state,
                              AlarmType type,
                              boolean triggerCondition,
                              boolean resolveCondition,
                              long triggerDurationMs,
                              long resolveDurationMs,
                              long nowMs,
                              Instant now,
                              List<Transition> out) {
        var alarmState = state.byType.get(type);

        if (!alarmState.active) {
            if (triggerCondition) {
                if (alarmState.triggerStartedAtMs == 0L) {
                    alarmState.triggerStartedAtMs = nowMs;
                }
                if (nowMs - alarmState.triggerStartedAtMs >= triggerDurationMs) {
                    alarmState.active = true;
                    alarmState.triggerStartedAtMs = 0L;
                    alarmState.resolveStartedAtMs = 0L;
                    out.add(new Transition(state.bedId, state.patientId, type, true, now));
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
                out.add(new Transition(state.bedId, state.patientId, type, false, now));
            }
        } else {
            alarmState.resolveStartedAtMs = 0L;
        }
    }

    private void forceResolveVitalsAlarms(BedAlarmState state, Instant now, List<Transition> out) {
        resolveTypeIfActive(state, AlarmType.TACHYCARDIA, now, out);
        resolveTypeIfActive(state, AlarmType.BRADYCARDIA, now, out);
        resolveTypeIfActive(state, AlarmType.LOW_SQI, now, out);
    }

    private void resolveTypeIfActive(BedAlarmState state,
                                     AlarmType type,
                                     Instant now,
                                     List<Transition> out) {
        var alarmState = state.byType.get(type);
        if (!alarmState.active) {
            return;
        }
        alarmState.active = false;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        out.add(new Transition(state.bedId, state.patientId, type, false, now));
    }

    private void triggerOfflineIfInactive(BedAlarmState state,
                                          Instant now,
                                          List<Transition> out) {
        var alarmState = state.byType.get(AlarmType.DEVICE_OFFLINE);
        if (alarmState.active) {
            return;
        }
        alarmState.active = true;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        out.add(new Transition(state.bedId, state.patientId, AlarmType.DEVICE_OFFLINE, true, now));
    }

    private void resolveOfflineIfActive(BedAlarmState state,
                                        Instant now,
                                        List<Transition> out) {
        var alarmState = state.byType.get(AlarmType.DEVICE_OFFLINE);
        if (!alarmState.active) {
            return;
        }
        alarmState.active = false;
        alarmState.triggerStartedAtMs = 0L;
        alarmState.resolveStartedAtMs = 0L;
        out.add(new Transition(state.bedId, state.patientId, AlarmType.DEVICE_OFFLINE, false, now));
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







