package youzi.lin.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import youzi.lin.server.repository.VisitRepository;
import youzi.lin.server.service.FrameBufferService;
import youzi.lin.server.websocket.BinaryFrameWebSocketHandler;
import youzi.lin.server.websocket.WebSocketSessionManager;

/**
 * WebSocket 配置类。
 * <p>
 * 注册二进制帧处理器到根路径 "/"，并配置容器参数。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketSessionManager sessionManager;
    private final FrameBufferService frameBufferService;
    private final VisitRepository visitRepository;

    public WebSocketConfig(WebSocketSessionManager sessionManager,
                           FrameBufferService frameBufferService,
                           VisitRepository visitRepository) {
        this.sessionManager = sessionManager;
        this.frameBufferService = frameBufferService;
        this.visitRepository = visitRepository;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(binaryFrameWebSocketHandler(), "/ws")
                .setAllowedOrigins("*");
    }

    @Bean
    public BinaryFrameWebSocketHandler binaryFrameWebSocketHandler() {
        return new BinaryFrameWebSocketHandler(sessionManager, frameBufferService, visitRepository);
    }

    /**
     * 配置 WebSocket 容器参数。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        // 最大二进制消息大小：1 MB（单帧 JPEG + 8 字节时间戳绰绰有余）
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxTextMessageBufferSize(64 * 1024);
        // 空闲超时 5 分钟（客户端有重连机制）
        container.setMaxSessionIdleTimeout(5 * 60 * 1000L);
        return container;
    }
}

