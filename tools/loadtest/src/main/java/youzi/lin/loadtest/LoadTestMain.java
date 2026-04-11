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
            case "smart-suite" -> runSmartSuite(options);
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
        List<Integer> levels = resolveLevels(options, "bedsLevels", levelsForProfile("bedside", CliOptions.get(options, "profile", "balanced")));
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
        writeBedsideCharts(outputCsv, results);
    }

    private static void runNurseMatrix(Map<String, String> options) throws Exception {
        List<Integer> levels = resolveLevels(options, "stationsLevels", levelsForProfile("nurse", CliOptions.get(options, "profile", "balanced")));
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
        writeNurseCharts(outputCsv, results);
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
        List<Integer> levels = resolveLevels(options, "concurrencyLevels", levelsForProfile("db", CliOptions.get(options, "profile", "balanced")));
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
                    measureSec,
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
        writeDbCharts(outputCsv, results);

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

    private static void runSmartSuite(Map<String, String> options) throws Exception {
        String outDir = CliOptions.get(options, "outDir", ".\\results");
        String profile = CliOptions.get(options, "profile", "balanced");

        System.out.printf("[smart-suite] profile=%s outDir=%s%n", profile, java.nio.file.Path.of(outDir).toAbsolutePath());

        var bedsideOptions = new java.util.HashMap<>(options);
        bedsideOptions.put("profile", profile);
        bedsideOptions.put("outputCsv", outDir + "\\bedside-ladder.csv");
        bedsideOptions.put("outputMd", outDir + "\\bedside-ladder.md");
        runBedsideMatrix(bedsideOptions);

        var nurseOptions = new java.util.HashMap<>(options);
        nurseOptions.put("profile", profile);
        nurseOptions.put("outputCsv", outDir + "\\nurse-ladder.csv");
        nurseOptions.put("outputMd", outDir + "\\nurse-ladder.md");
        runNurseMatrix(nurseOptions);

        var dbOptions = new java.util.HashMap<>(options);
        dbOptions.put("profile", profile);
        dbOptions.put("outputCsv", outDir + "\\db-ladder.csv");
        dbOptions.put("outputMd", outDir + "\\db-ladder.md");
        runDbMixed(dbOptions);
    }

    private static List<Integer> resolveLevels(Map<String, String> options, String key, List<Integer> fallback) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return CliOptions.getIntList(options, key, joinLevels(fallback));
    }

    private static String joinLevels(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> levelsForProfile(String target, String profile) {
        String normalized = profile.toLowerCase(Locale.ROOT);
        return switch (target) {
            case "bedside" -> switch (normalized) {
                case "quick" -> List.of(8, 16, 32);
                case "high" -> List.of(32, 64, 128, 256, 384);
                default -> List.of(16, 32, 64, 128, 256);
            };
            case "nurse" -> switch (normalized) {
                case "quick" -> List.of(20, 50, 100);
                case "high" -> List.of(100, 200, 500, 1000, 1500);
                default -> List.of(50, 100, 200, 500, 1000);
            };
            case "db" -> switch (normalized) {
                case "quick" -> List.of(4, 8, 16);
                case "high" -> List.of(32, 64, 128, 192, 256);
                default -> List.of(16, 32, 64, 128);
            };
            default -> throw new IllegalArgumentException("Unsupported profile target: " + target);
        };
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

    private static void writeBedsideCharts(String outputCsv, List<WsResult> results) throws Exception {
        List<Double> x = new ArrayList<>();
        List<Double> sendRate = new ArrayList<>();
        List<Double> recvRate = new ArrayList<>();
        List<Double> sendP95 = new ArrayList<>();
        List<Double> recvP95 = new ArrayList<>();

        for (WsResult result : results) {
            WsSnapshot m = result.metrics();
            double seconds = Math.max(1, result.measureSec());
            x.add((double) result.concurrency());
            sendRate.add(m.sent() / seconds);
            recvRate.add(m.received() / seconds);
            sendP95.add(m.sendP95Ms());
            recvP95.add(m.recvDelayP95Ms());
        }

        String base = baseName(outputCsv);
        writeLineChartSvg(base + "-throughput.svg",
                "Bedside Throughput vs Beds",
                "Beds",
                "Messages/sec",
                x,
                Map.of("send_rate", sendRate, "recv_rate", recvRate));
        writeLineChartSvg(base + "-latency.svg",
                "Bedside Latency vs Beds",
                "Beds",
                "Latency (ms)",
                x,
                Map.of("send_p95", sendP95, "recv_delay_p95", recvP95));
    }

    private static void writeNurseCharts(String outputCsv, List<WsResult> results) throws Exception {
        List<Double> x = new ArrayList<>();
        List<Double> recvRate = new ArrayList<>();
        List<Double> recvP95 = new ArrayList<>();
        List<Double> recvP99 = new ArrayList<>();

        for (WsResult result : results) {
            WsSnapshot m = result.metrics();
            double seconds = Math.max(1, result.measureSec());
            x.add((double) result.concurrency());
            recvRate.add(m.received() / seconds);
            recvP95.add(m.recvDelayP95Ms());
            recvP99.add(m.recvDelayP99Ms());
        }

        String base = baseName(outputCsv);
        writeLineChartSvg(base + "-throughput.svg",
                "Nurse Throughput vs Stations",
                "Stations",
                "Messages/sec",
                x,
                Map.of("recv_rate", recvRate));
        writeLineChartSvg(base + "-latency.svg",
                "Nurse Latency vs Stations",
                "Stations",
                "Latency (ms)",
                x,
                Map.of("recv_delay_p95", recvP95, "recv_delay_p99", recvP99));
    }

    private static void writeDbCharts(String outputCsv, List<DbResult> results) throws Exception {
        List<Double> x = new ArrayList<>();
        List<Double> writeTps = new ArrayList<>();
        List<Double> readTps = new ArrayList<>();
        List<Double> writeP95 = new ArrayList<>();
        List<Double> readP95 = new ArrayList<>();
        List<Double> writeP99 = new ArrayList<>();
        List<Double> readP99 = new ArrayList<>();

        for (DbResult result : results) {
            x.add((double) result.concurrency());
            double seconds = Math.max(1, result.measureSec());
            writeTps.add(result.writeOps() / seconds);
            readTps.add(result.readOps() / seconds);
            writeP95.add(result.writeP95Ms());
            readP95.add(result.readP95Ms());
            writeP99.add(result.writeP99Ms());
            readP99.add(result.readP99Ms());
        }

        String base = baseName(outputCsv);
        writeLineChartSvg(base + "-throughput.svg",
                "DB Throughput vs Concurrency",
                "Concurrency",
                "Ops/sec",
                x,
                Map.of("write_ops", writeTps, "read_ops", readTps));
        writeLineChartSvg(base + "-latency.svg",
                "DB Latency vs Concurrency",
                "Concurrency",
                "Latency (ms)",
                x,
                Map.of("write_p95", writeP95, "read_p95", readP95, "write_p99", writeP99, "read_p99", readP99));
    }

    private static void writeLineChartSvg(String outputPath,
                                          String title,
                                          String xLabel,
                                          String yLabel,
                                          List<Double> xValues,
                                          Map<String, List<Double>> seriesMap) throws Exception {
        if (xValues.isEmpty() || seriesMap.isEmpty()) {
            return;
        }

        int width = 980;
        int height = 540;
        int left = 70;
        int right = 20;
        int top = 50;
        int bottom = 60;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        double minX = xValues.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxX = xValues.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (maxX <= minX) {
            maxX = minX + 1;
        }

        double minY = 0;
        double maxY = seriesMap.values().stream()
                .flatMap(List::stream)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1.0);
        if (maxY <= minY) {
            maxY = 1;
        }
        maxY *= 1.1;

        String[] palette = new String[]{"#2563eb", "#dc2626", "#16a34a", "#9333ea", "#ea580c"};
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\">\n");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        svg.append("<text x=\"").append(width / 2).append("\" y=\"28\" text-anchor=\"middle\" font-size=\"20\" font-family=\"Arial\">")
                .append(title).append("</text>\n");

        svg.append("<line x1=\"").append(left).append("\" y1=\"").append(top + plotHeight)
                .append("\" x2=\"").append(left + plotWidth).append("\" y2=\"").append(top + plotHeight)
                .append("\" stroke=\"#111827\"/>\n");
        svg.append("<line x1=\"").append(left).append("\" y1=\"").append(top)
                .append("\" x2=\"").append(left).append("\" y2=\"").append(top + plotHeight)
                .append("\" stroke=\"#111827\"/>\n");

        int ticks = 5;
        for (int i = 0; i <= ticks; i++) {
            double ratio = i / (double) ticks;
            int y = (int) Math.round(top + plotHeight - ratio * plotHeight);
            double value = minY + ratio * (maxY - minY);
            svg.append("<line x1=\"").append(left).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(left + plotWidth)
                    .append("\" y2=\"").append(y)
                    .append("\" stroke=\"#e5e7eb\"/>\n");
            svg.append("<text x=\"").append(left - 8).append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\" font-size=\"12\" font-family=\"Arial\">")
                    .append(String.format(Locale.ROOT, "%.1f", value)).append("</text>\n");
        }

        for (int i = 0; i < xValues.size(); i++) {
            double xRaw = xValues.get(i);
            int x = mapX(xRaw, minX, maxX, left, plotWidth);
            svg.append("<line x1=\"").append(x).append("\" y1=\"").append(top)
                    .append("\" x2=\"").append(x).append("\" y2=\"").append(top + plotHeight)
                    .append("\" stroke=\"#f3f4f6\"/>\n");
            svg.append("<text x=\"").append(x).append("\" y=\"").append(top + plotHeight + 20)
                    .append("\" text-anchor=\"middle\" font-size=\"12\" font-family=\"Arial\">")
                    .append(trimDouble(xRaw)).append("</text>\n");
        }

        int colorIndex = 0;
        int legendY = top + 12;
        for (Map.Entry<String, List<Double>> entry : seriesMap.entrySet()) {
            String color = palette[colorIndex % palette.length];
            colorIndex++;

            List<Double> series = entry.getValue();
            if (series.size() != xValues.size()) {
                continue;
            }

            StringBuilder points = new StringBuilder();
            for (int i = 0; i < xValues.size(); i++) {
                int x = mapX(xValues.get(i), minX, maxX, left, plotWidth);
                int y = mapY(series.get(i), minY, maxY, top, plotHeight);
                points.append(x).append(',').append(y).append(' ');
            }
            svg.append("<polyline fill=\"none\" stroke=\"").append(color)
                    .append("\" stroke-width=\"2\" points=\"").append(points.toString().trim()).append("\"/>\n");

            for (int i = 0; i < xValues.size(); i++) {
                int x = mapX(xValues.get(i), minX, maxX, left, plotWidth);
                int y = mapY(series.get(i), minY, maxY, top, plotHeight);
                svg.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                        .append("\" r=\"3\" fill=\"").append(color).append("\"/>\n");
            }

            int legendX = width - right - 190;
            svg.append("<line x1=\"").append(legendX).append("\" y1=\"").append(legendY)
                    .append("\" x2=\"").append(legendX + 24).append("\" y2=\"").append(legendY)
                    .append("\" stroke=\"").append(color).append("\" stroke-width=\"3\"/>\n");
            svg.append("<text x=\"").append(legendX + 30).append("\" y=\"").append(legendY + 4)
                    .append("\" font-size=\"12\" font-family=\"Arial\">")
                    .append(entry.getKey()).append("</text>\n");
            legendY += 20;
        }

        svg.append("<text x=\"").append(left + plotWidth / 2).append("\" y=\"").append(height - 15)
                .append("\" text-anchor=\"middle\" font-size=\"13\" font-family=\"Arial\">")
                .append(xLabel).append("</text>\n");
        svg.append("<text x=\"18\" y=\"").append(top + plotHeight / 2)
                .append("\" transform=\"rotate(-90 18 ").append(top + plotHeight / 2)
                .append(")\" text-anchor=\"middle\" font-size=\"13\" font-family=\"Arial\">")
                .append(yLabel).append("</text>\n");
        svg.append("</svg>\n");

        java.nio.file.Path path = java.nio.file.Path.of(outputPath);
        java.nio.file.Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.writeString(path, svg.toString());
        System.out.println("[report] chart written: " + path.toAbsolutePath());
    }

    private static int mapX(double value, double minX, double maxX, int left, int plotWidth) {
        double ratio = (value - minX) / (maxX - minX);
        return (int) Math.round(left + ratio * plotWidth);
    }

    private static int mapY(double value, double minY, double maxY, int top, int plotHeight) {
        double ratio = (value - minY) / (maxY - minY);
        return (int) Math.round(top + plotHeight - ratio * plotHeight);
    }

    private static String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-6) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String baseName(String outputCsv) {
        if (outputCsv.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return outputCsv.substring(0, outputCsv.length() - 4);
        }
        return outputCsv;
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
        System.out.println("  bedside-matrix --baseUrl ws://localhost:8080 --bedsLevels 16,32,64,128,256 --profile balanced --fps 15 --outputCsv .\\results\\bedside-matrix.csv --outputMd .\\results\\bedside-matrix.md");
        System.out.println("  nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 500 --warmupSec 120 --measureSec 180");
        System.out.println("     (writes .\\results\\nurse-result.csv and .\\results\\nurse-result.md by default)");
        System.out.println("  nurse-matrix --baseUrl ws://localhost:8080 --wardCode 内科一区 --stationsLevels 50,100,200,500,1000 --profile balanced --outputCsv .\\results\\nurse-matrix.csv --outputMd .\\results\\nurse-matrix.md");
        System.out.println("  db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 \\");
        System.out.println("     --concurrencyLevels 16,32,64,128 --profile balanced --writeRatio 0.8 --warmupSec 120 --measureSec 180 --cleanup true --outputCsv .\\results\\db-latency.csv --outputMd .\\results\\db-latency.md");
        System.out.println("  smart-suite --baseUrl ws://localhost:8080 --wardCode 内科一区 --profile balanced --outDir .\\results --warmupSec 30 --measureSec 60");
        System.out.println("     (runs bedside+nurse+db ladder automatically and writes csv/md/svg for each)");
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
                            int measureSec,
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


