package youzi.lin.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CliOptions {

    private CliOptions() {
    }

    static Map<String, String> parse(String[] args) {
        java.util.HashMap<String, String> map = new java.util.HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            String value = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            map.put(key, value);
        }
        return map;
    }

    static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }

    static String get(Map<String, String> options, String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    static int getInt(Map<String, String> options, String key, int defaultValue) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    static double getDouble(Map<String, String> options, String key, double defaultValue) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(value);
    }

    static boolean getBoolean(Map<String, String> options, String key, boolean defaultValue) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    static List<Integer> getIntList(Map<String, String> options, String key, String defaultValue) {
        String raw = options.getOrDefault(key, defaultValue);
        List<Integer> numbers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                numbers.add(Integer.parseInt(trimmed));
            }
        }
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("Option --" + key + " has no valid numbers");
        }
        return numbers;
    }
}

