package youzi.lin.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动入口。
 * <p>
 * {@code @EnableScheduling} 用于启用 {@link youzi.lin.server.websocket.WebSocketHeartbeat}
 * 等定时任务。
 * </p>
 */
@SpringBootApplication
@EnableScheduling
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

}
