package youzi.lin.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import youzi.lin.server.service.WardService;

import java.time.Instant;

/**
 * 护士站 WebSocket 处理器。
 * <p>
 * 负责护士站会话生命周期与文本协议处理：
 * <ul>
 *     <li>{@code subscribe}：订阅病区并返回初始快照</li>
 *     <li>{@code unsubscribe}：取消病区订阅</li>
 *     <li>{@code ping}：返回 {@code pong}</li>
 * </ul>
 * </p>
 *
 * <p>消息示例：</p>
 * <pre>
 * {"type":"subscribe","requestId":"r-1","wardCode":"A01"}
 * </pre>
 */
public class NurseStationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NurseStationWebSocketHandler.class);

    private final WebSocketSessionManager sessionManager;
    private final NurseWardBroadcastService nurseWardBroadcastService;
    private final WardService wardService;
    private final ObjectMapper objectMapper;

    public NurseStationWebSocketHandler(WebSocketSessionManager sessionManager,
                                        NurseWardBroadcastService nurseWardBroadcastService,
                                        WardService wardService,
                                        ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.nurseWardBroadcastService = nurseWardBroadcastService;
        this.wardService = wardService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessionManager.register(session, null, null);
        log.info("[NurseWS] 护士站已连接：{}", session.getId());
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = session.getId();
        sessionManager.markClientActivity(sessionId);

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(sessionId, null, "BAD_JSON", "消息不是合法 JSON");
            return;
        }

        String messageType = text(root, "type");
        String requestId = text(root, "requestId");
        if (messageType == null || messageType.isBlank()) {
            sendError(sessionId, requestId, "BAD_REQUEST", "缺少 type");
            return;
        }

        switch (messageType) {
            case "subscribe" -> handleSubscribe(sessionId, requestId, text(root, "wardCode"));
            case "unsubscribe" -> handleUnsubscribe(sessionId, requestId, text(root, "wardCode"));
            case "ping" -> sendPong(sessionId, root.path("ts").asLong(System.currentTimeMillis()));
            default -> sendError(sessionId, requestId, "UNSUPPORTED_TYPE", "不支持的消息类型: " + messageType);
        }
    }

    @Override
    protected void handlePongMessage(@NonNull WebSocketSession session, @NonNull PongMessage message) {
        sessionManager.markClientActivity(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();
        nurseWardBroadcastService.removeSession(sessionId);
        sessionManager.remove(sessionId);
        log.info("[NurseWS] 护士站断开：{}，状态：{}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = session.getId();
        log.warn("[NurseWS] 会话 {} 传输异常：{}", sessionId, exception.getMessage());
        nurseWardBroadcastService.removeSession(sessionId);
        sessionManager.remove(sessionId);
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // no-op
        }
    }

    private void handleSubscribe(String sessionId, String requestId, String wardCode) {
        if (wardCode == null || wardCode.isBlank()) {
            sendError(sessionId, requestId, "BAD_REQUEST", "wardCode 不能为空");
            return;
        }
        if (!wardService.existsWard(wardCode)) {
            sendError(sessionId, requestId, "WARD_NOT_FOUND", "wardCode 不存在: " + wardCode);
            return;
        }

        nurseWardBroadcastService.subscribe(sessionId, wardCode);
        sendSubscribed(sessionId, requestId, wardCode);
        // 先回订阅成功，再发快照，方便前端把后续 snapshot 当作订阅后的数据流处理。
        nurseWardBroadcastService.sendSnapshot(sessionId, wardCode);
    }

    private void handleUnsubscribe(String sessionId, String requestId, String wardCode) {
        if (wardCode == null || wardCode.isBlank()) {
            sendError(sessionId, requestId, "BAD_REQUEST", "wardCode 不能为空");
            return;
        }
        nurseWardBroadcastService.unsubscribe(sessionId, wardCode);

        var node = objectMapper.createObjectNode();
        node.put("type", "unsubscribed");
        if (requestId != null) {
            node.put("requestId", requestId);
        } else {
            node.putNull("requestId");
        }
        node.put("wardCode", wardCode);
        node.put("serverTime", Instant.now().toString());
        sendJson(sessionId, node);
    }

    private void sendSubscribed(String sessionId, String requestId, String wardCode) {
        var node = objectMapper.createObjectNode();
        node.put("type", "subscribed");
        if (requestId != null) {
            node.put("requestId", requestId);
        } else {
            node.putNull("requestId");
        }
        node.put("wardCode", wardCode);
        node.put("serverTime", Instant.now().toString());
        sendJson(sessionId, node);
    }

    private void sendError(String sessionId, String requestId, String code, String message) {
        var node = objectMapper.createObjectNode();
        node.put("type", "error");
        if (requestId != null) {
            node.put("requestId", requestId);
        } else {
            node.putNull("requestId");
        }
        node.put("code", code);
        node.put("message", message);
        node.put("serverTime", Instant.now().toString());
        sendJson(sessionId, node);
    }

    private void sendPong(String sessionId, long ts) {
        var node = objectMapper.createObjectNode();
        node.put("type", "pong");
        node.put("ts", ts);
        sendJson(sessionId, node);
    }

    private void sendJson(String sessionId, JsonNode payload) {
        try {
            boolean sent = sessionManager.sendTextMessage(sessionId, objectMapper.writeValueAsString(payload));
            if (!sent) {
                nurseWardBroadcastService.removeSession(sessionId);
            }
        } catch (Exception e) {
            log.error("[NurseWS] 会话 {} 发送失败: {}", sessionId, e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
}


