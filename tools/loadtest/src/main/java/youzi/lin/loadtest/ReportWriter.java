package youzi.lin.loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ReportWriter {

    private ReportWriter() {
    }

    static void writeSingleWsResult(String scenario, WsResult result, Map<String, String> options) throws Exception {
        String outputCsv = CliOptions.get(options, "outputCsv", ".\\results\\" + scenario + "-result.csv");
        String outputMd = CliOptions.get(options, "outputMd", ".\\results\\" + scenario + "-result.md");

        List<String> rows = new ArrayList<>();
        rows.add("concurrency,sent,recv,errors,send_rate,recv_rate,send_p95_ms,send_p99_ms,recv_p95_ms,recv_p99_ms");
        rows.add(toWsCsvRow(result));
        writeRows(outputCsv, rows);
        writeWsMarkdown(outputMd, scenario + " Result", "concurrency", List.of(result));
        writeWsRuntimeArtifacts(outputCsv, outputMd, scenario + " Runtime", "concurrency", List.of(result));
    }

    static void writeRows(String outputCsv, List<String> rows) throws Exception {
        Path path = Path.of(outputCsv);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, rows);
        System.out.println("[report] csv written: " + path.toAbsolutePath());
    }

    static void writeWsMarkdown(String outputMd,
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

        Path path = Path.of(outputMd);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, lines);
        System.out.println("[report] markdown written: " + path.toAbsolutePath());
    }

    static void writeDbMarkdown(String outputMd, List<DbResult> results) throws Exception {
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

        Path path = Path.of(outputMd);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, lines);
        System.out.println("[report] markdown written: " + path.toAbsolutePath());
    }

    static String toWsCsvRow(WsResult result) {
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

    static String toNurseCsvRow(WsResult result) {
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

    static void writeWsRuntimeArtifacts(String wsOutputCsv,
                                        String wsOutputMd,
                                        String title,
                                        String concurrencyColumn,
                                        List<WsResult> results) throws Exception {
        String runtimeCsv = replaceSuffix(wsOutputCsv, ".csv", "-runtime.csv");
        String runtimeMd = replaceSuffix(wsOutputMd, ".md", "-runtime.md");
        List<String> rows = new ArrayList<>();
        rows.add(concurrencyColumn + ",runtime_available,samples,cpu_avg_pct,cpu_p95_pct,cpu_max_pct,heap_avg_mb,heap_p95_mb,heap_max_mb,gc_count,gc_pause_ms,gc_pause_ms_per_sec,threads_avg,threads_max");

        for (WsResult result : results) {
            RuntimeSummary r = result.runtimeSummary();
            rows.add(String.format(Locale.ROOT,
                    "%d,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%d",
                    result.concurrency(),
                    r.available(),
                    r.sampleCount(),
                    r.cpuAvgPct(),
                    r.cpuP95Pct(),
                    r.cpuMaxPct(),
                    r.heapAvgMb(),
                    r.heapP95Mb(),
                    r.heapMaxMb(),
                    r.gcCountDelta(),
                    r.gcTimeDeltaMs(),
                    r.gcPauseMsPerSec(),
                    r.threadAvg(),
                    r.threadMax()));
        }
        writeRows(runtimeCsv, rows);

        List<String> lines = new ArrayList<>();
        lines.add("# " + title);
        lines.add("");
        lines.add("| " + concurrencyColumn + " | runtime | cpu avg% | cpu p95% | heap avg(MB) | gc pause(ms) | threads avg | samples |");
        lines.add("|---:|:---:|---:|---:|---:|---:|---:|---:|");
        for (WsResult result : results) {
            RuntimeSummary r = result.runtimeSummary();
            lines.add(String.format(Locale.ROOT,
                    "| %d | %s | %.2f | %.2f | %.2f | %d | %.2f | %d |",
                    result.concurrency(),
                    r.available() ? "yes" : "no",
                    r.cpuAvgPct(),
                    r.cpuP95Pct(),
                    r.heapAvgMb(),
                    r.gcTimeDeltaMs(),
                    r.threadAvg(),
                    r.sampleCount()));
        }

        Path mdPath = Path.of(runtimeMd);
        Path mdParent = mdPath.toAbsolutePath().getParent();
        if (mdParent != null) {
            Files.createDirectories(mdParent);
        }
        Files.write(mdPath, lines);
        System.out.println("[report] markdown written: " + mdPath.toAbsolutePath());

        writeWsRuntimeChart(runtimeCsv, title, concurrencyColumn, results);
    }

    private static void writeWsRuntimeChart(String runtimeCsv,
                                            String title,
                                            String concurrencyColumn,
                                            List<WsResult> results) throws Exception {
        List<Double> x = new ArrayList<>();
        List<Double> cpuAvg = new ArrayList<>();
        List<Double> heapAvg = new ArrayList<>();
        List<Double> gcPausePerSec = new ArrayList<>();

        for (WsResult result : results) {
            RuntimeSummary r = result.runtimeSummary();
            if (!r.available()) {
                continue;
            }
            x.add((double) result.concurrency());
            cpuAvg.add(r.cpuAvgPct());
            heapAvg.add(r.heapAvgMb());
            gcPausePerSec.add(r.gcPauseMsPerSec());
        }

        if (x.isEmpty()) {
            return;
        }

        String chartPath = baseName(runtimeCsv) + "-resource.svg";
        writeLineChartSvg(chartPath,
                title + " Resource",
                concurrencyColumn,
                "metric",
                x,
                Map.of(
                        "cpu_avg_pct", cpuAvg,
                        "heap_avg_mb", heapAvg,
                        "gc_pause_ms_per_sec", gcPausePerSec
                ));
    }

    static void writeBedsideCharts(String outputCsv, List<WsResult> results) throws Exception {
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

    static void writeNurseCharts(String outputCsv, List<WsResult> results) throws Exception {
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

    static void writeDbCharts(String outputCsv, List<DbResult> results) throws Exception {
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

        for (Double xRaw : xValues) {
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

        Path path = Path.of(outputPath);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, svg.toString());
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

    private static String replaceSuffix(String source, String suffix, String replacement) {
        if (source.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            return source.substring(0, source.length() - suffix.length()) + replacement;
        }
        return source + replacement;
    }
}

