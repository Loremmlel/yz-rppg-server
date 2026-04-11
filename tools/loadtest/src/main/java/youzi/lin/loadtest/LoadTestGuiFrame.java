package youzi.lin.loadtest;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LoadTestGuiFrame extends JFrame {

    private final JComboBox<String> scenarioBox = new JComboBox<>(new String[]{
            "bedside", "nurse", "bedside-matrix", "nurse-matrix", "db", "smart-suite"
    });
    private final JComboBox<String> profileBox = new JComboBox<>(new String[]{"quick", "balanced", "high"});
    private final JCheckBox cleanupBox = new JCheckBox("cleanup", true);
    private final JCheckBox serverAutoStartBox = new JCheckBox("autoStartServer", true);
    private final JCheckBox serverInstrumentationBox = new JCheckBox("loadtest埋点开关", true);

    private final JTextField baseUrlField = new JTextField("ws://localhost:8080");
    private final JTextField wardCodeField = new JTextField("内科一区");
    private final JTextField outDirField = new JTextField(".\\results");
    private final JTextField serverWorkDirField = new JTextField("..\\..");
    private final JTextField serverProfileField = new JTextField("loadtest");
    private final JComboBox<String> serverJvmPresetBox = new JComboBox<>(new String[]{"g1-4g", "g1-8g", "zgc-4g", "zgc-8g", "none"});
    private final JTextField serverJvmArgsField = new JTextField();
    private final JTextField serverReadyTimeoutField = new JTextField("120");

    private final JTextField warmupField = new JTextField("30");
    private final JTextField measureField = new JTextField("60");
    private final JTextField fpsField = new JTextField("15");
    private final JTextField bedsField = new JTextField("64");
    private final JTextField stationsField = new JTextField("500");
    private final JTextField writeRatioField = new JTextField("0.8");

    private final JTextField bedsLevelsField = new JTextField("16,32,64,128,256");
    private final JTextField stationsLevelsField = new JTextField("50,100,200,500,1000");
    private final JTextField concurrencyLevelsField = new JTextField("16,32,64,128");

    private final JTextField jdbcUrlField = new JTextField("jdbc:postgresql://localhost:5432/rppg");
    private final JTextField usernameField = new JTextField("postgres");
    private final JPasswordField passwordField = new JPasswordField();

    private final JTextArea logArea = new JTextArea();
    private final DefaultListModel<String> reportModel = new DefaultListModel<>();
    private final JList<String> reportList = new JList<>(reportModel);
    private final JPanel fieldsPanel = new JPanel(new GridLayout(0, 4, 8, 6));
    private final List<FieldBinding> fieldBindings = new ArrayList<>();

    private final JButton runButton = new JButton("开始压测");
    private final JButton stopButton = new JButton("停止");
    private final JButton openReportButton = new JButton("打开选中报告");
    private final JButton browseOutDirButton = new JButton("选择输出目录");

    private LoadTestSwingRunner currentRunner;
    private boolean stopRequested;

    LoadTestGuiFrame() {
        super("压测工具图形界面");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(new Dimension(1200, 760));
        setLocationRelativeTo(null);

        registerFields();
        refreshVisibleFields();

        JPanel topActions = new JPanel(new GridLayout(1, 4, 8, 6));
        topActions.add(runButton);
        topActions.add(stopButton);
        topActions.add(browseOutDirButton);
        topActions.add(openReportButton);

        JPanel topPanel = new JPanel(new BorderLayout(0, 8));
        topPanel.add(fieldsPanel, BorderLayout.CENTER);
        topPanel.add(topActions, BorderLayout.SOUTH);

        logArea.setEditable(false);
        reportList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane logScroll = new JScrollPane(logArea);
        JScrollPane reportScroll = new JScrollPane(reportList);
        logScroll.setPreferredSize(new Dimension(800, 300));
        reportScroll.setPreferredSize(new Dimension(380, 300));

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
        bottomPanel.add(logScroll, BorderLayout.CENTER);
        bottomPanel.add(reportScroll, BorderLayout.EAST);

        setLayout(new BorderLayout(8, 8));
        add(topPanel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.CENTER);

        stopButton.setEnabled(false);
        bindActions();
    }

    private void bindActions() {
        runButton.addActionListener(e -> startRun());
        stopButton.addActionListener(e -> stopRun());
        browseOutDirButton.addActionListener(e -> pickOutDir());
        openReportButton.addActionListener(e -> openSelectedReport());
        scenarioBox.addActionListener(e -> refreshVisibleFields());
    }

    private void startRun() {
        if (currentRunner != null && !currentRunner.isDone()) {
            appendLog("已有压测任务在运行，请先停止或等待完成。");
            return;
        }

        stopRequested = false;
        reportModel.clear();
        String scenario = String.valueOf(scenarioBox.getSelectedItem());
        Map<String, String> options = buildOptions();

        currentRunner = new LoadTestSwingRunner(
                scenario,
                options,
                this::appendLog,
                this::appendReport,
                () -> SwingUtilities.invokeLater(this::setIdleState)
        );

        setRunningState();
        currentRunner.execute();
    }

    private Map<String, String> buildOptions() {
        Map<String, String> values = new LinkedHashMap<>();
        putIfNotBlank(values, "baseUrl", baseUrlField.getText());
        putIfNotBlank(values, "wardCode", wardCodeField.getText());
        putIfNotBlank(values, "outDir", outDirField.getText());
        putIfNotBlank(values, "serverWorkDir", serverWorkDirField.getText());
        putIfNotBlank(values, "serverProfile", serverProfileField.getText());
        putIfNotBlank(values, "serverJvmPreset", String.valueOf(serverJvmPresetBox.getSelectedItem()));
        putIfNotBlank(values, "serverJvmArgs", serverJvmArgsField.getText());
        putIfNotBlank(values, "serverReadyTimeoutSec", serverReadyTimeoutField.getText());

        putIfNotBlank(values, "warmupSec", warmupField.getText());
        putIfNotBlank(values, "measureSec", measureField.getText());
        putIfNotBlank(values, "fps", fpsField.getText());
        putIfNotBlank(values, "beds", bedsField.getText());
        putIfNotBlank(values, "stations", stationsField.getText());
        putIfNotBlank(values, "writeRatio", writeRatioField.getText());

        putIfNotBlank(values, "bedsLevels", bedsLevelsField.getText());
        putIfNotBlank(values, "stationsLevels", stationsLevelsField.getText());
        putIfNotBlank(values, "concurrencyLevels", concurrencyLevelsField.getText());

        putIfNotBlank(values, "jdbcUrl", jdbcUrlField.getText());
        putIfNotBlank(values, "username", usernameField.getText());
        putIfNotBlank(values, "password", new String(passwordField.getPassword()));

        putIfNotBlank(values, "profile", String.valueOf(profileBox.getSelectedItem()));
        values.put("cleanup", String.valueOf(cleanupBox.isSelected()));
        values.put("serverAutoStart", String.valueOf(serverAutoStartBox.isSelected()));
        values.put("serverEnableLoadtestInstrumentation", String.valueOf(serverInstrumentationBox.isSelected()));
        return values;
    }

    private void setRunningState() {
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        stopButton.setText("停止");
    }

    private void setIdleState() {
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
        stopButton.setText("停止");
        if (stopRequested) {
            appendLog("停止流程结束，界面已恢复空闲状态。");
            stopRequested = false;
        }
    }

    private void stopRun() {
        if (currentRunner != null && !currentRunner.isDone()) {
            stopRequested = true;
            currentRunner.cancel(true);
            stopButton.setEnabled(false);
            stopButton.setText("停止中...");
            appendLog("已发送停止请求，等待任务收尾...");
            return;
        }
        appendLog("当前没有运行中的任务。");
    }

    private void pickOutDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setSelectedFile(new File(outDirField.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openSelectedReport() {
        String pathText = reportList.getSelectedValue();
        if (pathText == null || pathText.isBlank()) {
            JOptionPane.showMessageDialog(this, "请先选择一个报告文件。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try {
            Path path = Path.of(pathText);
            if (!Files.exists(path)) {
                JOptionPane.showMessageDialog(this, "文件不存在: " + path, "警告", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "打开失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void appendReport(String path) {
        if (!reportModel.contains(path)) {
            reportModel.addElement(path);
        }
    }

    private static void putIfNotBlank(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private void registerFields() {
        addBinding("场景", "scenario", scenarioBox, allScenarios());
        addBinding("压力档位", "profile", profileBox, setOf("bedside-matrix", "nurse-matrix", "db", "smart-suite"));
        addBinding("自动清理DB假数据", "cleanup", cleanupBox, setOf("db", "smart-suite"));

        addBinding("服务地址(baseUrl)", "baseUrl", baseUrlField, setOf("bedside", "nurse", "bedside-matrix", "nurse-matrix", "smart-suite"));
        addBinding("病区编码(wardCode)", "wardCode", wardCodeField, setOf("nurse", "nurse-matrix", "smart-suite"));
        addBinding("输出目录(outDir)", "outDir", outDirField, setOf("smart-suite"));
        addBinding("自动启动主应用(serverAutoStart)", "serverAutoStart", serverAutoStartBox, allScenariosExceptGui());
        addBinding("主应用目录(serverWorkDir)", "serverWorkDir", serverWorkDirField, allScenariosExceptGui());
        addBinding("主应用profile(serverProfile)", "serverProfile", serverProfileField, allScenariosExceptGui());
        addBinding("JVM预设(serverJvmPreset)", "serverJvmPreset", serverJvmPresetBox, allScenariosExceptGui());
        addBinding("自定义JVM参数(serverJvmArgs)", "serverJvmArgs", serverJvmArgsField, allScenariosExceptGui());
        addBinding("主应用就绪超时秒(serverReadyTimeoutSec)", "serverReadyTimeoutSec", serverReadyTimeoutField, allScenariosExceptGui());
        addBinding("启用loadtest埋点(serverEnableLoadtestInstrumentation)", "serverEnableLoadtestInstrumentation", serverInstrumentationBox, allScenariosExceptGui());

        addBinding("预热秒数(warmupSec)", "warmupSec", warmupField, allScenariosExceptGui());
        addBinding("测量秒数(measureSec)", "measureSec", measureField, allScenariosExceptGui());
        addBinding("帧率(fps)", "fps", fpsField, setOf("bedside", "bedside-matrix", "smart-suite"));
        addBinding("床位并发(beds)", "beds", bedsField, setOf("bedside"));
        addBinding("护士站并发(stations)", "stations", stationsField, setOf("nurse"));
        addBinding("写入占比(writeRatio)", "writeRatio", writeRatioField, setOf("db", "smart-suite"));

        addBinding("床位阶梯(bedsLevels)", "bedsLevels", bedsLevelsField, setOf("bedside-matrix", "smart-suite"));
        addBinding("护士站阶梯(stationsLevels)", "stationsLevels", stationsLevelsField, setOf("nurse-matrix", "smart-suite"));
        addBinding("DB并发阶梯(concurrencyLevels)", "concurrencyLevels", concurrencyLevelsField, setOf("db", "smart-suite"));

        addBinding("JDBC地址(jdbcUrl)", "jdbcUrl", jdbcUrlField, setOf("db", "smart-suite"));
        addBinding("数据库用户名(username)", "username", usernameField, setOf("db", "smart-suite"));
        addBinding("数据库密码(password)", "password", passwordField, setOf("db", "smart-suite"));
    }

    private void refreshVisibleFields() {
        String scenario = String.valueOf(scenarioBox.getSelectedItem());
        fieldsPanel.removeAll();
        for (FieldBinding binding : fieldBindings) {
            boolean visible = binding.scenarios.contains(scenario);
            binding.label.setVisible(visible);
            binding.component.setVisible(visible);
            if (visible) {
                fieldsPanel.add(binding.label);
                fieldsPanel.add(binding.component);
            }
        }
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private void addBinding(String text, String key, Component component, Set<String> scenarios) {
        fieldBindings.add(new FieldBinding(new JLabel(text), component, key, scenarios));
    }

    private static Set<String> allScenarios() {
        return setOf("bedside", "nurse", "bedside-matrix", "nurse-matrix", "db", "smart-suite");
    }

    private static Set<String> allScenariosExceptGui() {
        return allScenarios();
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static final class FieldBinding {
        private final JLabel label;
        private final Component component;
        @SuppressWarnings("unused")
        private final String key;
        private final Set<String> scenarios;

        private FieldBinding(JLabel label, Component component, String key, Set<String> scenarios) {
            this.label = label;
            this.component = component;
            this.key = key;
            this.scenarios = scenarios;
        }
    }
}

