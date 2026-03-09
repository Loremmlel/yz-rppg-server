package youzi.lin.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;
import youzi.lin.server.dto.FrameAnalysisResultDto;
import youzi.lin.server.entity.PatientVitals;
import youzi.lin.server.service.PatientVitalsService;
import youzi.lin.server.websocket.VideoFrameData;
import youzi.lin.server.websocket.WebSocketSessionManager;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC 客户端：将帧批次发送至 Python 分析服务，并将结果回传给 WebSocket 客户端。
 * <p>
 * 收到分析结果后：
 * <ol>
 *     <li>解析完整 JSON（含 HRV 数据）</li>
 *     <li>向 WebSocket 客户端推送精简的 {@code {"hr": x, "sqi": x}} 文本消息</li>
 *     <li>追加到 per-session 写入缓冲；积攒到 {@value #BATCH_SIZE} 条
 *         或距上次刷盘超过 {@value #FLUSH_INTERVAL_MS} ms 时，批量写入 TimescaleDB</li>
 * </ol>
 * </p>
 */
@Component
public class GrpcFrameAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcFrameAnalysisClient.class);

    /** 每个会话积攒多少条结果后触发一次批量写入 */
    private static final int BATCH_SIZE = 20;

    /**
     * 距上次刷盘超过此毫秒数时，即使未满 {@value #BATCH_SIZE} 条也强制写入，
     * 防止低频场景下数据长时间停留在内存中。
     */
    private static final long FLUSH_INTERVAL_MS = 10_000;

    private final FrameAnalysisServiceGrpc.FrameAnalysisServiceStub asyncStub;
    private final WebSocketSessionManager sessionManager;
    private final PatientVitalsService vitalsService;
    private final ObjectMapper objectMapper;

    /** per-session 写入缓冲，key = sessionId */
    private final ConcurrentHashMap<String, SessionBuffer> buffers = new ConcurrentHashMap<>();

    /** 聚合统计日志记录器，负责计数、定时打印并重置，与业务逻辑解耦 */
    private final StatsLogger statsLogger = new StatsLogger();

    public GrpcFrameAnalysisClient(GrpcChannelFactory channelFactory,
                                   WebSocketSessionManager sessionManager,
                                   PatientVitalsService vitalsService) {
        this.sessionManager = sessionManager;
        this.vitalsService = vitalsService;
        this.objectMapper = new ObjectMapper();
        ManagedChannel channel = channelFactory.createChannel("frame-analysis");
        this.asyncStub = FrameAnalysisServiceGrpc.newStub(channel);
    }

    /**
     * 异步发送帧批次至 Python gRPC 服务。
     *
     * @param sessionId WebSocket 会话 ID，用于回送结果和关联床位/患者信息
     * @param frames    本批次的帧数据（通常 30 帧）
     */
    public void analyzeFramesAsync(String sessionId, List<VideoFrameData> frames) {
        var requestBuilder = FrameBatchRequest.newBuilder().setSessionId(sessionId);
        for (var frame : frames) {
            requestBuilder.addFrames(
                    Frame.newBuilder()
                            .setTimestampMs(frame.timestampMs())
                            .setImageData(ByteString.copyFrom(frame.imageData()))
                            .build()
            );
        }

        asyncStub.analyzeFrames(requestBuilder.build(), new StreamObserver<>() {
            @Override
            public void onNext(FrameBatchResponse response) {
                var payload = response.getPayload().toByteArray();
                log.debug("[gRPC] 会话 {} 收到分析结果，大小: {} 字节", sessionId, payload.length);

                FrameAnalysisResultDto result;
                try {
                    result = objectMapper.readValue(payload, FrameAnalysisResultDto.class);
                } catch (Exception e) {
                    statsLogger.recordParseError();
                    log.error("[gRPC] 会话 {} 解析分析结果失败: {}", sessionId, e.getMessage(), e);
                    return;
                }

                statsLogger.recordResultReceived();

                // 1. 向 WebSocket 客户端推送精简心率指标（TextMessage）
                pushHeartRateToClient(sessionId, result);

                // 2. 追加到批次缓冲，条件满足时批量写入 TimescaleDB
                bufferAndFlush(sessionId, result);
            }

            @Override
            public void onError(Throwable t) {
                statsLogger.recordGrpcError();
                // 默认日志记录（可选，如果不想完全屏蔽可以保留，但此处按要求由 statsLogger 提供周期性统计报表）
                log.debug("[gRPC] 会話 {} 分析请求失败: {}", sessionId, t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.debug("[gRPC] 会话 {} 分析调用完成", sessionId);
            }
        });
    }

    /**
     * 会话断开时调用：强制刷盘剩余缓冲并清除该会话的缓冲区。
     * 由 {@code BinaryFrameWebSocketHandler} 在连接关闭 / 传输错误时调用。
     */
    public void flushAndRemoveSession(String sessionId) {
        var buf = buffers.remove(sessionId);
        if (buf == null) return;
        List<PatientVitals> toFlush;
        synchronized (buf) {
            toFlush = buf.drain();
        }
        if (!toFlush.isEmpty()) {
            log.info("[gRPC] 会话 {} 断开，强制刷盘剩余 {} 条记录", sessionId, toFlush.size());
            safeSaveAll(toFlush, sessionId);
        }
    }

    // ── 私有方法 ──────────────────────────────────────────────

    /**
     * 将当前分析结果追加到 per-session 缓冲；
     * 达到 {@value #BATCH_SIZE} 条或超过 {@value #FLUSH_INTERVAL_MS} ms 时批量写入。
     */
    private void bufferAndFlush(String sessionId, FrameAnalysisResultDto result) {
        Long bedId = sessionManager.getBedId(sessionId);
        Long patientId = sessionManager.getPatientId(sessionId);
        if (bedId == null || patientId == null) {
            log.debug("[gRPC] 会话 {} 缺少 bedId 或 patientId，跳过数据库写入", sessionId);
            return;
        }

        PatientVitals entity = vitalsService.toEntity(result, bedId, patientId, Instant.now());
        var buf = buffers.computeIfAbsent(sessionId, _ -> new SessionBuffer());

        List<PatientVitals> toFlush = null;
        synchronized (buf) {
            buf.add(entity);
            if (buf.shouldFlush()) {
                toFlush = buf.drain();
            }
        }

        if (toFlush != null) {
            log.debug("[gRPC] 会话 {} 触发批量写入，共 {} 条", sessionId, toFlush.size());
            safeSaveAll(toFlush, sessionId);
        }
    }

    private void safeSaveAll(List<PatientVitals> entities, String sessionId) {
        try {
            vitalsService.saveAll(entities);
            statsLogger.recordRowsPersisted(entities.size());
            log.debug("[gRPC] 会话 {} 批量写入 {} 条记录", sessionId, entities.size());
        } catch (Exception e) {
            if (isShutdownCause(e)) {
                // 应用正在关闭，JPA/EntityManagerFactory 已先于 WebSocket 下线，
                // 此时写入失败属于预期行为，仅记录 WARN，不作为异常处理。
                statsLogger.recordRowsDropped(entities.size());
                log.warn("[gRPC] 会话 {} 应用关闭期间丢弃 {} 条未持久化记录",
                        sessionId, entities.size());
            } else {
                statsLogger.recordDbError();
                log.error("[gRPC] 会话 {} 批量写入 TimescaleDB 失败: {}", sessionId, e.getMessage(), e);
            }
        }
    }

    /**
     * 判断异常是否由应用关闭（EntityManagerFactory 已关闭）引起。
     * 向上递归检查 cause 链，避免被包装层干扰。
     */
    private static boolean isShutdownCause(Throwable t) {
        while (t != null) {
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().contains("closed")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 向 WebSocket 客户端推送精简心率指标 JSON：
     * <pre>{"hr": 82.26, "sqi": 0.54}</pre>
     */
    private void pushHeartRateToClient(String sessionId, FrameAnalysisResultDto result) {
        try {
            ObjectNode output = objectMapper.createObjectNode();
            if (result.getHr() != null) {
                output.put("hr", result.getHr());
            } else {
                output.putNull("hr");
            }
            if (result.getSqi() != null) {
                output.put("sqi", result.getSqi());
            } else {
                output.putNull("sqi");
            }
            boolean sent = sessionManager.sendTextMessage(sessionId, objectMapper.writeValueAsString(output));
            if (!sent) {
                log.warn("[gRPC] 会话 {} 已断开，丢弃推送结果", sessionId);
            }
        } catch (Exception e) {
            log.error("[gRPC] 会话 {} 构造 WebSocket 消息失败: {}", sessionId, e.getMessage(), e);
        }
    }

    // ── 内部类 ────────────────────────────────────────────────

    /**
     * 单个会话的写入缓冲区，非线程安全（调用方需持锁操作）。
     */
    private static final class SessionBuffer {

        private final List<PatientVitals> items = new ArrayList<>(BATCH_SIZE);
        private long lastFlushMs = System.currentTimeMillis();

        void add(PatientVitals entity) {
            items.add(entity);
        }

        /** 条数达上限，或距上次刷盘超过时间阈值 */
        boolean shouldFlush() {
            return items.size() >= BATCH_SIZE
                    || (System.currentTimeMillis() - lastFlushMs) >= FLUSH_INTERVAL_MS;
        }

        /** 取出所有待写条目并重置计时器 */
        List<PatientVitals> drain() {
            var snapshot = new ArrayList<>(items);
            items.clear();
            lastFlushMs = System.currentTimeMillis();
            return snapshot;
        }
    }

    /**
     * 聚合统计日志记录器：封装计数器、定时调度和打印逻辑，与业务代码完全解耦。
     * <p>
     * 外部只需调用各 {@code record*()} 方法累加指标；每隔 {@value #INTERVAL_SECONDS} 秒
     * 自动打印一条汇总 INFO 日志并将计数器归零，供下个周期重新统计。
     * 空闲周期（无结果也无错误）不会产生任何日志行。
     * </p>
     */
    private static final class StatsLogger {

        /** 聚合日志的统计周期（秒）。 */
        private static final long INTERVAL_SECONDS = 30;

        // ── 计数器 ──
        private final AtomicLong resultsReceived = new AtomicLong();
        private final AtomicLong rowsPersisted = new AtomicLong();
        private final AtomicLong rowsDropped = new AtomicLong();
        private final AtomicLong parseErrors = new AtomicLong();
        private final AtomicLong grpcErrors = new AtomicLong();
        private final AtomicLong dbErrors = new AtomicLong();

        /** 守护线程调度器，应用退出时随 JVM 自动销毁。保持为字段以防止 GC 回收。 */
        @SuppressWarnings("FieldCanBeLocal")
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "grpc-stats-logger");
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

        void recordResultReceived() { resultsReceived.incrementAndGet(); }
        void recordRowsPersisted(int n) { rowsPersisted.addAndGet(n); }
        void recordRowsDropped(int n) { rowsDropped.addAndGet(n); }
        void recordParseError() { parseErrors.incrementAndGet(); }
        void recordGrpcError() { grpcErrors.incrementAndGet(); }
        void recordDbError() { dbErrors.incrementAndGet(); }

        /**
         * 打印周期汇总日志并将所有计数器归零，为下个周期重新计数。
         * 周期内无任何活动时静默跳过，避免产生噪音日志。
         */
        private void printAndReset() {
            long received = resultsReceived.getAndSet(0);
            long persisted = rowsPersisted.getAndSet(0);
            long dropped = rowsDropped.getAndSet(0);
            long parseErr = parseErrors.getAndSet(0);
            long grpcErr = grpcErrors.getAndSet(0);
            long dbErr = dbErrors.getAndSet(0);

            // 周期内无任何活动时跳过，保持日志安静
            if (received == 0 && grpcErr == 0) {
                return;
            }

            log.info("[gRPC 统计] 过去 {}s：收到结果 {}，持久化 {} 行，丢弃 {} 行，"
                    + "解析失败 {}，gRPC 错误 {}，DB 错误 {}",
                    INTERVAL_SECONDS, received, persisted, dropped,
                    parseErr, grpcErr, dbErr);
        }
    }
}
