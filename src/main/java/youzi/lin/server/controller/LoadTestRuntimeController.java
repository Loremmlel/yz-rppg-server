package youzi.lin.server.controller;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.List;

@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest")
public class LoadTestRuntimeController {

    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final java.lang.management.OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

    @GetMapping("/runtime-snapshot")
    public RuntimeSnapshot snapshot() {
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gcMxBean : gcMxBeans) {
            long c = gcMxBean.getCollectionCount();
            if (c > 0) {
                gcCount += c;
            }
            long t = gcMxBean.getCollectionTime();
            if (t > 0) {
                gcTimeMs += t;
            }
        }

        double processCpuPct = -1.0;
        if (osMxBean instanceof OperatingSystemMXBean hotspotMxBean) {
            double load = hotspotMxBean.getProcessCpuLoad();
            if (load >= 0) {
                processCpuPct = load * 100.0;
            }
        }

        return new RuntimeSnapshot(
                Instant.now().toString(),
                runtimeMXBean.getUptime(),
                processCpuPct,
                memoryMXBean.getHeapMemoryUsage().getUsed(),
                memoryMXBean.getNonHeapMemoryUsage().getUsed(),
                gcCount,
                gcTimeMs,
                threadMXBean.getThreadCount()
        );
    }

    public record RuntimeSnapshot(String timestamp,
                                  long uptimeMs,
                                  double processCpuPct,
                                  long heapUsedBytes,
                                  long nonHeapUsedBytes,
                                  long gcCount,
                                  long gcTimeMs,
                                  int threadCount) {
    }
}

