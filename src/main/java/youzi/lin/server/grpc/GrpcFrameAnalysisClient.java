package youzi.lin.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;
import youzi.lin.server.websocket.VideoFrameData;
import youzi.lin.server.websocket.WebSocketSessionManager;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.util.List;

/**
 * gRPC 客户端：将帧批次发送至 Python 分析服务，并将结果回传给 WebSocket 客户端。
 * <p>
 * 使用异步 Stub 避免阻塞 WebSocket 线程。
 */
@Component
public class GrpcFrameAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcFrameAnalysisClient.class);

    private final FrameAnalysisServiceGrpc.FrameAnalysisServiceStub asyncStub;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public GrpcFrameAnalysisClient(GrpcChannelFactory channelFactory,
                                   WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
        ManagedChannel channel = channelFactory.createChannel("frame-analysis");
        this.asyncStub = FrameAnalysisServiceGrpc.newStub(channel);
    }

    /**
     * 异步发送帧批次至 Python gRPC 服务。
     *
     * @param sessionId WebSocket 会话 ID，用于回送结果
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
                log.info("[gRPC] 会话 {} 收到分析结果，大小: {} 字节", sessionId, payload.length);

                String jsonOutput;
                try {
                    var root = objectMapper.readTree(payload);
                    var hrNode = root.get("hr");
                    var sqiNode = root.hasNonNull("SQI") ? root.get("SQI") : root.get("sqi");
                    ObjectNode output = objectMapper.createObjectNode();
                    output.put("hr", hrNode == null || hrNode.isNull() ? null : hrNode.asDouble());
                    output.put("sqi", sqiNode == null || sqiNode.isNull() ? null : sqiNode.asDouble());
                    jsonOutput = objectMapper.writeValueAsString(output);
                } catch (Exception e) {
                    log.error("[gRPC] 会话 {} 解析分析结果失败: {}", sessionId, e.getMessage(), e);
                    return;
                }

                boolean sent = sessionManager.sendTextMessage(sessionId, jsonOutput);
                if (!sent) {
                    log.warn("[gRPC] 会话 {} 已断开，丢弃分析结果", sessionId);
                }
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
}
