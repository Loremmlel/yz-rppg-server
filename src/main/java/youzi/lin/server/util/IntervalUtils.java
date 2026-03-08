package youzi.lin.server.util;

/**
 * 时间窗口解析工具：将前端简写转换为 PostgreSQL INTERVAL 字符串。
 */
public final class IntervalUtils {

    private IntervalUtils() {
    }

    public static String parseOrNull(String interval) {
        if (interval == null || interval.isBlank()) {
            return null;
        }
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

    public static String parseOrDefault(String interval, String defaultInterval) {
        String parsed = parseOrNull(interval);
        return parsed != null ? parsed : defaultInterval;
    }
}

