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

    @GetMapping("/targets")
    public List<LoadTestNurseScenarioService.ScenarioBedTarget> targets(
            @RequestParam(required = false) String wardCode) {
        return scenarioService.listTargets(wardCode);
    }

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

    @GetMapping("/status")
    public LoadTestNurseScenarioService.ScenarioRunStatus status(@RequestParam Long bedId) {
        return scenarioService.getStatus(bedId);
    }

    @GetMapping("/alarm-events")
    public List<AlarmEventView> alarmEvents(@RequestParam Long bedId,
                                            @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        return alarmEventRepository.findTop50ByBedIdOrderByTriggerTimeDesc(bedId).stream()
                .map(AlarmEventView::from)
                .limit(safeLimit)
                .sorted(Comparator.comparing(AlarmEventView::triggerTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public record HrJumpScenarioRequest(Long bedId,
                                        Long patientId,
                                        Integer baselineSeconds,
                                        Integer highSeconds,
                                        Integer recoverySeconds) {
    }

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




