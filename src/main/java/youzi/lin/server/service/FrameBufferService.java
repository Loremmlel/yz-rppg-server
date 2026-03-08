package youzi.lin.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import youzi.lin.server.grpc.GrpcFrameAnalysisClient;
import youzi.lin.server.websocket.VideoFrameData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 帧缓冲服务。
 * <p>
 * 按 WebSocket 会话 ID 缓冲接收到的视频帧；
 * 每积攒 {@value #BATCH_SIZE} 帧后，将整批帧交给 gRPC 客户端发送至 Python 分析服务。
 * </p>
 * <p>
 * 高频帧写入场景下不对每批提交打印日志，改由内部 {@link StatsLogger} 每
 * 30 秒汇总一次关键指标，避免日志文件被重复行淹没。
 * </p>
 */
@Service
public class FrameBufferService {

    private static final Logger log = LoggerFactory.getLogger(FrameBufferService.class);
    private static final int BATCH_SIZE = 30;
    private static final int TIMESTAMP_BYTES = Long.BYTES; // 8

    private final ConcurrentHashMap<String, List<VideoFrameData>> buffers = new ConcurrentHashMap<>();
    private final GrpcFrameAnalysisClient grpcClient;

    /** 聚合统计日志记录器，与帧处理逻辑解耦 */
    private final StatsLogger statsLogger = new StatsLogger();

    public FrameBufferService(GrpcFrameAnalysisClient grpcClient) {
        this.grpcClient = grpcClient;
    }

    /**
     * 解析二进制帧并加入缓冲区。
     * <p>
     * 积攒满 {@value #BATCH_SIZE} 帧时异步提交至 gRPC 分析服务。
     * </p>
     *
     * @param sessionId WebSocket 会话 ID
     * @param payload   原始二进制数据：[8 字节大端时间戳] + [编码图像数据]
     */
    public void addFrame(String sessionId, byte[] payload) {
        if (payload.length <= TIMESTAMP_BYTES) {
            statsLogger.recordInvalidFrame();
            log.warn("[FrameBuffer] 会话 {} 收到无效帧（长度 {}），已丢弃", sessionId, payload.length);
            return;
        }

        // 解析时间戳（大端 int64）
        var timestampMs = ByteBuffer.wrap(payload, 0, TIMESTAMP_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();

        // 提取图像数据（JPEG / WebP 等，透传不解码）
        var imageData = new byte[payload.length - TIMESTAMP_BYTES];
        System.arraycopy(payload, TIMESTAMP_BYTES, imageData, 0, imageData.length);

        var frame = new VideoFrameData(timestampMs, imageData);

        // 使用 computeIfAbsent 保证 per-session list 的创建是原子的
        var sessionBuffer = buffers.computeIfAbsent(sessionId, _ -> new ArrayList<>(BATCH_SIZE));

        List<VideoFrameData> batch = null;
        synchronized (sessionBuffer) {
            sessionBuffer.add(frame);
            statsLogger.recordFrameAccepted();
            if (sessionBuffer.size() >= BATCH_SIZE) {
                batch = new ArrayList<>(sessionBuffer);
                sessionBuffer.clear();
            }
        }

        if (batch != null) {
            statsLogger.recordBatchDispatched();
            grpcClient.analyzeFramesAsync(sessionId, batch);
        }
    }

    /**
     * 清除指定会话的缓冲区（会话断开时调用）。
     * <p>
     * 未满批次的帧将被丢弃；因 gRPC 服务已在 {@link GrpcFrameAnalysisClient#flushAndRemoveSession}
     * 中完成剩余数据的持久化，此处只需释放内存即可。
     * </p>
     */
    public void removeSession(String sessionId) {
        var removed = buffers.remove(sessionId);
        if (removed != null && !removed.isEmpty()) {
            statsLogger.recordFramesDiscarded(removed.size());
            log.info("[FrameBuffer] 会话 {} 断开，丢弃 {} 帧未满批次", sessionId, removed.size());
        }
    }

    // ── 内部类 ────────────────────────────────────────────────

    /**
     * 聚合统计日志记录器：封装帧处理相关计数器、定时调度和打印逻辑，与业务代码完全解耦。
     * <p>
     * 外部只需调用各 {@code record*()} 方法累加指标；每隔 {@value #INTERVAL_SECONDS} 秒
     * 自动打印一条汇总 INFO 日志并将计数器归零，供下个周期重新统计。
     * 空闲周期（无帧到达）不会产生任何日志行。
     * </p>
     */
    private static final class StatsLogger {

        /** 聚合日志的统计周期（秒）。 */
        private static final long INTERVAL_SECONDS = 30;

        // ── 计数器 ──
        private final AtomicLong framesAccepted = new AtomicLong();
        private final AtomicLong invalidFrames = new AtomicLong();
        private final AtomicLong batchesDispatched = new AtomicLong();
        private final AtomicLong framesDiscarded = new AtomicLong();

        /** 守护线程调度器，应用退出时随 JVM 自动销毁。保持为字段以防止 GC 回收。 */
        @SuppressWarnings("FieldCanBeLocal")
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "frame-buffer-stats-logger");
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

        // ── 累加 API ──

        void recordFrameAccepted() { framesAccepted.incrementAndGet(); }
        void recordInvalidFrame() { invalidFrames.incrementAndGet(); }
        void recordBatchDispatched() { batchesDispatched.incrementAndGet(); }
        void recordFramesDiscarded(int n) { framesDiscarded.addAndGet(n); }

        /**
         * 打印周期汇总日志并将所有计数器归零，为下个周期重新计数。
         * 周期内无任何活动时静默跳过，避免产生噪音日志。
         */
        private void printAndReset() {
            long accepted = framesAccepted.getAndSet(0);
            long invalid = invalidFrames.getAndSet(0);
            long batches = batchesDispatched.getAndSet(0);
            long discarded = framesDiscarded.getAndSet(0);

            // 周期内无任何帧到达时跳过，保持日志安静
            if (accepted == 0 && invalid == 0) {
                return;
            }

            log.info("[FrameBuffer 统计] 过去 {}s：接收帧 {}，无效帧 {}，提交批次 {}，断开丢帧 {}",
                    INTERVAL_SECONDS, accepted, invalid, batches, discarded);
        }
    }
}

