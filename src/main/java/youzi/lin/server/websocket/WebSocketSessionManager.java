package youzi.lin.server.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的 WebSocket 会话管理器。
 * <p>
 * 维护所有活跃客户端会话，提供注册 / 注销 / 按 ID 发送消息等能力。
 * 同一 session 的写操作需加同步锁（WebSocketSession 本身非线程安全）。
 * <p>
 * 同时维护 sessionId ↔ bedId 的双向映射，供业务层按床位查找会话。
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** sessionId → bedId */
    private final ConcurrentHashMap<String, Long> sessionBedMap = new ConcurrentHashMap<>();

    /** bedId → sessionId（同一床位同时仅允许一个活跃连接） */
    private final ConcurrentHashMap<Long, String> bedSessionMap = new ConcurrentHashMap<>();

    /**
     * 注册会话并绑定床位 ID。
     *
     * @param session WebSocket 会话
     * @param bedId   客户端 query 中携带的床位 ID，可能为 {@code null}（未提供时）
     */
    public void register(WebSocketSession session, Long bedId) {
        sessions.put(session.getId(), session);

        if (bedId != null) {
            // 若该床位已有旧会话，先清除旧映射
            String oldSessionId = bedSessionMap.put(bedId, session.getId());
            if (oldSessionId != null && !oldSessionId.equals(session.getId())) {
                sessionBedMap.remove(oldSessionId);
                log.info("[SessionManager] 床位 {} 的旧会话 {} 已被新会话 {} 替代",
                        bedId, oldSessionId, session.getId());
            }
            sessionBedMap.put(session.getId(), bedId);
        }
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);

        Long bedId = sessionBedMap.remove(sessionId);
        if (bedId != null) {
            bedSessionMap.remove(bedId, sessionId);
        }
    }

    public WebSocketSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 根据床位 ID 获取当前活跃的 WebSocket 会话。
     */
    public WebSocketSession getByBedId(Long bedId) {
        String sessionId = bedSessionMap.get(bedId);
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 获取指定会话绑定的床位 ID。
     */
    public Long getBedId(String sessionId) {
        return sessionBedMap.get(sessionId);
    }

    public Collection<WebSocketSession> allSessions() {
        return sessions.values();
    }

    /**
     * 获取所有 sessionId → bedId 的快照（只读），用于调试 / 监控。
     */
    public Map<String, Long> allSessionBedMappings() {
        return Map.copyOf(sessionBedMap);
    }

    /**
     * 向指定会话发送文本消息。
     * 对同一 session 的 sendMessage 加锁，避免并发写入导致异常。
     */
    public boolean sendTextMessage(String sessionId, String text) {
        var session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(text));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * 向指定会话发送二进制消息。
     * 对同一 session 的 sendMessage 加锁，避免并发写入导致异常。
     */
    public boolean sendBinaryMessage(String sessionId, byte[] data) {
        var session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        synchronized (session) {
            try {
                session.sendMessage(new BinaryMessage(data));
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }
}

