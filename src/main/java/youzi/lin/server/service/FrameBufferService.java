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

/**
 * 帧缓冲服务。
 * <p>
 * 按 WebSocket 会话 ID 缓冲接收到的视频帧；
 * 每积攒 {@value #BATCH_SIZE} 帧后，将整批帧交给 gRPC 客户端发送至 Python 分析服务。
 */
@Service
public class FrameBufferService {

    private static final Logger log = LoggerFactory.getLogger(FrameBufferService.class);
    private static final int BATCH_SIZE = 30;
    private static final int TIMESTAMP_BYTES = Long.BYTES; // 8

    private final ConcurrentHashMap<String, List<VideoFrameData>> buffers = new ConcurrentHashMap<>();
    private final GrpcFrameAnalysisClient grpcClient;

    public FrameBufferService(GrpcFrameAnalysisClient grpcClient) {
        this.grpcClient = grpcClient;
    }

    /**
     * 解析二进制帧并加入缓冲区。
     *
     * @param sessionId WebSocket 会话 ID
     * @param payload   原始二进制数据：[8 字节大端时间戳] + [JPEG]
     */
    public void addFrame(String sessionId, byte[] payload) {
        if (payload.length <= TIMESTAMP_BYTES) {
            log.warn("[FrameBuffer] 会话 {} 收到无效帧（长度 {}），已丢弃", sessionId, payload.length);
            return;
        }

        // 解析时间戳（大端 int64）
        var timestampMs = ByteBuffer.wrap(payload, 0, TIMESTAMP_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();

        // 提取 JPEG 数据
        var jpeg = new byte[payload.length - TIMESTAMP_BYTES];
        System.arraycopy(payload, TIMESTAMP_BYTES, jpeg, 0, jpeg.length);

        var frame = new VideoFrameData(timestampMs, jpeg);

        // 使用 computeIfAbsent 保证 per-session list 的创建是原子的
        var sessionBuffer = buffers.computeIfAbsent(sessionId, _ -> new ArrayList<>(BATCH_SIZE));

        List<VideoFrameData> batch = null;
        synchronized (sessionBuffer) {
            sessionBuffer.add(frame);
            if (sessionBuffer.size() >= BATCH_SIZE) {
                batch = new ArrayList<>(sessionBuffer);
                sessionBuffer.clear();
            }
        }

        if (batch != null) {
            log.info("[FrameBuffer] 会话 {} 已积攒 {} 帧，提交 gRPC 分析", sessionId, batch.size());
            grpcClient.analyzeFramesAsync(sessionId, batch);
        }
    }

    /**
     * 清除指定会话的缓冲区（会话断开时调用）。
     */
    public void removeSession(String sessionId) {
        var removed = buffers.remove(sessionId);
        if (removed != null && !removed.isEmpty()) {
            log.info("[FrameBuffer] 会话 {} 断开，丢弃 {} 帧未满批次", sessionId, removed.size());
        }
    }
}


