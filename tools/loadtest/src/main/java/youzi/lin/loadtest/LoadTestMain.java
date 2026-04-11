package youzi.lin.loadtest;

import java.util.Locale;
import java.util.Map;

public final class LoadTestMain {

    private LoadTestMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String scenario = args[0].toLowerCase(Locale.ROOT);
        if ("gui".equals(scenario)) {
            LoadTestGuiMain.launch();
            return;
        }

        Map<String, String> options = CliOptions.parse(args);

        try {
            new LoadTestScenarioExecutor().execute(scenario, options, new LoadTestScenarioExecutor.RunEvents() {
            });
        } catch (IllegalArgumentException ex) {
            System.out.println(ex.getMessage());
            printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  bedside --baseUrl ws://localhost:8080 --beds 64 --fps 15 --warmupSec 120 --measureSec 180");
        System.out.println("     (runtime metrics enabled by default: --runtimeMetrics true --runtimeSampleIntervalSec 1)");
        System.out.println("     (writes .\\results\\bedside-result.csv and .\\results\\bedside-result.md by default)");
        System.out.println("  bedside-matrix --baseUrl ws://localhost:8080 --bedsLevels 16,32,64,128,256 --profile balanced --fps 15 --outputCsv .\\results\\bedside-matrix.csv --outputMd .\\results\\bedside-matrix.md");
        System.out.println("  nurse --baseUrl ws://localhost:8080 --wardCode 内科一区 --stations 500 --warmupSec 120 --measureSec 180");
        System.out.println("     (runtime metrics enabled by default: --runtimeMetrics true --runtimeSampleIntervalSec 1)");
        System.out.println("     (writes .\\results\\nurse-result.csv and .\\results\\nurse-result.md by default)");
        System.out.println("  nurse-matrix --baseUrl ws://localhost:8080 --wardCode 内科一区 --stationsLevels 50,100,200,500,1000 --profile balanced --outputCsv .\\results\\nurse-matrix.csv --outputMd .\\results\\nurse-matrix.md");
        System.out.println("  db --jdbcUrl jdbc:postgresql://localhost:5432/rppg --username postgres --password 1234 \\");
        System.out.println("     --concurrencyLevels 16,32,64,128 --profile balanced --writeRatio 0.8 --warmupSec 120 --measureSec 180 --cleanup true --outputCsv .\\results\\db-latency.csv --outputMd .\\results\\db-latency.md");
        System.out.println("  smart-suite --baseUrl ws://localhost:8080 --wardCode 内科一区 --profile balanced --outDir .\\results --warmupSec 30 --measureSec 60");
        System.out.println("     (runs bedside+nurse+db ladder automatically and writes csv/md/svg for each)");
        System.out.println("  runtime endpoint default: http://<baseUrl-host>/api/loadtest/runtime-snapshot (loadtest profile)");
        System.out.println("  optional server auto-start:");
        System.out.println("     --serverAutoStart true --serverWorkDir ..\\.. --serverProfile loadtest");
        System.out.println("     --serverJvmPreset g1-4g|g1-8g|zgc-4g|zgc-8g|none --serverJvmArgs \"-XX:+HeapDumpOnOutOfMemoryError\"");
        System.out.println("     --serverEnableLoadtestInstrumentation true --serverReadyTimeoutSec 120");
        System.out.println("  gui");
        System.out.println("     (launches a Swing GUI for all scenarios and options)");
    }
}

