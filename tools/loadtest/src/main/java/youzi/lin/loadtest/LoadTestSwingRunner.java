package youzi.lin.loadtest;

import javax.swing.SwingWorker;
import java.util.concurrent.CancellationException;
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
            if (isCancelled()) {
                publish("任务已在启动前取消。");
                return null;
            }

            publish("开始执行场景: " + scenario);
            LoadTestScenarioExecutor executor = new LoadTestScenarioExecutor();
            executor.execute(scenario, options, new LoadTestScenarioExecutor.RunEvents() {
                @Override
                public void onLog(String message) {
                    publish(message);
                }

                @Override
                public void onReportPath(java.nio.file.Path path) {
                    publish("__REPORT__" + path);
                    publish("报告文件: " + path);
                }
            });
            if (isCancelled()) {
                publish("已收到停止请求，任务结束。");
            } else {
                publish("场景执行完成: " + scenario);
            }
        } catch (CancellationException e) {
            publish("任务已取消。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publish("任务线程被中断，正在结束。");
        } catch (Exception e) {
            if (isCancelled()) {
                publish("已请求停止，任务正在收尾。");
            } else {
                publish("执行失败: " + e.getMessage());
            }
        }
        return null;
    }

    @Override
    protected void process(List<String> chunks) {
        for (String line : chunks) {
            if (line.startsWith("__REPORT__")) {
                reportConsumer.accept(line.substring("__REPORT__".length()));
            } else {
                logConsumer.accept(line);
            }
        }
    }

    @Override
    protected void done() {
        doneCallback.run();
    }
}

