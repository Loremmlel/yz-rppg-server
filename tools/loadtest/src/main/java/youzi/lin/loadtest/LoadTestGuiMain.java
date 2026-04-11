package youzi.lin.loadtest;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class LoadTestGuiMain {

    private LoadTestGuiMain() {
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // keep default look and feel
            }
            new LoadTestGuiFrame().setVisible(true);
        });
    }

    public static void main(String[] args) {
        launch();
    }
}

