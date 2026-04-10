package youzi.lin.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.ConcurrentHistogram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class LoadTestMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LoadTestMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String scenario = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> options = CliOptions.parse(args);

        switch (scenario) {
            case "bedside" -> runBedside(options);
            case "nurse" -> runNurse(options);
            case "db" -> runDbMixed(options);
            default -> {
                System.out.println("Unknown scenario: " + scenario);
                printUsage();
            }
        }
    }

    private static void runBedside(Map<String, String> options) throws Exception {
        String baseUrl = CliOptions.get(options, "baseUrl", "ws://localhost:8080");
        int beds = CliOptions.getInt(options, "beds", 64);
        int fps = CliOptions.getInt(options, "fps", 15);
        int bedIdStart = CliOptions.getInt(options, "bedIdStart", 1);
        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);

        Metrics metrics = new Metrics();
        byte[] image = new byte[256 * 256];
        Arrays.fill(image, (byte) 0xFF);

        HttpClient client = HttpClient.newHttpClient();
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService senderPool = Executors.newFixedThreadPool(beds);
        List<WebSocket> sockets = new ArrayList<>(beds);
        CountDownLatch openLatch = new CountDownLatch(beds);

        for (int i = 0; i < beds; i++) {
            int bedId = bedIdStart + i;
            String url = baseUrl + "/ws?bedId=" + bedId;
            var listener = new GenericWsListener(metrics, true);
            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), listener)
                    .join();
            sockets.add(ws);
            openLatch.countDown();
        }
        openLatch.await(10, TimeUnit.SECONDS);

        long intervalNs = (long) (1_000_000_000d / Math.max(1, fps));
        for (WebSocket ws : sockets) {
            senderPool.submit(() -> {
                while (running.get()) {
                    long beginNs = System.nanoTime();
                    byte[] payload = buildFramePayload(image);
                    try {
                        ws.sendBinary(ByteBuffer.wrap(payload), true).join();
                        metrics.sent.increment();
                        metrics.sendLatencyMicros.recordValue(Math.max(1, (System.nanoTime() - beginNs) / 1_000));
                    } catch (Exception e) {
                        metrics.errors.increment();
                    }
                    long remain = intervalNs - (System.nanoTime() - beginNs);
                    if (remain > 0) {
                        TimeUnit.NANOSECONDS.sleep(remain);
                    }
                }
            });
        }

        System.out.printf("[bedside] warmup %ds ...%n", warmupSec);
        TimeUnit.SECONDS.sleep(warmupSec);
        metrics.reset();

        System.out.printf("[bedside] measure %ds ...%n", measureSec);
        TimeUnit.SECONDS.sleep(measureSec);

        running.set(false);
        senderPool.shutdownNow();
        senderPool.awaitTermination(10, TimeUnit.SECONDS);
        for (WebSocket socket : sockets) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (Exception ignored) {
                // ignore close failures
            }
        }

        printWsResult("bedside", beds, measureSec, metrics);
    }

    private static void runNurse(Map<String, String> options) throws Exception {
        String baseUrl = CliOptions.get(options, "baseUrl", "ws://localhost:8080");
        String wardCode = CliOptions.get(options, "wardCode", "内科一区");
        int stations = CliOptions.getInt(options, "stations", 300);
        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);

        Metrics metrics = new Metrics();
        HttpClient client = HttpClient.newHttpClient();
        List<WebSocket> sockets = new ArrayList<>(stations);

        for (int i = 0; i < stations; i++) {
            var listener = new GenericWsListener(metrics, false);
            WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(baseUrl + "/ws/nurse"), listener)
                    .join();
            String subscribe = "{\"type\":\"subscribe\",\"requestId\":\"s-" + i + "\",\"wardCode\":\"" + wardCode + "\"}";
            ws.sendText(subscribe, true).join();
            sockets.add(ws);
        }

        System.out.printf("[nurse] warmup %ds ...%n", warmupSec);
        TimeUnit.SECONDS.sleep(warmupSec);
        metrics.reset();

        System.out.printf("[nurse] measure %ds ...%n", measureSec);
        TimeUnit.SECONDS.sleep(measureSec);

        for (WebSocket socket : sockets) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (Exception ignored) {
                // ignore close failures
            }
        }

        printWsResult("nurse", stations, measureSec, metrics);
    }

    private static void runDbMixed(Map<String, String> options) throws Exception {
        String jdbcUrl = CliOptions.require(options, "jdbcUrl");
        String username = CliOptions.require(options, "username");
        String password = CliOptions.require(options, "password");
        String outputCsv = CliOptions.get(options, "outputCsv", "db-latency.csv");

        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);
        double writeRatio = CliOptions.getDouble(options, "writeRatio", 0.8d);
        int bedStart = CliOptions.getInt(options, "bedStart", 1);
        int bedEnd = CliOptions.getInt(options, "bedEnd", 64);
        int patientStart = CliOptions.getInt(options, "patientStart", 1);
        int patientEnd = CliOptions.getInt(options, "patientEnd", 256);
        List<Integer> levels = CliOptions.getIntList(options, "concurrencyLevels", "16,32,64,128");

        List<String> rows = new ArrayList<>();
        rows.add("concurrency,write_ops,read_ops,write_p95_ms,write_p99_ms,read_p95_ms,read_p99_ms,write_err,read_err");

        for (int concurrency : levels) {
            DbMetrics metrics = new DbMetrics();
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicBoolean measuring = new AtomicBoolean(false);
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);

            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> runDbWorker(
                        jdbcUrl,
                        username,
                        password,
                        writeRatio,
                        bedStart,
                        bedEnd,
                        patientStart,
                        patientEnd,
                        running,
                        measuring,
                        metrics
                ));
            }

            System.out.printf("[db] concurrency=%d warmup %ds ...%n", concurrency, warmupSec);
            TimeUnit.SECONDS.sleep(warmupSec);
            metrics.reset();
            measuring.set(true);

            System.out.printf("[db] concurrency=%d measure %ds ...%n", concurrency, measureSec);
            TimeUnit.SECONDS.sleep(measureSec);
            measuring.set(false);
            running.set(false);

            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            String line = String.format(Locale.ROOT,
                    "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d",
                    concurrency,
                    metrics.writeOps.sum(),
                    metrics.readOps.sum(),
                    percentileMs(metrics.writeLatencyMicros, 95.0),
                    percentileMs(metrics.writeLatencyMicros, 99.0),
                    percentileMs(metrics.readLatencyMicros, 95.0),
                    percentileMs(metrics.readLatencyMicros, 99.0),
                    metrics.writeErrors.sum(),
                    metrics.readErrors.sum());
            rows.add(line);
            System.out.println("[db] " + line);
        }

        java.nio.file.Path csvPath = java.nio.file.Path.of(outputCsv);
        java.nio.file.Path parent = csvPath.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.write(csvPath, rows);
        System.out.println("[db] csv written: " + csvPath.toAbsolutePath());
    }

    private static void runDbWorker(String jdbcUrl,
                                    String username,
                                    String password,
                                    double writeRatio,
                                    int bedStart,
                                    int bedEnd,
                                    int patientStart,
                                    int patientEnd,
                                    AtomicBoolean running,
                                    AtomicBoolean measuring,
                                    DbMetrics metrics) {
        String insertSql = "INSERT INTO patient_vitals(\"time\", bed_id, patient_id, hr, sqi, latency, hrv_sdnn, hrv_rmssd) VALUES (NOW(), ?, ?, ?, ?, ?, ?, ?)";
        String querySql = "SELECT time_bucket('1 minute', \"time\") AS bucket, AVG(hr), percentile_cont(0.5) WITHIN GROUP (ORDER BY hrv_sdnn) "
                + "FROM patient_vitals WHERE bed_id = ? AND patient_id = ? AND \"time\" > NOW() - INTERVAL '30 minutes' "
                + "GROUP BY bucket ORDER BY bucket DESC LIMIT 60";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement insert = conn.prepareStatement(insertSql);
             PreparedStatement query = conn.prepareStatement(querySql)) {

            conn.setAutoCommit(true);

            while (running.get()) {
                boolean doWrite = ThreadLocalRandom.current().nextDouble() < writeRatio;
                long startNs = System.nanoTime();

                if (doWrite) {
                    try {
                        insert.setLong(1, randomBetween(bedStart, bedEnd));
                        insert.setLong(2, randomBetween(patientStart, patientEnd));
                        insert.setDouble(3, ThreadLocalRandom.current().nextDouble(55.0, 120.0));
                        insert.setDouble(4, ThreadLocalRandom.current().nextDouble(0.3, 1.0));
                        insert.setDouble(5, ThreadLocalRandom.current().nextDouble(5.0, 30.0));
                        insert.setDouble(6, ThreadLocalRandom.current().nextDouble(15.0, 90.0));
                        insert.setDouble(7, ThreadLocalRandom.current().nextDouble(8.0, 70.0));
                        insert.executeUpdate();
                        if (measuring.get()) {
                            metrics.writeOps.increment();
                            metrics.writeLatencyMicros.recordValue((System.nanoTime() - startNs) / 1_000 + 1);
                        }
                    } catch (Exception e) {
                        if (measuring.get()) {
                            metrics.writeErrors.increment();
                        }
                    }
                } else {
                    try {
                        query.setLong(1, randomBetween(bedStart, bedEnd));
                        query.setLong(2, randomBetween(patientStart, patientEnd));
                        try (ResultSet rs = query.executeQuery()) {
                            while (rs.next()) {
                                rs.getObject(1);
                            }
                        }
                        if (measuring.get()) {
                            metrics.readOps.increment();
                            metrics.readLatencyMicros.recordValue((System.nanoTime() - startNs) / 1_000 + 1);
                        }
                    } catch (Exception e) {
                        if (measuring.get()) {
                            metrics.readErrors.increment();
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (measuring.get()) {
                metrics.readErrors.increment();
                metrics.writeErrors.increment();
            }
        }
    }

    private static byte[] buildFramePayload(byte[] image) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + image.length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(System.currentTimeMillis());
        buffer.put(image);
        return buffer.array();
    }

    private static void printWsResult(String label, int concurrency, int measureSec, Metrics metrics) {
        double seconds = Math.max(1, measureSec);
        double sendRate = metrics.sent.sum() / seconds;
        double recvRate = metrics.received.sum() / seconds;

        System.out.printf(Locale.ROOT,
                "[%s] concurrency=%d sent=%d recv=%d errors=%d send_rate=%.2f/s recv_rate=%.2f/s send_p95=%.3fms recv_delay_p95=%.3fms%n",
                label,
                concurrency,
                metrics.sent.sum(),
                metrics.received.sum(),
                metrics.errors.sum(),
                sendRate,
                recvRate,
                percentileMs(metrics.sendLatencyMicros, 95.0),
                percentileMs(metrics.eventDelayMicros, 95.0));
    }

    private static double percentileMs(ConcurrentHistogram histogram, double p) {
        if (histogram.getTotalCount() == 0) {
            return 0.0;
        }
        return histogram.getValueAtPercentile(p) / 1_000.0;
    }

    private static long randomBetween(int start, int end) {
        int min = Math.min(start, end);
        int max = Math.max(start, end);
        return ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  bedside --baseUrl ws://localhost:8080 --beds 64 --fps 15 --warmupSec 120 --measureSec 180");
        System.out.println("  nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 500 --warmupSec 120 --measureSec 180");
        System.out.println("  db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 \\");
        System.out.println("     --concurrencyLevels 16,32,64,128 --writeRatio 0.8 --warmupSec 120 --measureSec 180 --outputCsv .\\results\\db-latency.csv");
    }

    private static final class GenericWsListener implements WebSocket.Listener {
        private final Metrics metrics;
        private final boolean parseClientMetric;
        private final StringBuilder textBuffer = new StringBuilder();

        private GenericWsListener(Metrics metrics, boolean parseClientMetric) {
            this.metrics = metrics;
            this.parseClientMetric = parseClientMetric;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                metrics.received.increment();
                if (!parseClientMetric) {
                    extractEventTime(textBuffer.toString(), metrics);
                }
                textBuffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            metrics.errors.increment();
        }
    }

    private static void extractEventTime(String payload, Metrics metrics) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            if ("vitals.batch_update".equals(root.path("type").asText())) {
                JsonNode updates = root.path("updates");
                if (updates.isArray() && !updates.isEmpty()) {
                    String ts = updates.get(0).path("eventTime").asText(null);
                    if (ts != null) {
                        long delayMicros = Duration.between(Instant.parse(ts), Instant.now()).toNanos() / 1_000;
                        if (delayMicros > 0) {
                            metrics.eventDelayMicros.recordValue(delayMicros);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore invalid payload
        }
    }

    private static final class Metrics {
        private final LongAdder sent = new LongAdder();
        private final LongAdder received = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final ConcurrentHistogram sendLatencyMicros = new ConcurrentHistogram(3_600_000_000L, 3);
        private final ConcurrentHistogram eventDelayMicros = new ConcurrentHistogram(3_600_000_000L, 3);

        private void reset() {
            sent.reset();
            received.reset();
            errors.reset();
            sendLatencyMicros.reset();
            eventDelayMicros.reset();
        }
    }

    private static final class DbMetrics {
        private final LongAdder writeOps = new LongAdder();
        private final LongAdder readOps = new LongAdder();
        private final LongAdder writeErrors = new LongAdder();
        private final LongAdder readErrors = new LongAdder();
        private final ConcurrentHistogram writeLatencyMicros = new ConcurrentHistogram(3_600_000_000L, 3);
        private final ConcurrentHistogram readLatencyMicros = new ConcurrentHistogram(3_600_000_000L, 3);

        private void reset() {
            writeOps.reset();
            readOps.reset();
            writeErrors.reset();
            readErrors.reset();
            writeLatencyMicros.reset();
            readLatencyMicros.reset();
        }
    }

    private static final class CliOptions {
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
}


