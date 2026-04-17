package youzi.lin.server.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import youzi.lin.server.repository.AlarmEventRepository;
import youzi.lin.server.service.LoadTestNurseScenarioService;

import java.util.Comparator;
import java.util.List;

/**
 * 护士站压测场景控制器。
 * <p>
 * 提供目标床位查询、心率阶跃场景触发、运行状态查看和报警事件回放接口，
 * 仅在 {@code loadtest} profile 启用。
 * </p>
 */
@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest/scenario")
public class LoadTestNurseScenarioController {

    private final LoadTestNurseScenarioService scenarioService;
    private final AlarmEventRepository alarmEventRepository;

    public LoadTestNurseScenarioController(LoadTestNurseScenarioService scenarioService,
                                           AlarmEventRepository alarmEventRepository) {
        this.scenarioService = scenarioService;
        this.alarmEventRepository = alarmEventRepository;
    }

    /**
     * 列出可用于场景注入的床位目标。
     *
     * @param wardCode 可选病区编码；为空时返回全部病区床位
     */
    @GetMapping("/targets")
    public List<LoadTestNurseScenarioService.ScenarioBedTarget> listTargets(
            @RequestParam(required = false) String wardCode) {
        return scenarioService.listTargets(wardCode);
    }

    /**
     * 启动心率阶跃场景（基线 -> 高心率 -> 恢复）。
     * <p>
     * 示例请求体：
     * <pre>
     * {
     *   "bedId": 12,
     *   "baselineSeconds": 2,
     *   "highSeconds": 20,
     *   "recoverySeconds": 12
     * }
     * </pre>
     * </p>
     */
    @PostMapping("/hr-jump")
    public LoadTestNurseScenarioService.ScenarioStartResult startHrJump(
            @RequestBody HrJumpScenarioRequest request) {
        int baselineSeconds = request.baselineSeconds() == null ? 2 : request.baselineSeconds();
        int highSeconds = request.highSeconds() == null ? 20 : request.highSeconds();
        int recoverySeconds = request.recoverySeconds() == null ? 12 : request.recoverySeconds();

        return scenarioService.startHrJumpScenario(
                request.bedId(),
                request.patientId(),
                baselineSeconds,
                highSeconds,
                recoverySeconds
        );
    }

    /**
     * 查询指定床位当前场景运行状态。
     */
    @GetMapping("/status")
    public LoadTestNurseScenarioService.ScenarioRunStatus status(@RequestParam Long bedId) {
        return scenarioService.getStatus(bedId);
    }

    /**
     * 查询指定床位最近报警事件（按触发时间排序返回）。
     */
    @GetMapping("/alarm-events")
    public List<AlarmEventView> listAlarmEvents(@RequestParam Long bedId,
                                                @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        return alarmEventRepository.findTop50ByBedIdOrderByTriggerTimeDesc(bedId).stream()
                .map(AlarmEventView::from)
                .limit(safeLimit)
                .sorted(Comparator.comparing(AlarmEventView::triggerTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 心率阶跃场景请求参数。
     */
    public record HrJumpScenarioRequest(Long bedId,
                                        Long patientId,
                                        Integer baselineSeconds,
                                        Integer highSeconds,
                                        Integer recoverySeconds) {
    }

    /**
     * 报警事件对外展示视图。
     */
    public record AlarmEventView(Long id,
                                 Long patientId,
                                 Long bedId,
                                 String alarmType,
                                 String status,
                                 java.time.Instant triggerTime,
                                 java.time.Instant resolveTime) {
        public static AlarmEventView from(youzi.lin.server.entity.AlarmEvent entity) {
            return new AlarmEventView(
                    entity.getId(),
                    entity.getPatientId(),
                    entity.getBedId(),
                    entity.getAlarmType() == null ? null : entity.getAlarmType().name(),
                    entity.getStatus() == null ? null : entity.getStatus().name(),
                    entity.getTriggerTime(),
                    entity.getResolveTime()
            );
        }
    }
}




