package app.svault.desktop.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/**
 * IconButton：32×32 ghost 按鈕，一定有可讀名稱（螢幕閱讀器與提示框共用同一段文字）。
 */
public final class IconButton extends Button {

    private final Region icon;
    private final Tooltip tooltip = new Tooltip();

    /**
     * @param name         圖示
     * @param accessibleText 可讀名稱（例：「複製密碼」）
     */
    public IconButton(IconName name, String accessibleText) {
        icon = Icons.of(name);
        setGraphic(icon);
        getStyleClass().add("sv-icon-button");
        setTooltip(tooltip);
        setLabel(accessibleText);
    }

    /**
     * 換圖示與可讀名稱（例：顯示／隱藏密碼切換時）。
     *
     * @param name           新圖示
     * @param accessibleText 新的可讀名稱
     */
    public void setIcon(IconName name, String accessibleText) {
        Icons.set(icon, name);
        setLabel(accessibleText);
    }

    private void setLabel(String accessibleText) {
        if (accessibleText == null || accessibleText.isBlank()) {
            throw new IllegalArgumentException("IconButton 一定要有可讀名稱");
        }
        setAccessibleText(accessibleText);
        tooltip.setText(accessibleText);
    }
}
