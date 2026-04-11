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
import java.util.stream.Collectors;

final class LoadTestService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    WsResult runBedside(Map<String, String> options) throws Exception {
        String baseUrl = CliOptions.get(options, "baseUrl", "ws://localhost:8080");
        int beds = CliOptions.getInt(options, "beds", 64);
        int fps = CliOptions.getInt(options, "fps", 15);
        int bedIdStart = CliOptions.getInt(options, "bedIdStart", 1);
        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);
        boolean runtimeEnabled = CliOptions.getBoolean(options, "runtimeMetrics", true);
        int runtimeIntervalSec = CliOptions.getInt(options, "runtimeSampleIntervalSec", 1);
        String runtimeEndpoint = CliOptions.get(options, "runtimeEndpoint", RuntimeSampler.defaultRuntimeEndpoint(baseUrl));

        Metrics metrics = new Metrics();
        byte[] image = new byte[256 * 256];
        Arrays.fill(image, (byte) 0xFF);
        RuntimeSampler runtimeSampler = runtimeEnabled ? RuntimeSampler.start(runtimeEndpoint, runtimeIntervalSec) : null;

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
        if (runtimeSampler != null) {
            runtimeSampler.resetMeasurementWindow();
        }

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

        RuntimeSummary runtimeSummary = runtimeSampler != null
                ? runtimeSampler.stopAndSummarize(measureSec)
                : RuntimeSummary.empty();
        return new WsResult("bedside", beds, measureSec, metrics.snapshot(), runtimeSummary);
    }

    WsResult runNurse(Map<String, String> options) throws Exception {
        String baseUrl = CliOptions.get(options, "baseUrl", "ws://localhost:8080");
        String wardCode = CliOptions.get(options, "wardCode", "内科一区");
        int stations = CliOptions.getInt(options, "stations", 300);
        int warmupSec = CliOptions.getInt(options, "warmupSec", 120);
        int measureSec = CliOptions.getInt(options, "measureSec", 180);
        boolean runtimeEnabled = CliOptions.getBoolean(options, "runtimeMetrics", true);
        int runtimeIntervalSec = CliOptions.getInt(options, "runtimeSampleIntervalSec", 1);
        String runtimeEndpoint = CliOptions.get(options, "runtimeEndpoint", RuntimeSampler.defaultRuntimeEndpoint(baseUrl));

        Metrics metrics = new Metrics();
        HttpClient client = HttpClient.newHttpClient();
        List<WebSocket> sockets = new ArrayList<>(stations);
        RuntimeSampler runtimeSampler = runtimeEnabled ? RuntimeSampler.start(runtimeEndpoint, runtimeIntervalSec) : null;

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
        if (runtimeSampler != null) {
            runtimeSampler.resetMeasurementWindow();
        }

        System.out.printf("[nurse] measure %ds ...%n", measureSec);
        TimeUnit.SECONDS.sleep(measureSec);

        for (WebSocket socket : sockets) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (Exception ignored) {
                // ignore close failures
            }
        }

        RuntimeSummary runtimeSummary = runtimeSampler != null
                ? runtimeSampler.stopAndSummarize(measureSec)
                : RuntimeSummary.empty();
        return new WsResult("nurse", stations, measureSec, metrics.snapshot(), runtimeSummary);
    }

    void runBedsideMatrix(Map<String, String> options) throws Exception {
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
            printRuntimeSummary(result.label(), result.runtimeSummary());
            rows.add(ReportWriter.toWsCsvRow(result));
        }

        ReportWriter.writeRows(outputCsv, rows);
        ReportWriter.writeWsMarkdown(outputMd, "Bedside Matrix", "beds", results);
        ReportWriter.writeBedsideCharts(outputCsv, results);
        ReportWriter.writeWsRuntimeArtifacts(outputCsv, outputMd, "Bedside Runtime", "beds", results);
    }

    void runNurseMatrix(Map<String, String> options) throws Exception {
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
            printRuntimeSummary(result.label(), result.runtimeSummary());
            rows.add(ReportWriter.toNurseCsvRow(result));
        }

        ReportWriter.writeRows(outputCsv, rows);
        ReportWriter.writeWsMarkdown(outputMd, "Nurse Matrix", "stations", results);
        ReportWriter.writeNurseCharts(outputCsv, results);
        ReportWriter.writeWsRuntimeArtifacts(outputCsv, outputMd, "Nurse Runtime", "stations", results);
    }

    void runDbMixed(Map<String, String> options) throws Exception {
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

        ReportWriter.writeRows(outputCsv, rows);
        ReportWriter.writeDbMarkdown(outputMd, results);
        ReportWriter.writeDbCharts(outputCsv, results);

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

    void runSmartSuite(Map<String, String> options) throws Exception {
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

    static void printWsResult(String label, int concurrency, int measureSec, WsSnapshot metrics) {
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

    static void printRuntimeSummary(String label, RuntimeSummary runtimeSummary) {
        if (!runtimeSummary.available()) {
            System.out.printf("[%s] runtime metrics unavailable (check /api/loadtest/runtime-snapshot)%n", label);
            return;
        }
        System.out.printf(Locale.ROOT,
                "[%s] runtime cpu(avg/p95/max)=%.2f/%.2f/%.2f%% heap(avg/p95/max)=%.2f/%.2f/%.2fMB gcPause=%dms gcCount=%d threads(avg/max)=%.1f/%d samples=%d%n",
                label,
                runtimeSummary.cpuAvgPct(),
                runtimeSummary.cpuP95Pct(),
                runtimeSummary.cpuMaxPct(),
                runtimeSummary.heapAvgMb(),
                runtimeSummary.heapP95Mb(),
                runtimeSummary.heapMaxMb(),
                runtimeSummary.gcTimeDeltaMs(),
                runtimeSummary.gcCountDelta(),
                runtimeSummary.threadAvg(),
                runtimeSummary.threadMax(),
                runtimeSummary.sampleCount());
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

    private static List<Integer> resolveLevels(Map<String, String> options, String key, List<Integer> fallback) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return CliOptions.getIntList(options, key, joinLevels(fallback));
    }

    private static String joinLevels(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
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
}

