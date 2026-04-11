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
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class LoadTestGuiFrame extends JFrame {

    private final JComboBox<String> scenarioBox = new JComboBox<>(new String[]{
            "bedside", "nurse", "bedside-matrix", "nurse-matrix", "db", "smart-suite"
    });
    private final JComboBox<String> profileBox = new JComboBox<>(new String[]{"quick", "balanced", "high"});
    private final JCheckBox cleanupBox = new JCheckBox("cleanup", true);

    private final JTextField baseUrlField = new JTextField("ws://localhost:8080");
    private final JTextField wardCodeField = new JTextField("内科一区");
    private final JTextField outDirField = new JTextField(".\\results");

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

    private final JButton runButton = new JButton("Run");
    private final JButton stopButton = new JButton("Stop");
    private final JButton openReportButton = new JButton("Open Selected Report");
    private final JButton browseOutDirButton = new JButton("Browse OutDir");

    private LoadTestSwingRunner currentRunner;

    LoadTestGuiFrame() {
        super("Load Test GUI");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(new Dimension(1200, 760));
        setLocationRelativeTo(null);

        JPanel fieldsPanel = new JPanel(new GridLayout(0, 4, 8, 6));
        addField(fieldsPanel, "scenario", scenarioBox);
        addField(fieldsPanel, "profile", profileBox);
        addField(fieldsPanel, "cleanup", cleanupBox);
        addField(fieldsPanel, "baseUrl", baseUrlField);
        addField(fieldsPanel, "wardCode", wardCodeField);
        addField(fieldsPanel, "outDir", outDirField);
        addField(fieldsPanel, "warmupSec", warmupField);
        addField(fieldsPanel, "measureSec", measureField);
        addField(fieldsPanel, "fps", fpsField);
        addField(fieldsPanel, "beds", bedsField);
        addField(fieldsPanel, "stations", stationsField);
        addField(fieldsPanel, "writeRatio", writeRatioField);
        addField(fieldsPanel, "bedsLevels", bedsLevelsField);
        addField(fieldsPanel, "stationsLevels", stationsLevelsField);
        addField(fieldsPanel, "concurrencyLevels", concurrencyLevelsField);
        addField(fieldsPanel, "jdbcUrl", jdbcUrlField);
        addField(fieldsPanel, "username", usernameField);
        addField(fieldsPanel, "password", passwordField);

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
    }

    private void startRun() {
        if (currentRunner != null && !currentRunner.isDone()) {
            appendLog("A run is already active.");
            return;
        }

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
        return values;
    }

    private void setRunningState() {
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void setIdleState() {
        runButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void stopRun() {
        if (currentRunner != null && !currentRunner.isDone()) {
            currentRunner.cancel(true);
            appendLog("Cancel requested.");
        }
        setIdleState();
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
            JOptionPane.showMessageDialog(this, "Select a report first.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try {
            Path path = Path.of(pathText);
            if (!Files.exists(path)) {
                JOptionPane.showMessageDialog(this, "File not found: " + path, "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Open failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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

    private static void addField(JPanel panel, String label, java.awt.Component component) {
        panel.add(new JLabel(label));
        panel.add(component);
    }
}

