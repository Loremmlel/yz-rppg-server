package youzi.lin.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;
import youzi.lin.server.dto.FrameAnalysisResultDTO;
import youzi.lin.server.service.PatientVitalsService;
import youzi.lin.server.websocket.VideoFrameData;
import youzi.lin.server.websocket.WebSocketSessionManager;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.List;

/**
 * gRPC 客户端：将帧批次发送至 Python 分析服务，并将结果回传给 WebSocket 客户端。
 * <p>
 * 使用异步 Stub 避免阻塞 WebSocket 线程。
 * </p>
 * <p>
 * 收到分析结果后：
 * <ol>
 *     <li>解析完整 JSON（含 HRV 数据）</li>
 *     <li>向 WebSocket 客户端推送精简的 {@code {"heartRate": x, "sqi": x}} 文本消息</li>
 *     <li>将完整数据（含 HRV）持久化到 TimescaleDB</li>
 * </ol>
 * </p>
 */
@Component
public class GrpcFrameAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcFrameAnalysisClient.class);

    private final FrameAnalysisServiceGrpc.FrameAnalysisServiceStub asyncStub;
    private final WebSocketSessionManager sessionManager;
    private final PatientVitalsService vitalsService;
    private final ObjectMapper objectMapper;

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
        var requestBuilder = FrameBatchRequest.newBuilder()
                .setSessionId(sessionId);

        for (var frame : frames) {
            requestBuilder.addFrames(
                    Frame.newBuilder()
                            .setTimestampMs(frame.timestampMs())
                            .setImageData(ByteString.copyFrom(frame.imageData()))
                            .build()
            );
        }

        FrameBatchRequest request = requestBuilder.build();

        asyncStub.analyzeFrames(request, new StreamObserver<>() {
            @Override
            public void onNext(FrameBatchResponse response) {
                var payload = response.getPayload().toByteArray();
                log.debug("[gRPC] 会话 {} 收到分析结果，大小: {} 字节", sessionId, payload.length);

                FrameAnalysisResultDTO result;
                try {
                    result = objectMapper.readValue(payload, FrameAnalysisResultDTO.class);
                } catch (Exception e) {
                    log.error("[gRPC] 会话 {} 解析分析结果失败: {}", sessionId, e.getMessage(), e);
                    return;
                }

                // 1. 向 WebSocket 客户端推送精简心率指标（TextMessage）
                pushHeartRateToClient(sessionId, result);

                // 2. 持久化完整数据到 TimescaleDB
                persistVitals(sessionId, result);
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
            String json = objectMapper.writeValueAsString(output);
            boolean sent = sessionManager.sendTextMessage(sessionId, json);
            if (!sent) {
                log.warn("[gRPC] 会话 {} 已断开，丢弃推送结果", sessionId);
            }
        } catch (Exception e) {
            log.error("[gRPC] 会话 {} 构造 WebSocket 消息失败: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 将分析结果持久化到 TimescaleDB。
     * bedId 和 patientId 从 {@link WebSocketSessionManager} 的会话映射中读取。
     */
    private void persistVitals(String sessionId, FrameAnalysisResultDTO result) {
        Long bedId = sessionManager.getBedId(sessionId);
        Long patientId = sessionManager.getPatientId(sessionId);
        if (bedId == null || patientId == null) {
            log.debug("[gRPC] 会话 {} 缺少 bedId 或 patientId，跳过数据库写入", sessionId);
            return;
        }
        try {
            vitalsService.save(result, bedId, patientId, Instant.now());
        } catch (Exception e) {
            log.error("[gRPC] 会话 {} 写入 TimescaleDB 失败: {}", sessionId, e.getMessage(), e);
        }
    }
}
