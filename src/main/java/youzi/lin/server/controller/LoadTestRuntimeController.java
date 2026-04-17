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

/**
 * Loadtest 运行时快照接口。
 * <p>
 * 仅在 {@code loadtest} profile 下启用，用于采集压测期间的 JVM/进程资源指标，
 * 便于和吞吐、延迟曲线做时间对齐分析。
 * </p>
 *
 * <p>示例：</p>
 * <pre>
 * GET /api/loadtest/runtime-snapshot
 * </pre>
 */
@RestController
@Profile("loadtest")
@RequestMapping("/api/loadtest")
public class LoadTestRuntimeController {

    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> garbageCollectorBeans = ManagementFactory.getGarbageCollectorMXBeans();
    private final java.lang.management.OperatingSystemMXBean operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();

    /**
     * 返回当前时刻的单点运行时指标。
     * <p>
     * 该接口是“快照”而非“区间统计”：
     * GC 指标为 JVM 累计值，CPU 为当前进程负载估算值。
     * </p>
     */
    @GetMapping("/runtime-snapshot")
    public RuntimeSnapshot snapshot() {
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gcBean : garbageCollectorBeans) {
            long c = gcBean.getCollectionCount();
            if (c > 0) {
                gcCount += c;
            }
            long t = gcBean.getCollectionTime();
            if (t > 0) {
                gcTimeMs += t;
            }
        }

        double processCpuPct = -1.0;
        if (operatingSystemBean instanceof OperatingSystemMXBean hotspotMxBean) {
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

    /**
     * 运行时快照 DTO。
     *
     * @param timestamp ISO-8601 时间戳
     * @param uptimeMs  JVM 启动后运行时长（毫秒）
     * @param processCpuPct 进程 CPU 占用百分比，无法获取时为 -1
     * @param heapUsedBytes 堆内存已使用字节数
     * @param nonHeapUsedBytes 非堆内存已使用字节数
     * @param gcCount GC 累计次数
     * @param gcTimeMs GC 累计耗时（毫秒）
     * @param threadCount 当前线程数
     */
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

