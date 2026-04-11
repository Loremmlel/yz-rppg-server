package youzi.lin.loadtest;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class LoadTestSwingRunner extends SwingWorker<Void, String> {

    private final String scenario;
    private final Map<String, String> options;
    private final Consumer<String> logConsumer;
    private final Consumer<String> reportConsumer;
    private final Runnable doneCallback;

    LoadTestSwingRunner(String scenario,
                        Map<String, String> options,
                        Consumer<String> logConsumer,
                        Consumer<String> reportConsumer,
                        Runnable doneCallback) {
        this.scenario = scenario;
        this.options = options;
        this.logConsumer = logConsumer;
        this.reportConsumer = reportConsumer;
        this.doneCallback = doneCallback;
    }

    @Override
    protected Void doInBackground() {
        try {
            publish("Starting scenario: " + scenario);
            LoadTestScenarioExecutor executor = new LoadTestScenarioExecutor();
            executor.execute(scenario, options, new LoadTestScenarioExecutor.RunEvents() {
                @Override
                public void onLog(String message) {
                    publish(message);
                }

                @Override
                public void onReportPath(java.nio.file.Path path) {
                    reportConsumer.accept(path.toString());
                    publish("Report: " + path);
                }
            });
        } catch (Exception e) {
            publish("Failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    protected void process(List<String> chunks) {
        for (String line : chunks) {
            logConsumer.accept(line);
        }
    }

    @Override
    protected void done() {
        doneCallback.run();
    }
}

