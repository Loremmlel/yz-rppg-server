package youzi.lin.server.util;

/**
 * 时间窗口解析工具：将前端简写转换为 PostgreSQL INTERVAL 字符串。
 * <p>
 * 支持的简写格式示例：{@code 1m}、{@code 5m}、{@code 15m}、{@code 1h}、{@code 1d}。
 * 如果输入已包含空格（如 {@code "1 minute"}），则视为已是合法 PostgreSQL INTERVAL，直接透传。
 * </p>
 */
public final class IntervalUtils {

    // 工具类不允许实例化
    private IntervalUtils() {
    }

    /**
     * 将简写 interval 转换为 PostgreSQL INTERVAL 字符串；无法识别时返回 {@code null}。
     *
     * @param interval 简写（如 {@code "1m"}）或完整 PostgreSQL 格式（如 {@code "1 minute"}）
     * @return PostgreSQL INTERVAL 字符串；输入为空或无法识别时返回 {@code null}
     */
    public static String parseOrNull(String interval) {
        if (interval == null || interval.isBlank()) {
            return null;
        }
        // 已是完整 PostgreSQL INTERVAL 格式（含空格），直接透传
        if (interval.contains(" ")) {
            return interval;
        }
        return switch (interval.toLowerCase()) {
            case "1m" -> "1 minute";
            case "5m" -> "5 minutes";
            case "10m" -> "10 minutes";
            case "15m" -> "15 minutes";
            case "30m" -> "30 minutes";
            case "1h" -> "1 hour";
            case "6h" -> "6 hours";
            case "12h" -> "12 hours";
            case "1d" -> "1 day";
            default -> null;
        };
    }

    /**
     * 将简写 interval 转换为 PostgreSQL INTERVAL 字符串；无法识别时返回指定默认值。
     *
     * @param interval        简写或完整格式
     * @param defaultInterval 解析失败时的兜底默认值（如 {@code "1 minute"}）
     * @return 解析后的 PostgreSQL INTERVAL 字符串或默认值
     */
    public static String parseOrDefault(String interval, String defaultInterval) {
        String parsed = parseOrNull(interval);
        return parsed != null ? parsed : defaultInterval;
    }
}
