package app.svault.desktop.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 空清單、找不到結果（EmptyState）：說明＋（可選）下一步按鈕。
 */
public final class EmptyState extends VBox {

    private SvButton action;

    /**
     * @param icon        圖示
     * @param title       標題
     * @param description 說明；沒有時 {@code null}
     * @param actionText  行動按鈕文字；無行動時 {@code null}
     * @param onAction    行動
     */
    public EmptyState(IconName icon, String title, String description, String actionText, Runnable onAction) {
        getStyleClass().add("sv-empty-state");
        Label heading = new Label(title);
        heading.getStyleClass().add("sv-empty-title");
        heading.setWrapText(true);
        getChildren().addAll(Icons.large(icon), heading);
        if (description != null) {
            Label body = new Label(description);
            body.getStyleClass().add("sv-empty-description");
            body.setWrapText(true);
            getChildren().add(body);
        }
        if (actionText != null && onAction != null) {
            action = SvButton.primary(actionText);
            action.setOnAction(e -> onAction.run());
            getChildren().add(action);
        }
    }

    /** @return 行動按鈕；無行動時 {@code null} */
    public SvButton action() {
        return action;
    }
}
