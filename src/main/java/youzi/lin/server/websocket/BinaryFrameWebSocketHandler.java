package youzi.lin.server.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import youzi.lin.server.service.FrameBufferService;

/**
 * WebSocket 二进制帧处理器。
 * <p>
 * 负责：
 * <ul>
 *     <li>管理客户端会话的生命周期（连接 / 断开 / 异常）</li>
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
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionManager.register(session);
        log.info("[WebSocket] 客户端已连接：{}，远程地址：{}", session.getId(),
                session.getRemoteAddress());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] payload = message.getPayload().array();
        frameBufferService.addFrame(session.getId(), payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
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
}



