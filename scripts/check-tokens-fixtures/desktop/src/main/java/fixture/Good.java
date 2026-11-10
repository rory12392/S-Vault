// token-check-expect: none（ColorScheme、Font.getDefault 不是寫死樣式）
package fixture;

class Good {
    Object scheme = javafx.application.ColorScheme.DARK;
    Object font = javafx.scene.text.Font.getDefault();
    String styleClass = "sv-button-primary";
    Object swatch = "#1F5FAF"; // token-check: allow 展示頁色票
}
