package youzi.lin.server.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import youzi.lin.server.config.LoadTestProperties;
import youzi.lin.server.repository.BedRepository;
import youzi.lin.server.websocket.NurseWardBroadcastService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 护士站压测数据泵。
 * <p>
 * 在 {@code loadtest} profile 下按固定频率生成模拟 HR/SQI 增量并广播，
 * 用于验证护士站订阅链路在高频更新下的吞吐和稳定性。
 * </p>
 */
@Service
@Profile("loadtest")
public class LoadTestNurseDataPump {

    private static final Logger log = LoggerFactory.getLogger(LoadTestNurseDataPump.class);

    private final LoadTestProperties loadTestProperties;
    private final BedRepository bedRepository;
    private final NurseWardBroadcastService nurseWardBroadcastService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "loadtest-nurse-data-pump");
        t.setDaemon(true);
        return t;
    });

    private volatile List<Long> wardBedIds = List.of();
    private final AtomicLong patientSequence = new AtomicLong(1);

    public LoadTestNurseDataPump(LoadTestProperties loadTestProperties,
                                 BedRepository bedRepository,
                                 NurseWardBroadcastService nurseWardBroadcastService) {
        this.loadTestProperties = loadTestProperties;
        this.bedRepository = bedRepository;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
    }

    /**
     * 根据配置在应用启动后自动开启数据泵。
     */
    @PostConstruct
    void startIfEnabled() {
        if (!loadTestProperties.getNursePump().isEnabled()) {
            return;
        }

        var wardCode = loadTestProperties.getNursePump().getWardCode();
        wardBedIds = bedRepository.findByWardCode(wardCode).stream()
                .map(youzi.lin.server.entity.Bed::getId)
                .toList();

        if (wardBedIds.isEmpty()) {
            log.warn("[LoadTest] nurse pump enabled but no beds found in wardCode={}", wardCode);
            return;
        }

        // 下限 10ms，避免配置错误导致 scheduleAtFixedRate 过度占用 CPU。
        long intervalMs = Math.max(10, loadTestProperties.getNursePump().getIntervalMs());
        scheduler.scheduleAtFixedRate(this::emitBatch, 1000, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[LoadTest] nurse pump started: wardCode={}, beds={}, intervalMs={}, patientsPerTick={}",
                wardCode,
                wardBedIds.size(),
                intervalMs,
                loadTestProperties.getNursePump().getPatientsPerTick());
    }

    /**
     * 应用关闭时停止后台调度线程。
     */
    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 生成并发布一批模拟患者更新。
     */
    private void emitBatch() {
        int updates = Math.max(1, loadTestProperties.getNursePump().getPatientsPerTick());
        for (int i = 0; i < updates; i++) {
            long bedId = wardBedIds.get(i % wardBedIds.size());
            long patientId = patientSequence.getAndIncrement();
            double hr = ThreadLocalRandom.current().nextDouble(58.0, 122.0);
            double sqi = ThreadLocalRandom.current().nextDouble(0.4, 1.0);
            nurseWardBroadcastService.publishUpdate(bedId, patientId, hr, sqi, Instant.now());
        }
    }
}


