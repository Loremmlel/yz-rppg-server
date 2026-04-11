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
        Map<String, String> options = CliOptions.parse(args);
        LoadTestService service = new LoadTestService();

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
            default -> {
                System.out.println("Unknown scenario: " + scenario);
                printUsage();
            }
        }
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
}

