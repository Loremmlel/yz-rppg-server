package youzi.lin.server.enums;

/**
 * 报警类型。
 */
public enum AlarmType {
    /** 心动过速。 */
    TACHYCARDIA,
    /** 心动过缓。 */
    BRADYCARDIA,
    /** 信号质量差。 */
    LOW_SQI,
    /** 客户端超时或设备离线。 */
    DEVICE_OFFLINE
}

