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
        try (ServerAppLauncher launcher = new ServerAppLauncher(options, events::onLog)) {
            launcher.startIfEnabled();
            switch (scenario) {
                case "bedside" -> {
                    WsResult result = service.runBedside(options);
                    LoadTestService.printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                    LoadTestService.printRuntimeSummary(result.label(), result.runtimeSummary());
                    ReportWriter.writeSingleWsResult("bedside", result, options);
                }
                case "nurse" -> {
                    WsResult result = service.runNurse(options);
                    LoadTestService.printWsResult(result.label(), result.concurrency(), result.measureSec(), result.metrics());
                    LoadTestService.printRuntimeSummary(result.label(), result.runtimeSummary());
                    ReportWriter.writeSingleWsResult("nurse", result, options);
                }
                case "bedside-matrix" -> service.runBedsideMatrix(options);
                case "nurse-matrix" -> service.runNurseMatrix(options);
                case "db" -> service.runDbMixed(options);
                case "smart-suite" -> service.runSmartSuite(options);
                default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
            }
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
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\bedside-result.csv");
                String md = CliOptions.get(options, "outputMd", ".\\results\\bedside-result.md");
                paths.add(Path.of(csv));
                paths.add(Path.of(md));
                paths.add(Path.of(replaceCsv(csv, "-runtime.csv")));
                paths.add(Path.of(replaceMd(md, "-runtime.md")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-resource.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-heap.svg")));
            }
            case "nurse" -> {
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\nurse-result.csv");
                String md = CliOptions.get(options, "outputMd", ".\\results\\nurse-result.md");
                paths.add(Path.of(csv));
                paths.add(Path.of(md));
                paths.add(Path.of(replaceCsv(csv, "-runtime.csv")));
                paths.add(Path.of(replaceMd(md, "-runtime.md")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-resource.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-heap.svg")));
            }
            case "bedside-matrix" -> {
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\bedside-matrix.csv");
                String md = CliOptions.get(options, "outputMd", ".\\results\\bedside-matrix.md");
                paths.add(Path.of(csv));
                paths.add(Path.of(md));
                paths.add(Path.of(replaceCsv(csv, "-throughput.svg")));
                paths.add(Path.of(replaceCsv(csv, "-latency.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime.csv")));
                paths.add(Path.of(replaceMd(md, "-runtime.md")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-resource.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-heap.svg")));
            }
            case "nurse-matrix" -> {
                String csv = CliOptions.get(options, "outputCsv", ".\\results\\nurse-matrix.csv");
                String md = CliOptions.get(options, "outputMd", ".\\results\\nurse-matrix.md");
                paths.add(Path.of(csv));
                paths.add(Path.of(md));
                paths.add(Path.of(replaceCsv(csv, "-throughput.svg")));
                paths.add(Path.of(replaceCsv(csv, "-latency.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime.csv")));
                paths.add(Path.of(replaceMd(md, "-runtime.md")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-resource.svg")));
                paths.add(Path.of(replaceCsv(csv, "-runtime-heap.svg")));
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
                paths.add(Path.of(outDir, "bedside-ladder-runtime.csv"));
                paths.add(Path.of(outDir, "bedside-ladder-runtime.md"));
                paths.add(Path.of(outDir, "bedside-ladder-runtime-resource.svg"));
                paths.add(Path.of(outDir, "bedside-ladder-runtime-heap.svg"));
                paths.add(Path.of(outDir, "nurse-ladder.csv"));
                paths.add(Path.of(outDir, "nurse-ladder.md"));
                paths.add(Path.of(outDir, "nurse-ladder-throughput.svg"));
                paths.add(Path.of(outDir, "nurse-ladder-latency.svg"));
                paths.add(Path.of(outDir, "nurse-ladder-runtime.csv"));
                paths.add(Path.of(outDir, "nurse-ladder-runtime.md"));
                paths.add(Path.of(outDir, "nurse-ladder-runtime-resource.svg"));
                paths.add(Path.of(outDir, "nurse-ladder-runtime-heap.svg"));
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

    private static String replaceMd(String path, String suffix) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".md")) {
            return path.substring(0, path.length() - 3) + suffix;
        }
        return path + suffix;
    }
}

