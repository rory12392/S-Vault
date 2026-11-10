package app.svault.desktop.ui;

import javafx.scene.layout.Region;

/**
 * 以 {@code icons.css} 的 {@code -fx-shape} 畫圖示：一個 Region，顏色由 base.css 依所在元件決定。
 */
public final class Icons {

    private Icons() {
    }

    /**
     * @param name 圖示
     * @return 20px 圖示
     */
    public static Region of(IconName name) {
        Region icon = new Region();
        icon.getStyleClass().addAll("sv-icon", name.styleClass());
        // 圖示只是裝飾，可讀名稱由所在的按鈕提供
        icon.setFocusTraversable(false);
        icon.setMouseTransparent(true);
        return icon;
    }

    /**
     * @param name 圖示
     * @return 16px 圖示（列內的狀態圖示）
     */
    public static Region small(IconName name) {
        Region icon = of(name);
        icon.getStyleClass().add("sv-icon-small");
        return icon;
    }

    /**
     * @param name 圖示
     * @return 48px 圖示（EmptyState）
     */
    public static Region large(IconName name) {
        Region icon = of(name);
        icon.getStyleClass().add("sv-icon-large");
        return icon;
    }

    /**
     * 換掉既有圖示的形狀。
     *
     * @param icon {@link #of} 建立的圖示
     * @param name 新圖示
     */
    public static void set(Region icon, IconName name) {
        icon.getStyleClass().removeIf(c -> c.startsWith("sv-icon-") && !c.equals("sv-icon-small")
                && !c.equals("sv-icon-large"));
        icon.getStyleClass().add(name.styleClass());
    }
}
