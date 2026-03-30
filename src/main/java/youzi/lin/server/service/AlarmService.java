package youzi.lin.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import youzi.lin.server.entity.AlarmEvent;
import youzi.lin.server.enums.AlarmStatus;
import youzi.lin.server.enums.AlarmType;
import youzi.lin.server.repository.AlarmEventRepository;
import youzi.lin.server.websocket.NurseWardAlarmPublisher;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 报警编排服务：承接状态机判定、事件持久化与护士站实时推送。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final AlarmStateTracker tracker;
    private final AlarmEventRepository alarmEventRepository;
    private final NurseWardAlarmPublisher nurseWardBroadcastService;

    /** bedId -> (alarmType -> active alarmEventId) */
    private final ConcurrentHashMap<Long, EnumMap<AlarmType, Long>> activeEventIds = new ConcurrentHashMap<>();

    private final StatsLogger statsLogger = new StatsLogger();

    public AlarmService(AlarmStateTracker tracker,
                        AlarmEventRepository alarmEventRepository,
                        NurseWardAlarmPublisher nurseWardBroadcastService) {
        this.tracker = tracker;
        this.alarmEventRepository = alarmEventRepository;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
    }

    public void onSessionConnected(Long bedId, Long patientId) {
        if (bedId == null) {
            return;
        }
        tracker.onSessionConnected(bedId, patientId, Instant.now());
    }

    public void onSessionDisconnected(Long bedId, Long patientId) {
        if (bedId == null) {
            return;
        }
        applyTransitions(tracker.onSessionDisconnected(bedId, patientId, Instant.now()));
    }

    public void evaluateVitals(Long bedId,
                               Long patientId,
                               Double hr,
                               Double sqi,
                               Instant eventTime) {
        if (bedId == null) {
            return;
        }
        statsLogger.recordEvaluation();
        applyTransitions(tracker.evaluateVitals(bedId, patientId, hr, sqi, eventTime));
    }

    @Scheduled(fixedDelay = 5_000L)
    public void sweepOfflineTimeout() {
        applyTransitions(tracker.checkTimeout(Instant.now()));
    }

    private void applyTransitions(List<AlarmStateTracker.Transition> transitions) {
        if (transitions == null || transitions.isEmpty()) {
            return;
        }

        for (var transition : transitions) {
            if (transition.triggered()) {
                triggerEvent(transition);
            } else {
                resolveEvent(transition);
            }
        }
    }

    private void triggerEvent(AlarmStateTracker.Transition transition) {
        var entity = new AlarmEvent();
        entity.setPatientId(transition.patientId());
        entity.setBedId(transition.bedId());
        entity.setAlarmType(transition.alarmType());
        entity.setStatus(AlarmStatus.ACTIVE);
        entity.setTriggerTime(transition.eventTime());
        entity.setResolveTime(null);

        try {
            var saved = alarmEventRepository.save(entity);
            activeEventIds
                    .computeIfAbsent(transition.bedId(), _ -> new EnumMap<>(AlarmType.class))
                    .put(transition.alarmType(), saved.getId());

            nurseWardBroadcastService.publishAlarm(
                    transition.bedId(),
                    transition.patientId(),
                    transition.alarmType(),
                    AlarmStatus.ACTIVE,
                    alarmMessage(transition.alarmType()),
                    transition.eventTime(),
                    saved.getId()
            );
            statsLogger.recordTriggered();
        } catch (Exception e) {
            statsLogger.recordDbError();
            log.error("[Alarm] 触发报警入库失败 bedId={}, type={}, err={}",
                    transition.bedId(), transition.alarmType(), e.getMessage(), e);
        }
    }

    private void resolveEvent(AlarmStateTracker.Transition transition) {
        Long eventId = activeEventIds
                .computeIfAbsent(transition.bedId(), _ -> new EnumMap<>(AlarmType.class))
                .remove(transition.alarmType());

        if (eventId == null) {
            // 兼容服务重启后内存丢失：仍然广播解除，避免前端卡住 active 态。
            nurseWardBroadcastService.publishAlarm(
                    transition.bedId(),
                    transition.patientId(),
                    transition.alarmType(),
                    AlarmStatus.RESOLVED,
                    alarmResolveMessage(transition.alarmType()),
                    transition.eventTime(),
                    null
            );
            statsLogger.recordResolved();
            return;
        }

        try {
            alarmEventRepository.findById(eventId).ifPresent(entity -> {
                entity.setStatus(AlarmStatus.RESOLVED);
                entity.setResolveTime(transition.eventTime());
                alarmEventRepository.save(entity);
            });

            nurseWardBroadcastService.publishAlarm(
                    transition.bedId(),
                    transition.patientId(),
                    transition.alarmType(),
                    AlarmStatus.RESOLVED,
                    alarmResolveMessage(transition.alarmType()),
                    transition.eventTime(),
                    eventId
            );
            statsLogger.recordResolved();
        } catch (Exception e) {
            statsLogger.recordDbError();
            log.error("[Alarm] 解除报警入库失败 eventId={}, bedId={}, type={}, err={}",
                    eventId, transition.bedId(), transition.alarmType(), e.getMessage(), e);
        }
    }

    private static String alarmMessage(AlarmType alarmType) {
        return switch (alarmType) {
            case TACHYCARDIA -> "心率过速 (>120bpm)";
            case BRADYCARDIA -> "心率过缓 (<50bpm)";
            case LOW_SQI -> "信号质量差 (SQI<0.4)";
            case DEVICE_OFFLINE -> "设备离线或数据超时";
        };
    }

    private static String alarmResolveMessage(AlarmType alarmType) {
        return switch (alarmType) {
            case TACHYCARDIA -> "心率过速已恢复";
            case BRADYCARDIA -> "心率过缓已恢复";
            case LOW_SQI -> "信号质量已恢复";
            case DEVICE_OFFLINE -> "设备重新在线";
        };
    }

    /**
     * 报警链路聚合日志，风格与现有 gRPC/FrameBuffer 统计日志保持一致。
     */
    private static final class StatsLogger {

        private static final long INTERVAL_SECONDS = 30;

        private final AtomicLong evaluations = new AtomicLong();
        private final AtomicLong triggered = new AtomicLong();
        private final AtomicLong resolved = new AtomicLong();
        private final AtomicLong dbErrors = new AtomicLong();

        @SuppressWarnings("FieldCanBeLocal")
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "alarm-stats-logger");
                    t.setDaemon(true);
                    return t;
                });

        StatsLogger() {
            scheduler.scheduleAtFixedRate(
                    this::printAndReset,
                    INTERVAL_SECONDS,
                    INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }

        void recordEvaluation() { evaluations.incrementAndGet(); }
        void recordTriggered() { triggered.incrementAndGet(); }
        void recordResolved() { resolved.incrementAndGet(); }
        void recordDbError() { dbErrors.incrementAndGet(); }

        private void printAndReset() {
            long eval = evaluations.getAndSet(0);
            long trigger = triggered.getAndSet(0);
            long resolve = resolved.getAndSet(0);
            long dbErr = dbErrors.getAndSet(0);

            if (eval == 0 && trigger == 0 && resolve == 0 && dbErr == 0) {
                return;
            }

            log.info("[Alarm 统计] 过去 {}s：判定 {} 次，触发 {}，解除 {}，DB错误 {}",
                    INTERVAL_SECONDS, eval, trigger, resolve, dbErr);
        }
    }
}


