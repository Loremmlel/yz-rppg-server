package youzi.lin.loadtest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LoadTestScenarioExecutor {

    interface RunEvents {
        default void onLog(String message) {
        }

        default void onReportPath(Path path) {
        }
    }

    private final LoadTestService service;

    LoadTestScenarioExecutor() {
        this.service = new LoadTestService();
    }

    void execute(String scenario, Map<String, String> options, RunEvents events) throws Exception {
        switch (scenario) {
            case "bedside" -> {
                WsResult result = service.runBedside(options);
                LoadTestService.printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                ReportWriter.writeSingleWsResult("bedside", result, options);
            }
            case "nurse" -> {
                WsResult result = service.runNurse(options);
                LoadTestService.printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                ReportWriter.writeSingleWsResult("nurse", result, options);
            }
            case "bedside-matrix" -> service.runBedsideMatrix(options);
            case "nurse-matrix" -> service.runNurseMatrix(options);
            case "db" -> service.runDbMixed(options);
            case "smart-suite" -> service.runSmartSuite(options);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }

        for (Path path : expectedReports(scenario, options)) {
            events.onReportPath(path.toAbsolutePath().normalize());
        }
        events.onLog("Scenario finished: " + scenario);
    }

    static List<Path> expectedReports(String scenario, Map<String, String> options) {
        List<Path> paths = new ArrayList<>();
        switch (scenario) {
            case "bedside" -> {
                paths.add(Path.of(CliOptions.get(options, "outputCsv", ".\\results\\bedside-result.csv")));
                paths.add(Path.of(CliOptions.get(options, "outputMd", ".\\results\\bedside-result.md")));
            }
            case "nurse" -> {
                paths.add(Path.of(CliOptions.get(options, "outputCsv", ".\\results\\nurse-result.csv")));
                paths.add(Path.of(CliOptions.get(options, "outputMd", ".\\results\\nurse-result.md")));
            }
            case "bedside-matrix" -> {
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\bedside-matrix.csv");
                paths.add(Path.of(csv));
                paths.add(Path.of(CliOptions.get(options, "outputMd", ".\\results\\bedside-matrix.md")));
                paths.add(Path.of(replaceCsv(csv, "-throughput.svg")));
                paths.add(Path.of(replaceCsv(csv, "-latency.svg")));
            }
            case "nurse-matrix" -> {
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\nurse-matrix.csv");
                paths.add(Path.of(csv));
                paths.add(Path.of(CliOptions.get(options, "outputMd", ".\\results\\nurse-matrix.md")));
                paths.add(Path.of(replaceCsv(csv, "-throughput.svg")));
                paths.add(Path.of(replaceCsv(csv, "-latency.svg")));
            }
            case "db" -> {
                String csv = CliOptions.get(options, "outputCsv", "db-latency.csv");
                paths.add(Path.of(csv));
                paths.add(Path.of(CliOptions.get(options, "outputMd", "db-latency.md")));
                paths.add(Path.of(replaceCsv(csv, "-throughput.svg")));
                paths.add(Path.of(replaceCsv(csv, "-latency.svg")));
            }
            case "smart-suite" -> {
                String outDir = CliOptions.get(options, "outDir", ".\\results");
                paths.add(Path.of(outDir, "bedside-ladder.csv"));
                paths.add(Path.of(outDir, "bedside-ladder.md"));
                paths.add(Path.of(outDir, "bedside-ladder-throughput.svg"));
                paths.add(Path.of(outDir, "bedside-ladder-latency.svg"));
                paths.add(Path.of(outDir, "nurse-ladder.csv"));
                paths.add(Path.of(outDir, "nurse-ladder.md"));
                paths.add(Path.of(outDir, "nurse-ladder-throughput.svg"));
                paths.add(Path.of(outDir, "nurse-ladder-latency.svg"));
                paths.add(Path.of(outDir, "db-ladder.csv"));
                paths.add(Path.of(outDir, "db-ladder.md"));
                paths.add(Path.of(outDir, "db-ladder-throughput.svg"));
                paths.add(Path.of(outDir, "db-ladder-latency.svg"));
            }
            default -> {
            }
        }
        return paths;
    }

    private static String replaceCsv(String path, String suffix) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".csv")) {
            return path.substring(0, path.length() - 4) + suffix;
        }
        return path + suffix;
    }
}

