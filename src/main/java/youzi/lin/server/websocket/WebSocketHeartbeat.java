package youzi.lin.server.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int MAX_MISSED_PING_COUNT = 3;

    private final WebSocketSessionManager sessionManager;
    private final NurseWardBroadcastService nurseWardBroadcastService;

    public WebSocketHeartbeat(WebSocketSessionManager sessionManager,
                              NurseWardBroadcastService nurseWardBroadcastService) {
        this.sessionManager = sessionManager;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void sendPing() {
        long now = System.currentTimeMillis();
        for (var session : sessionManager.allSessions()) {
            String sessionId = session.getId();
            if (!session.isOpen()) {
                cleanupSession(session);
                continue;
            }

            Long lastClientMessageAt = sessionManager.getLastClientMessageAt(sessionId);
            if (lastClientMessageAt == null || now - lastClientMessageAt >= HEARTBEAT_INTERVAL_MS) {
                int missedCount = sessionManager.incrementMissedPingCount(sessionId);
                if (missedCount >= MAX_MISSED_PING_COUNT) {
                    log.warn("[Heartbeat] 会话 {} 连续 {} 次心跳未响应，主动断开",
                            sessionId, missedCount);
                    closeAndCleanup(session, CloseStatus.SESSION_NOT_RELIABLE);
                    continue;
                }
            }

            boolean pingSent = sessionManager.sendPingMessage(sessionId);
            if (!pingSent) {
                log.warn("[Heartbeat] 向会话 {} 发送 Ping 失败，移除会话", sessionId);
                closeAndCleanup(session, CloseStatus.SERVER_ERROR);
            }
        }
    }

    private void closeAndCleanup(WebSocketSession session, CloseStatus closeStatus) {
        try {
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (Exception e) {
            log.debug("[Heartbeat] 关闭会话 {} 失败：{}", session.getId(), e.getMessage());
        } finally {
            cleanupSession(session);
        }
    }

    private void cleanupSession(WebSocketSession session) {
        nurseWardBroadcastService.removeSession(session.getId());
        sessionManager.remove(session.getId());
    }
}

