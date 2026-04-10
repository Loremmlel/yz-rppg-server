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
import java.sql.Timestamp;
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
            case "bedside" -> {
                WsResult result = runBedside(options);
                printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                writeSingleWsResult("bedside", result, options);
            }
            case "nurse" -> {
                WsResult result = runNurse(options);
                printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                writeSingleWsResult("nurse", result, options);
            }
            case "bedside-matrix" -> runBedsideMatrix(options);
            case "nurse-matrix" -> runNurseMatrix(options);
            case "db" -> runDbMixed(options);
            default -> {
                System.out.println("Unknown scenario: " + scenario);
                printUsage();
            }
        }
    }

    private static WsResult runBedside(Map<String, String> options) throws Exception {
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
                        try {
                            TimeUnit.NANOSECONDS.sleep(remain);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
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

        return new WsResult("bedside", beds, measureSec, metrics.snapshot());
    }

    private static WsResult runNurse(Map<String, String> options) throws Exception {
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

        return new WsResult("nurse", stations, measureSec, metrics.snapshot());
    }

    private static void runBedsideMatrix(Map<String, String> options) throws Exception {
        List<Integer> levels = CliOptions.getIntList(options, "bedsLevels", "16,32,64,128,256");
        String outputCsv = CliOptions.get(options, "outputCsv", ".\\results\\bedside-matrix.csv");
        String outputMd = CliOptions.get(options, "outputMd", ".\\results\\bedside-matrix.md");

        List<String> rows = new ArrayList<>();
        rows.add("beds,sent,recv,errors,send_rate,recv_rate,send_p95_ms,send_p99_ms,recv_p95_ms,recv_p99_ms");
        List<WsResult> results = new ArrayList<>();

        for (Integer beds : levels) {
            var scenarioOptions = new java.util.HashMap<>(options);
            scenarioOptions.put("beds", String.valueOf(beds));
            WsResult result = runBedside(scenarioOptions);
            results.add(result);
            printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
            rows.add(toWsCsvRow(result));
        }

        writeRows(outputCsv, rows);
        writeWsMarkdown(outputMd, "Bedside Matrix", "beds", results);
    }

    private static void runNurseMatrix(Map<String, String> options) throws Exception {
        List<Integer> levels = CliOptions.getIntList(options, "stationsLevels", "50,100,200,500,1000");
        String outputCsv = CliOptions.get(options, "outputCsv", ".\\results\\nurse-matrix.csv");
        String outputMd = CliOptions.get(options, "outputMd", ".\\results\\nurse-matrix.md");

        List<String> rows = new ArrayList<>();
        rows.add("stations,sent,recv,errors,recv_rate,recv_p95_ms,recv_p99_ms");
        List<WsResult> results = new ArrayList<>();

        for (Integer stations : levels) {
            var scenarioOptions = new java.util.HashMap<>(options);
            scenarioOptions.put("stations", String.valueOf(stations));
            WsResult result = runNurse(scenarioOptions);
            results.add(result);
            printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
            rows.add(toNurseCsvRow(result));
        }

        writeRows(outputCsv, rows);
        writeWsMarkdown(outputMd, "Nurse Matrix", "stations", results);
    }

    private static void runDbMixed(Map<String, String> options) throws Exception {
        String jdbcUrl = CliOptions.require(options, "jdbcUrl");
        String username = CliOptions.require(options, "username");
        String password = CliOptions.require(options, "password");
        String outputCsv = CliOptions.get(options, "outputCsv", "db-latency.csv");
        String outputMd = CliOptions.get(options, "outputMd", "db-latency.md");
        boolean cleanup = CliOptions.getBoolean(options, "cleanup", true);
        String runTag = CliOptions.get(options, "runTag", "db-" + System.currentTimeMillis());

        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);
        double writeRatio = CliOptions.getDouble(options, "writeRatio", 0.8d);
        int bedStart = CliOptions.getInt(options, "bedStart", 1);
        int bedEnd = CliOptions.getInt(options, "bedEnd", 64);
        int patientStart = CliOptions.getInt(options, "patientStart", 1);
        int patientEnd = CliOptions.getInt(options, "patientEnd", 256);
        List<Integer> levels = CliOptions.getIntList(options, "concurrencyLevels", "16,32,64,128");
        double markerLatency = -9000d - Math.abs(runTag.hashCode() % 500);

        List<String> rows = new ArrayList<>();
        rows.add("concurrency,write_ops,read_ops,write_p95_ms,write_p99_ms,read_p95_ms,read_p99_ms,write_err,read_err");

        List<DbResult> results = new ArrayList<>();
        Instant runStart = Instant.now();
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
                        markerLatency,
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
            results.add(new DbResult(
                    concurrency,
                    metrics.writeOps.sum(),
                    metrics.readOps.sum(),
                    percentileMs(metrics.writeLatencyMicros, 95.0),
                    percentileMs(metrics.writeLatencyMicros, 99.0),
                    percentileMs(metrics.readLatencyMicros, 95.0),
                    percentileMs(metrics.readLatencyMicros, 99.0),
                    metrics.writeErrors.sum(),
                    metrics.readErrors.sum()));
            System.out.println("[db] " + line);
        }
        Instant runEnd = Instant.now();

        writeRows(outputCsv, rows);
        writeDbMarkdown(outputMd, results);

        if (cleanup) {
            int deleted = cleanupDbData(
                    jdbcUrl,
                    username,
                    password,
                    bedStart,
                    bedEnd,
                    patientStart,
                    patientEnd,
                    markerLatency,
                    runStart,
                    runEnd
            );
            System.out.printf(Locale.ROOT,
                    "[db] cleanup enabled, runTag=%s, markerLatency=%.0f, deletedRows=%d%n",
                    runTag, markerLatency, deleted);
        }
    }

    private static void runDbWorker(String jdbcUrl,
                                    String username,
                                    String password,
                                    double writeRatio,
                                    int bedStart,
                                    int bedEnd,
                                    int patientStart,
                                    int patientEnd,
                                    double markerLatency,
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
                        insert.setDouble(5, markerLatency);
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

    private static void printWsResult(String label, int concurrency, int measureSec, WsSnapshot metrics) {
        double seconds = Math.max(1, measureSec);
        double sendRate = metrics.sent() / seconds;
        double recvRate = metrics.received() / seconds;

        System.out.printf(Locale.ROOT,
                "[%s] concurrency=%d sent=%d recv=%d errors=%d send_rate=%.2f/s recv_rate=%.2f/s send_p95=%.3fms recv_delay_p95=%.3fms%n",
                label,
                concurrency,
                metrics.sent(),
                metrics.received(),
                metrics.errors(),
                sendRate,
                recvRate,
                metrics.sendP95Ms(),
                metrics.recvDelayP95Ms());
    }

    private static void writeRows(String outputCsv, List<String> rows) throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(outputCsv);
        java.nio.file.Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.write(path, rows);
        System.out.println("[report] csv written: " + path.toAbsolutePath());
    }

    private static void writeSingleWsResult(String scenario,
                                            WsResult result,
                                            Map<String, String> options) throws Exception {
        String outputCsv = CliOptions.get(options, "outputCsv", ".\\results\\" + scenario + "-result.csv");
        String outputMd = CliOptions.get(options, "outputMd", ".\\results\\" + scenario + "-result.md");

        List<String> rows = new ArrayList<>();
        rows.add("concurrency,sent,recv,errors,send_rate,recv_rate,send_p95_ms,send_p99_ms,recv_p95_ms,recv_p99_ms");
        rows.add(toWsCsvRow(result));
        writeRows(outputCsv, rows);
        writeWsMarkdown(outputMd, scenario + " Result", "concurrency", List.of(result));
    }

    private static int cleanupDbData(String jdbcUrl,
                                     String username,
                                     String password,
                                     int bedStart,
                                     int bedEnd,
                                     int patientStart,
                                     int patientEnd,
                                     double markerLatency,
                                     Instant runStart,
                                     Instant runEnd) {
        String deleteSql = "DELETE FROM patient_vitals "
                + "WHERE bed_id BETWEEN ? AND ? "
                + "AND patient_id BETWEEN ? AND ? "
                + "AND latency = ? "
                + "AND \"time\" >= ? AND \"time\" <= ?";

        int minBed = Math.min(bedStart, bedEnd);
        int maxBed = Math.max(bedStart, bedEnd);
        int minPatient = Math.min(patientStart, patientEnd);
        int maxPatient = Math.max(patientStart, patientEnd);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement delete = conn.prepareStatement(deleteSql)) {
            delete.setInt(1, minBed);
            delete.setInt(2, maxBed);
            delete.setInt(3, minPatient);
            delete.setInt(4, maxPatient);
            delete.setDouble(5, markerLatency);
            delete.setTimestamp(6, Timestamp.from(runStart.minusSeconds(5)));
            delete.setTimestamp(7, Timestamp.from(runEnd.plusSeconds(5)));
            return delete.executeUpdate();
        } catch (Exception e) {
            System.out.println("[db] cleanup failed: " + e.getMessage());
            return -1;
        }
    }

    private static void writeWsMarkdown(String outputMd,
                                        String title,
                                        String concurrencyColumn,
                                        List<WsResult> results) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# " + title);
        lines.add("");
        lines.add("| " + concurrencyColumn + " | sent | recv | errors | send p95(ms) | recv delay p95(ms) |");
        lines.add("|---:|---:|---:|---:|---:|---:|");

        for (WsResult result : results) {
            WsSnapshot m = result.metrics();
            lines.add(String.format(Locale.ROOT,
                    "| %d | %d | %d | %d | %.3f | %.3f |",
                    result.concurrency(), m.sent(), m.received(), m.errors(), m.sendP95Ms(), m.recvDelayP95Ms()));
        }

        java.nio.file.Path path = java.nio.file.Path.of(outputMd);
        java.nio.file.Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.write(path, lines);
        System.out.println("[report] markdown written: " + path.toAbsolutePath());
    }

    private static String toWsCsvRow(WsResult result) {
        WsSnapshot m = result.metrics();
        double seconds = Math.max(1, result.measureSec());
        double sendRate = m.sent() / seconds;
        double recvRate = m.received() / seconds;
        return String.format(Locale.ROOT,
                "%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
                result.concurrency(),
                m.sent(),
                m.received(),
                m.errors(),
                sendRate,
                recvRate,
                m.sendP95Ms(),
                m.sendP99Ms(),
                m.recvDelayP95Ms(),
                m.recvDelayP99Ms());
    }

    private static String toNurseCsvRow(WsResult result) {
        WsSnapshot m = result.metrics();
        double seconds = Math.max(1, result.measureSec());
        double recvRate = m.received() / seconds;
        return String.format(Locale.ROOT,
                "%d,%d,%d,%d,%.3f,%.3f,%.3f",
                result.concurrency(),
                m.sent(),
                m.received(),
                m.errors(),
                recvRate,
                m.recvDelayP95Ms(),
                m.recvDelayP99Ms());
    }

    private static void writeDbMarkdown(String outputMd, List<DbResult> results) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# DB Mixed Workload");
        lines.add("");
        lines.add("| concurrency | write ops | read ops | write p95(ms) | read p95(ms) | write err | read err |");
        lines.add("|---:|---:|---:|---:|---:|---:|---:|");
        for (DbResult r : results) {
            lines.add(String.format(Locale.ROOT,
                    "| %d | %d | %d | %.3f | %.3f | %d | %d |",
                    r.concurrency(),
                    r.writeOps(),
                    r.readOps(),
                    r.writeP95Ms(),
                    r.readP95Ms(),
                    r.writeErr(),
                    r.readErr()));
        }

        java.nio.file.Path path = java.nio.file.Path.of(outputMd);
        java.nio.file.Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.write(path, lines);
        System.out.println("[report] markdown written: " + path.toAbsolutePath());
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
        System.out.println("     (writes .\\results\\bedside-result.csv and .\\results\\bedside-result.md by default)");
        System.out.println("  bedside-matrix --baseUrl ws://localhost:8080 --bedsLevels 16,32,64,128,256 --fps 15 --outputCsv .\\results\\bedside-matrix.csv --outputMd .\\results\\bedside-matrix.md");
        System.out.println("  nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 500 --warmupSec 120 --measureSec 180");
        System.out.println("     (writes .\\results\\nurse-result.csv and .\\results\\nurse-result.md by default)");
        System.out.println("  nurse-matrix --baseUrl ws://localhost:8080 --wardCode 内科一区 --stationsLevels 50,100,200,500,1000 --outputCsv .\\results\\nurse-matrix.csv --outputMd .\\results\\nurse-matrix.md");
        System.out.println("  db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 \\");
        System.out.println("     --concurrencyLevels 16,32,64,128 --writeRatio 0.8 --warmupSec 120 --measureSec 180 --cleanup true --outputCsv .\\results\\db-latency.csv --outputMd .\\results\\db-latency.md");
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

        private WsSnapshot snapshot() {
            return new WsSnapshot(
                    sent.sum(),
                    received.sum(),
                    errors.sum(),
                    percentileMs(sendLatencyMicros, 95.0),
                    percentileMs(sendLatencyMicros, 99.0),
                    percentileMs(eventDelayMicros, 95.0),
                    percentileMs(eventDelayMicros, 99.0)
            );
        }
    }

    private record WsResult(String label, int concurrency, int measureSec, WsSnapshot metrics) {
    }

    private record WsSnapshot(long sent,
                              long received,
                              long errors,
                              double sendP95Ms,
                              double sendP99Ms,
                              double recvDelayP95Ms,
                              double recvDelayP99Ms) {
    }

    private record DbResult(int concurrency,
                            long writeOps,
                            long readOps,
                            double writeP95Ms,
                            double writeP99Ms,
                            double readP95Ms,
                            double readP99Ms,
                            long writeErr,
                            long readErr) {
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
}


