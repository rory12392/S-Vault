package app.svault.desktop.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 覆蓋進度（ProgressOverlay）：解鎖超過 5 秒、背景合併、匯入中。顯示期間停用下方的畫面
 * （滑鼠被覆蓋層擋住，鍵盤因下方停用而無法操作），並以文字說明正在做什麼。
 */
public final class ProgressOverlay extends StackPane {

    private final Node covered;
    private final Label text = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ProgressBar bar = new ProgressBar();
    private final Label percent = new Label();
    private boolean coveredWasDisabled;

    /**
     * @param covered 被蓋住的畫面（顯示時停用）
     */
    public ProgressOverlay(Node covered) {
        this.covered = covered;
        getStyleClass().add("sv-progress-overlay");
        text.getStyleClass().add("sv-progress-text");
        text.setWrapText(true);
        percent.getStyleClass().add("sv-progress-percent");
        bar.getStyleClass().add("sv-progress-bar");
        VBox card = new VBox(spinner, bar, text, percent);
        card.getStyleClass().add("sv-progress-card");
        card.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        getChildren().add(card);
        setVisible(false);
    }

    /**
     * 顯示不確定進度。
     *
     * @param message 說明（例：「正在解鎖…」）
     */
    public void show(String message) {
        prepare(message);
        spinner.setVisible(true);
        spinner.setManaged(true);
        bar.setVisible(false);
        bar.setManaged(false);
        percent.setVisible(false);
        percent.setManaged(false);
        setAccessibleText(message);
    }

    /**
     * 顯示有百分比的進度。
     *
     * @param message  說明
     * @param progress 0～1
     */
    public void show(String message, double progress) {
        prepare(message);
        spinner.setVisible(false);
        spinner.setManaged(false);
        bar.setVisible(true);
        bar.setManaged(true);
        percent.setVisible(true);
        percent.setManaged(true);
        double clamped = Math.max(0, Math.min(1, progress));
        bar.setProgress(clamped);
        String p = UiText.format("progress.percent", Math.round(clamped * 100));
        percent.setText(p);
        setAccessibleText(message + " " + p);
    }

    /** 關閉並恢復下方畫面。 */
    public void hide() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        covered.setDisable(coveredWasDisabled);
    }

    private void prepare(String message) {
        text.setText(message);
        if (!isVisible()) {
            coveredWasDisabled = covered.isDisable();
            covered.setDisable(true);
            setVisible(true);
        }
    }
}
