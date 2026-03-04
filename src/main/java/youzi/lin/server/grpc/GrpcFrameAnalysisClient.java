package youzi.lin.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;
import youzi.lin.server.dto.FrameAnalysisResultDTO;
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
                log.info("[gRPC] 会话 {} 收到分析结果，大小: {} 字节", sessionId, payload.length);

                FrameAnalysisResultDTO result;
                try {
                    result = objectMapper.readValue(payload, FrameAnalysisResultDTO.class);
                } catch (Exception e) {
                    log.error("[gRPC] 会话 {} 解析分析结果失败: {}", sessionId, e.getMessage(), e);
                    return;
                }

                // 1. 向 WebSocket 客户端推送精简心率指标（TextMessage）
                pushHeartRateToClient(sessionId, result);

                // 2. 追加到批次缓冲，条件满足时批量写入 TimescaleDB
                bufferAndFlush(sessionId, result);
            }

            @Override
            public void onError(Throwable t) {
                log.error("[gRPC] 会话 {} 分析请求失败: {}", sessionId, t.getMessage(), t);
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
    private void bufferAndFlush(String sessionId, FrameAnalysisResultDTO result) {
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
            log.info("[gRPC] 会话 {} 成功批量写入 {} 条记录", sessionId, entities.size());
        } catch (Exception e) {
            if (isShutdownCause(e)) {
                // 应用正在关闭，JPA/EntityManagerFactory 已先于 WebSocket 下线，
                // 此时写入失败属于预期行为，仅记录 WARN，不作为异常处理。
                log.warn("[gRPC] 会话 {} 应用关闭期间丢弃 {} 条未持久化记录",
                        sessionId, entities.size());
            } else {
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
    private void pushHeartRateToClient(String sessionId, FrameAnalysisResultDTO result) {
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
}
