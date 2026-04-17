package youzi.lin.server.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import youzi.lin.server.service.AlarmService;

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
    private final AlarmService alarmService;

    public WebSocketHeartbeat(WebSocketSessionManager sessionManager,
                              NurseWardBroadcastService nurseWardBroadcastService,
                              AlarmService alarmService) {
        this.sessionManager = sessionManager;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
        this.alarmService = alarmService;
    }

    /**
     * 周期性发送心跳并清理不可靠连接。
     */
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
                    // 连续超阈值视为客户端不可达，主动断开避免占用会话与告警状态。
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

    /**
     * 关闭会话并执行统一清理。
     */
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

    /**
     * 清理会话关联状态。
     * <p>
     * 该方法只负责“连接断开后的联动收尾”：
     * 报警服务负责状态机边沿处理，广播服务负责订阅关系回收，
     * 会话管理器负责最终会话映射删除。
     * </p>
     */
    private void cleanupSession(WebSocketSession session) {
        Long bedId = sessionManager.getBedId(session.getId());
        Long patientId = sessionManager.getPatientId(session.getId());
        alarmService.onSessionDisconnected(bedId, patientId);
        nurseWardBroadcastService.removeSession(session.getId());
        sessionManager.remove(session.getId());
    }
}

