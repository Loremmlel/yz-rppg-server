package youzi.lin.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * loadtest profile 相关配置。
 * <p>
 * 绑定前缀 {@code app.loadtest}，用于控制：
 * <ul>
 *     <li>gRPC mock 延迟范围</li>
 *     <li>护士站模拟数据泵开关与节奏</li>
 * </ul>
 * </p>
 */
@Component
@ConfigurationProperties(prefix = "app.loadtest")
public class LoadTestProperties {

    private final GrpcMock grpcMock = new GrpcMock();
    private final NursePump nursePump = new NursePump();

    public GrpcMock getGrpcMock() {
        return grpcMock;
    }

    public NursePump getNursePump() {
        return nursePump;
    }

    /**
     * gRPC mock 配置。
     */
    public static class GrpcMock {
        private boolean enabled;
        private int minLatencyMs = 2;
        private int maxLatencyMs = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinLatencyMs() {
            return minLatencyMs;
        }

        public void setMinLatencyMs(int minLatencyMs) {
            this.minLatencyMs = minLatencyMs;
        }

        public int getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(int maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }
    }

    /**
     * 护士站模拟数据泵配置。
     */
    public static class NursePump {
        private boolean enabled;
        private String wardCode = "WARD-A";
        private int patientsPerTick = 8;
        private long intervalMs = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWardCode() {
            return wardCode;
        }

        public void setWardCode(String wardCode) {
            this.wardCode = wardCode;
        }

        public int getPatientsPerTick() {
            return patientsPerTick;
        }

        public void setPatientsPerTick(int patientsPerTick) {
            this.patientsPerTick = patientsPerTick;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }
}


