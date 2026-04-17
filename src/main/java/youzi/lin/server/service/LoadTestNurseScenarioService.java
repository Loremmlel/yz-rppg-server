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

/**
 * 护士站压测场景编排服务。
 * <p>
 * 职责：
 * <ul>
 *     <li>按床位生成可复现的心率阶跃序列（基线、高值、恢复）</li>
 *     <li>按秒驱动护士站增量推送、报警评估和时序数据入库</li>
 *     <li>维护场景运行状态，支持同床位任务覆盖</li>
 * </ul>
 * </p>
 */
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

    /**
     * 列出可注入压测场景的床位目标。
     *
     * @param wardCode 可选病区编码；为空时返回全量床位
     */
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

    /**
     * 启动（或覆盖）指定床位的心率阶跃场景。
     *
     * @param bedId 目标床位
     * @param patientId 可选患者 ID；为空时自动解析当前在院患者
     * @param baselineSeconds 基线阶段时长
     * @param highSeconds 高心率阶段时长
     * @param recoverySeconds 恢复阶段时长
     */
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

        int baselineDurationSeconds = Math.max(1, baselineSeconds);
        // 高心率至少 16 秒，确保触发窗口（15 秒）内有稳定样本。
        int highDurationSeconds = Math.max(16, highSeconds);
        // 恢复至少 11 秒，覆盖恢复窗口（10 秒）并留出 1 秒抖动余量。
        int recoveryDurationSeconds = Math.max(11, recoverySeconds);

        var runId = UUID.randomUUID().toString();
        var startTime = Instant.now();
        var steps = buildSteps(baselineDurationSeconds, highDurationSeconds, recoveryDurationSeconds);

        ScenarioRun run = new ScenarioRun(runId, bedId, bed.getWardCode(), resolvedPatientId, startTime, steps.size());

        var previous = activeRunsByBed.put(bedId, run);
        if (previous != null) {
            previous.cancel();
        }

        AtomicInteger index = new AtomicInteger(0);
        run.future = scheduler.scheduleAtFixedRate(() -> {
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

        return new ScenarioStartResult(
                runId,
                bedId,
                bed.getWardCode(),
                resolvedPatientId,
                startTime,
                startTime.plusSeconds(baselineDurationSeconds + 15L),
                startTime.plusSeconds(baselineDurationSeconds + highDurationSeconds + 10L),
                steps.size()
        );
    }

    /**
     * 查询床位当前场景状态；无活动任务时返回 {@code null}。
     */
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

    /**
     * 发布单个场景 tick 到各下游：护士站增量、报警状态机和时序库。
     */
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

    /**
     * 构建 1Hz 场景序列：基线（115）-> 高值（125）-> 恢复（105）。
     */
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

    /**
     * 可触发场景的床位目标。
     */
    public record ScenarioBedTarget(Long bedId,
                                    String wardCode,
                                    String roomNo,
                                    String bedNo,
                                    Long admittedPatientId) {
    }

    /**
     * 场景启动结果。
     */
    public record ScenarioStartResult(String runId,
                                      Long bedId,
                                      String wardCode,
                                      Long patientId,
                                      Instant startedAt,
                                      Instant expectedTriggerAt,
                                      Instant expectedResolveAt,
                                      int plannedTicks) {
    }

    /**
     * 场景实时状态。
     */
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


