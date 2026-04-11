package youzi.lin.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RuntimeSampler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String endpoint;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    private final List<Double> cpuPctSamples = new ArrayList<>();
    private final List<Double> heapMbSamples = new ArrayList<>();
    private final List<Double> nonHeapMbSamples = new ArrayList<>();
    private final List<Integer> threadSamples = new ArrayList<>();

    private long startGcCount;
    private long startGcTimeMs;
    private long lastGcCount;
    private long lastGcTimeMs;
    private boolean started;

    private RuntimeSampler(String endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loadtest-runtime-sampler");
            t.setDaemon(true);
            return t;
        });
    }

    static RuntimeSampler start(String endpoint, int intervalSec) {
        RuntimeSampler sampler = new RuntimeSampler(endpoint);
        sampler.scheduler.scheduleAtFixedRate(sampler::pollOnce, 0, Math.max(1, intervalSec), TimeUnit.SECONDS);
        return sampler;
    }

    synchronized void resetMeasurementWindow() {
        cpuPctSamples.clear();
        heapMbSamples.clear();
        nonHeapMbSamples.clear();
        threadSamples.clear();
        startGcCount = lastGcCount;
        startGcTimeMs = lastGcTimeMs;
        started = true;
    }

    synchronized RuntimeSummary stopAndSummarize(int measureSec) {
        scheduler.shutdownNow();
        if (!started || cpuPctSamples.isEmpty()) {
            return RuntimeSummary.empty();
        }

        double cpuAvg = avg(cpuPctSamples);
        double cpuP95 = percentile(cpuPctSamples, 95.0);
        double cpuMax = max(cpuPctSamples);

        double heapAvg = avg(heapMbSamples);
        double heapP95 = percentile(heapMbSamples, 95.0);
        double heapMax = max(heapMbSamples);

        double nonHeapAvg = avg(nonHeapMbSamples);
        double nonHeapMax = max(nonHeapMbSamples);

        double threadAvg = avgInts(threadSamples);
        int threadMax = threadSamples.stream().mapToInt(Integer::intValue).max().orElse(0);

        long gcCountDelta = Math.max(0, lastGcCount - startGcCount);
        long gcTimeDeltaMs = Math.max(0, lastGcTimeMs - startGcTimeMs);
        double gcPauseMsPerSec = gcTimeDeltaMs / (double) Math.max(1, measureSec);

        return new RuntimeSummary(
                true,
                cpuPctSamples.size(),
                cpuAvg,
                cpuP95,
                cpuMax,
                heapAvg,
                heapP95,
                heapMax,
                nonHeapAvg,
                nonHeapMax,
                gcCountDelta,
                gcTimeDeltaMs,
                gcPauseMsPerSec,
                threadAvg,
                threadMax
        );
    }

    private void pollOnce() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return;
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            double cpu = root.path("processCpuPct").asDouble(-1.0);
            double heapMb = bytesToMb(root.path("heapUsedBytes").asDouble(0.0));
            double nonHeapMb = bytesToMb(root.path("nonHeapUsedBytes").asDouble(0.0));
            long gcCount = root.path("gcCount").asLong(0);
            long gcTimeMs = root.path("gcTimeMs").asLong(0);
            int threadCount = root.path("threadCount").asInt(0);

            synchronized (this) {
                lastGcCount = gcCount;
                lastGcTimeMs = gcTimeMs;
                if (!started) {
                    return;
                }
                if (cpu >= 0.0) {
                    cpuPctSamples.add(cpu);
                }
                heapMbSamples.add(heapMb);
                nonHeapMbSamples.add(nonHeapMb);
                threadSamples.add(threadCount);
            }
        } catch (Exception ignored) {
            // Ignore transient sampling errors to avoid affecting the benchmark flow.
        }
    }

    private static double bytesToMb(double bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static double avg(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double avgInts(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum / (double) values.size();
    }

    private static double max(List<Double> values) {
        return values.stream().max(Comparator.naturalOrder()).orElse(0.0);
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    static String defaultRuntimeEndpoint(String baseUrl) {
        String httpBase = baseUrl;
        if (baseUrl.startsWith("ws://")) {
            httpBase = "http://" + baseUrl.substring("ws://".length());
        } else if (baseUrl.startsWith("wss://")) {
            httpBase = "https://" + baseUrl.substring("wss://".length());
        }
        return String.format(Locale.ROOT, "%s/api/loadtest/runtime-snapshot", trimTrailingSlash(httpBase));
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}


