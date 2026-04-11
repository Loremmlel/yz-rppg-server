package youzi.lin.loadtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ServerAppLauncher implements AutoCloseable {

    private static final String PRESET_NONE = "none";
    private static final String PRESET_G1_4G = "g1-4g";
    private static final String PRESET_G1_8G = "g1-8g";
    private static final String PRESET_ZGC_4G = "zgc-4g";
    private static final String PRESET_ZGC_8G = "zgc-8g";

    private final Map<String, String> options;
    private final Consumer<String> log;
    private final HttpClient httpClient;

    private Process process;
    private Thread shutdownHook;

    ServerAppLauncher(Map<String, String> options, Consumer<String> log) {
        this.options = options;
        this.log = log;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    void startIfEnabled() throws Exception {
        boolean autoStart = CliOptions.getBoolean(options, "serverAutoStart", false);
        if (!autoStart) {
            return;
        }
        if (process != null) {
            return;
        }

        Path workDir = Path.of(CliOptions.get(options, "serverWorkDir", "..\\..")).toAbsolutePath().normalize();
        String baseUrl = CliOptions.get(options, "baseUrl", "ws://localhost:8080");
        String readyEndpoint = CliOptions.get(options, "serverReadyEndpoint", RuntimeSampler.defaultRuntimeEndpoint(baseUrl));
        String profile = CliOptions.get(options, "serverProfile", "loadtest");
        int readyTimeoutSec = CliOptions.getInt(options, "serverReadyTimeoutSec", 120);
        String jvmArgs = buildJvmArgs();

        Path mvnwPath = workDir.resolve("mvnw.cmd");
        List<String> command = new ArrayList<>();
        command.add(mvnwPath.toString());
        command.add("-DskipTests");
        command.add("spring-boot:run");
        command.add("-Dspring-boot.run.profiles=" + profile);
        if (!jvmArgs.isBlank()) {
            command.add("-Dspring-boot.run.jvmArguments=" + jvmArgs);
        }

        log.accept("[server] auto-start enabled, workDir=" + workDir);
        log.accept("[server] command=" + String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir.toFile());
        process = startProcessWithCmdFallback(processBuilder, command, workDir);
        registerShutdownHook();

        startPipe(process.getInputStream(), "OUT");
        startPipe(process.getErrorStream(), "ERR");

        waitUntilReady(readyEndpoint, readyTimeoutSec);
        log.accept("[server] ready endpoint is reachable: " + readyEndpoint);
    }

    @Override
    public void close() {
        unregisterShutdownHook();
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            process = null;
            return;
        }

        log.accept("[server] stopping server process...");
        try {
            stopProcessTree(process);
        } finally {
            process = null;
        }
    }

    private void startPipe(InputStream stream, String kind) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept("[server-" + kind + "] " + line);
                }
            } catch (Exception e) {
                log.accept("[server-" + kind + "] log stream closed: " + e.getMessage());
            }
        }, "loadtest-server-log-" + kind.toLowerCase(Locale.ROOT));
        thread.setDaemon(true);
        thread.start();
    }

    private void waitUntilReady(String endpoint, int timeoutSec) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(5, timeoutSec));
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("Server process exited before ready.");
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 500) {
                    return;
                }
            } catch (Exception e) {
                lastFailure = e;
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        String detail = lastFailure == null ? "timeout" : lastFailure.getMessage();
        throw new IllegalStateException("Server ready check failed: " + endpoint + ", reason=" + detail);
    }

    private Process startProcessWithCmdFallback(ProcessBuilder directBuilder,
                                                List<String> originalCommand,
                                                Path workDir) throws IOException {
        try {
            return directBuilder.start();
        } catch (IOException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            boolean shouldRetryWithCmd = message.contains("error=2") || message.contains("error=193");
            if (!shouldRetryWithCmd) {
                throw ex;
            }
            List<String> cmdCommand = new ArrayList<>();
            cmdCommand.add("cmd.exe");
            cmdCommand.add("/c");
            cmdCommand.addAll(originalCommand);
            log.accept("[server] direct launch failed, retry via cmd.exe /c");
            return new ProcessBuilder(cmdCommand)
                    .directory(workDir.toFile())
                    .start();
        }
    }

    private void registerShutdownHook() {
        shutdownHook = new Thread(() -> {
            Process p = process;
            if (p != null && p.isAlive()) {
                try {
                    stopProcessTree(p);
                } catch (Exception ignored) {
                    // best-effort cleanup during JVM shutdown
                }
            }
        }, "loadtest-server-cleanup-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void unregisterShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is shutting down
        }
        shutdownHook = null;
    }

    private void stopProcessTree(Process target) {
        if (!target.isAlive()) {
            return;
        }
        if (isWindows()) {
            try {
                Process killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(target.pid()), "/T", "/F")
                        .start();
                killer.waitFor(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.accept("[server] taskkill failed: " + e.getMessage());
            }
        } else {
            target.destroy();
        }

        try {
            if (!target.waitFor(12, TimeUnit.SECONDS) && target.isAlive()) {
                log.accept("[server] force kill server process.");
                target.destroyForcibly();
                target.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String buildJvmArgs() {
        String preset = CliOptions.get(options, "serverJvmPreset", PRESET_G1_4G).toLowerCase(Locale.ROOT);
        String custom = CliOptions.get(options, "serverJvmArgs", "");
        boolean instrumentation = CliOptions.getBoolean(options, "serverEnableLoadtestInstrumentation", true);

        StringBuilder args = new StringBuilder();
        String presetArgs = jvmPresetArgs(preset);
        if (!presetArgs.isBlank()) {
            args.append(presetArgs);
        }
        if (!custom.isBlank()) {
            if (!args.isEmpty()) {
                args.append(' ');
            }
            args.append(custom.trim());
        }
        if (instrumentation) {
            if (!args.isEmpty()) {
                args.append(' ');
            }
            args.append("-Dapp.loadtest.grpc-mock.enabled=true -Dapp.loadtest.nurse-pump.enabled=true");
        }
        return args.toString();
    }

    private static String jvmPresetArgs(String preset) {
        return switch (preset) {
            case PRESET_NONE -> "";
            case PRESET_G1_8G -> "-Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+AlwaysPreTouch";
            case PRESET_ZGC_4G -> "-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch";
            case PRESET_ZGC_8G -> "-Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch";
            case PRESET_G1_4G -> "-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+AlwaysPreTouch";
            default -> throw new IllegalArgumentException("Unknown --serverJvmPreset: " + preset);
        };
    }
}

