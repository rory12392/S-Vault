package app.svault.desktop.ui;

import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 頁首（PageHeader）：標題在左、主要行動在右。
 */
public final class PageHeader extends HBox {

    private final Label title = new Label();
    private final HBox actions = new HBox();

    /**
     * @param titleText 頁面標題
     * @param actionNodes 右側的行動（通常是按鈕；primary 最多一個）
     */
    public PageHeader(String titleText, Node... actionNodes) {
        getStyleClass().add("sv-page-header");
        title.setText(titleText);
        title.getStyleClass().add("sv-page-title");
        title.setAccessibleRole(AccessibleRole.TEXT);
        // 空間不夠時先擠右側的行動，標題不截斷
        title.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actions.getStyleClass().add("sv-page-actions");
        actions.getChildren().addAll(actionNodes);
        getChildren().addAll(title, spacer, actions);
    }

    /** @param text 新標題 */
    public void setTitle(String text) {
        title.setText(text);
    }

    /** @return 右側行動的容器 */
    public HBox actions() {
        return actions;
    }
}
