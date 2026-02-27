package youzi.lin.server.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的 WebSocket 会话管理器。
 * <p>
 * 维护所有活跃客户端会话，提供注册 / 注销 / 按 ID 发送消息等能力。
 * 同一 session 的写操作需加同步锁（WebSocketSession 本身非线程安全）。
 */
@Component
public class WebSocketSessionManager {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public WebSocketSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public Collection<WebSocketSession> allSessions() {
        return sessions.values();
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

