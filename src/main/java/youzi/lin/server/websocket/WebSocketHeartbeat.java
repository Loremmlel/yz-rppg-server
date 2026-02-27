package youzi.lin.server.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 定时心跳任务。
 * <p>
 * 每 30 秒向所有活跃 WebSocket 会话发送 Ping 帧，
 * 检测不可达的客户端并主动清理死连接。
 */
@Component
public class WebSocketHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHeartbeat.class);

    private final WebSocketSessionManager sessionManager;

    public WebSocketHeartbeat(WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Scheduled(fixedRate = 30_000)
    public void sendPing() {
        for (var session : sessionManager.allSessions()) {
            if (!session.isOpen()) {
                sessionManager.remove(session.getId());
                continue;
            }
            synchronized (session) {
                try {
                    session.sendMessage(new PingMessage());
                } catch (Exception e) {
                    log.warn("[Heartbeat] 向会话 {} 发送 Ping 失败，移除会话：{}",
                            session.getId(), e.getMessage());
                    sessionManager.remove(session.getId());
                }
            }
        }
    }
}

