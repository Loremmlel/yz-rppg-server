package youzi.lin.server.websocket;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import youzi.lin.server.service.FrameBufferService;

import java.net.URI;

/**
 * WebSocket 二进制帧处理器。
 * <p>
 * 负责：
 * <ul>
 *     <li>管理客户端会话的生命周期（连接 / 断开 / 异常）</li>
 *     <li>从连接 URI 中提取 {@code bedId} 查询参数并绑定到会话</li>
 *     <li>将收到的二进制帧交给 {@link FrameBufferService} 缓冲与解析</li>
 * </ul>
 */
public class BinaryFrameWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinaryFrameWebSocketHandler.class);

    private final WebSocketSessionManager sessionManager;
    private final FrameBufferService frameBufferService;

    public BinaryFrameWebSocketHandler(WebSocketSessionManager sessionManager,
                                       FrameBufferService frameBufferService) {
        this.sessionManager = sessionManager;
        this.frameBufferService = frameBufferService;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long bedId = extractBedId(session);
        sessionManager.register(session, bedId);
        log.info("[WebSocket] 客户端已连接：{}，远程地址：{}，床位ID：{}",
                session.getId(), session.getRemoteAddress(), bedId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] payload = message.getPayload().array();
        frameBufferService.addFrame(session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();
        sessionManager.remove(sessionId);
        frameBufferService.removeSession(sessionId);
        log.info("[WebSocket] 客户端已断开：{}，状态：{}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = session.getId();
        log.error("[WebSocket] 传输错误，会话：{}，原因：{}", sessionId, exception.getMessage());
        sessionManager.remove(sessionId);
        frameBufferService.removeSession(sessionId);
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception e) {
            log.warn("[WebSocket] 关闭会话 {} 时出错：{}", sessionId, e.getMessage());
        }
    }

    /**
     * 从 WebSocket 握手 URI 的查询参数中提取 {@code bedId}。
     *
     * @return 床位 ID；若未提供或格式非法则返回 {@code null}
     */
    private Long extractBedId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        try {
            String value = UriComponentsBuilder.fromUri(uri).build()
                    .getQueryParams().getFirst("bedId");
            return value != null ? Long.valueOf(value) : null;
        } catch (NumberFormatException e) {
            log.warn("[WebSocket] 会话 {} 的 bedId 参数非法：{}", session.getId(), uri.getQuery());
            return null;
        }
    }
}



