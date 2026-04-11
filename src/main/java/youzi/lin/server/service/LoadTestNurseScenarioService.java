package youzi.lin.server.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.entity.PatientVitals;
import youzi.lin.server.enums.VisitStatus;
import youzi.lin.server.repository.BedRepository;
import youzi.lin.server.repository.VisitRepository;
import youzi.lin.server.websocket.NurseWardBroadcastService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Profile("loadtest")
public class LoadTestNurseScenarioService {

    private static final double DEFAULT_SQI = 0.9;

    private final BedRepository bedRepository;
    private final VisitRepository visitRepository;
    private final AlarmService alarmService;
    private final AlarmStateTracker alarmStateTracker;
    private final PatientVitalsService patientVitalsService;
    private final NurseWardBroadcastService nurseWardBroadcastService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "loadtest-nurse-scenario");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<Long, ScenarioRun> activeRunsByBed = new ConcurrentHashMap<>();

    public LoadTestNurseScenarioService(BedRepository bedRepository,
                                        VisitRepository visitRepository,
                                        AlarmService alarmService,
                                        AlarmStateTracker alarmStateTracker,
                                        PatientVitalsService patientVitalsService,
                                        NurseWardBroadcastService nurseWardBroadcastService) {
        this.bedRepository = bedRepository;
        this.visitRepository = visitRepository;
        this.alarmService = alarmService;
        this.alarmStateTracker = alarmStateTracker;
        this.patientVitalsService = patientVitalsService;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public List<ScenarioBedTarget> listTargets(String wardCode) {
        List<Bed> beds = (wardCode == null || wardCode.isBlank())
                ? bedRepository.findAll()
                : bedRepository.findByWardCode(wardCode);

        return beds.stream().map(bed -> {
            Optional<Long> patientId = visitRepository.findByBedIdAndStatus(bed.getId(), VisitStatus.ADMITTED)
                    .map(visit -> visit.getPatient().getId());
            return new ScenarioBedTarget(
                    bed.getId(),
                    bed.getWardCode(),
                    bed.getRoomNo(),
                    bed.getBedNo(),
                    patientId.orElse(null)
            );
        }).toList();
    }

    public ScenarioStartResult startHrJumpScenario(Long bedId,
                                                    Long patientId,
                                                    int baselineSeconds,
                                                    int highSeconds,
                                                    int recoverySeconds) {
        if (bedId == null) {
            throw new IllegalArgumentException("bedId 不能为空");
        }

        var bed = bedRepository.findById(bedId)
                .orElseThrow(() -> new IllegalArgumentException("未找到 bedId=" + bedId));

        Long resolvedPatientId = resolvePatientId(bedId, patientId);
        if (resolvedPatientId == null) {
            throw new IllegalArgumentException("床位没有在院患者，且未指定 patientId");
        }

        int safeBaseline = Math.max(1, baselineSeconds);
        int safeHigh = Math.max(16, highSeconds);
        int safeRecovery = Math.max(11, recoverySeconds);

        var runId = UUID.randomUUID().toString();
        var startTime = Instant.now();
        var steps = buildSteps(safeBaseline, safeHigh, safeRecovery);

        ScenarioRun run = new ScenarioRun(runId, bedId, bed.getWardCode(), resolvedPatientId, startTime, steps.size());

        var previous = activeRunsByBed.put(bedId, run);
        if (previous != null) {
            previous.cancel();
        }

        AtomicInteger index = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            int i = index.getAndIncrement();
            if (i >= steps.size()) {
                run.markFinished();
                run.cancel();
                activeRunsByBed.remove(bedId, run);
                return;
            }

            var step = steps.get(i);
            Instant eventTime = startTime.plusSeconds(i);
            publishTick(bedId, resolvedPatientId, step.hr(), step.sqi(), eventTime);
            run.lastTickIndex.set(i);
            run.lastHr = step.hr();
            run.lastEventTime = eventTime;
        }, 0, 1, TimeUnit.SECONDS);

        run.future = future;

        return new ScenarioStartResult(
                runId,
                bedId,
                bed.getWardCode(),
                resolvedPatientId,
                startTime,
                startTime.plusSeconds(safeBaseline + 15L),
                startTime.plusSeconds(safeBaseline + safeHigh + 10L),
                steps.size()
        );
    }

    public ScenarioRunStatus getStatus(Long bedId) {
        if (bedId == null) {
            return null;
        }
        var run = activeRunsByBed.get(bedId);
        if (run == null) {
            return null;
        }
        return new ScenarioRunStatus(
                run.runId,
                run.bedId,
                run.wardCode,
                run.patientId,
                run.startedAt,
                run.finished,
                run.lastTickIndex.get(),
                run.totalTicks,
                run.lastHr,
                run.lastEventTime,
                alarmStateTracker.debugState(bedId)
        );
    }

    private void publishTick(Long bedId, Long patientId, double hr, double sqi, Instant eventTime) {
        nurseWardBroadcastService.publishUpdate(bedId, patientId, hr, sqi, eventTime);
        alarmService.evaluateVitals(bedId, patientId, hr, sqi, eventTime);
        patientVitalsService.saveAll(List.of(toVitalsEntity(bedId, patientId, hr, sqi, eventTime)));
    }

    private static PatientVitals toVitalsEntity(Long bedId, Long patientId, double hr, double sqi, Instant eventTime) {
        PatientVitals entity = new PatientVitals();
        entity.setTime(eventTime);
        entity.setBedId(bedId);
        entity.setPatientId(patientId);
        entity.setHr(hr);
        entity.setSqi(sqi);
        entity.setLatency(0.0);
        return entity;
    }

    private Long resolvePatientId(Long bedId, Long requestedPatientId) {
        if (requestedPatientId != null) {
            return requestedPatientId;
        }
        return visitRepository.findByBedIdAndStatus(bedId, VisitStatus.ADMITTED)
                .map(visit -> visit.getPatient().getId())
                .orElse(null);
    }

    private static List<ScenarioStep> buildSteps(int baselineSeconds, int highSeconds, int recoverySeconds) {
        List<ScenarioStep> steps = new ArrayList<>(baselineSeconds + highSeconds + recoverySeconds);
        for (int i = 0; i < baselineSeconds; i++) {
            steps.add(new ScenarioStep(115.0, DEFAULT_SQI));
        }
        for (int i = 0; i < highSeconds; i++) {
            steps.add(new ScenarioStep(125.0, DEFAULT_SQI));
        }
        for (int i = 0; i < recoverySeconds; i++) {
            steps.add(new ScenarioStep(105.0, DEFAULT_SQI));
        }
        return steps;
    }

    public record ScenarioBedTarget(Long bedId,
                                    String wardCode,
                                    String roomNo,
                                    String bedNo,
                                    Long admittedPatientId) {
    }

    public record ScenarioStartResult(String runId,
                                      Long bedId,
                                      String wardCode,
                                      Long patientId,
                                      Instant startedAt,
                                      Instant expectedTriggerAt,
                                      Instant expectedResolveAt,
                                      int plannedTicks) {
    }

    public record ScenarioRunStatus(String runId,
                                    Long bedId,
                                    String wardCode,
                                    Long patientId,
                                    Instant startedAt,
                                    boolean finished,
                                    int lastTickIndex,
                                    int totalTicks,
                                    Double lastHr,
                                    Instant lastEventTime,
                                    AlarmStateTracker.BedDebugState alarmState) {
    }

    private record ScenarioStep(double hr, double sqi) {
    }

    private static final class ScenarioRun {
        private final String runId;
        private final Long bedId;
        private final String wardCode;
        private final Long patientId;
        private final Instant startedAt;
        private final int totalTicks;
        private final AtomicInteger lastTickIndex = new AtomicInteger(-1);
        private volatile ScheduledFuture<?> future;
        private volatile boolean finished;
        private volatile Double lastHr;
        private volatile Instant lastEventTime;

        private ScenarioRun(String runId,
                            Long bedId,
                            String wardCode,
                            Long patientId,
                            Instant startedAt,
                            int totalTicks) {
            this.runId = runId;
            this.bedId = bedId;
            this.wardCode = wardCode;
            this.patientId = patientId;
            this.startedAt = startedAt;
            this.totalTicks = totalTicks;
        }

        private void markFinished() {
            this.finished = true;
        }

        private void cancel() {
            var current = future;
            if (current != null) {
                current.cancel(false);
            }
        }
    }
}


