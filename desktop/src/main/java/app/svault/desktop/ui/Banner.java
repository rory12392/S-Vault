package app.svault.desktop.ui;

import java.util.Locale;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 頁面層級的持續訊息（Banner）：info（primary-container）／warning／error（danger-container），
 * 可含一個動作按鈕（可用 Tab 到）。在清單上方佔一列。
 */
public final class Banner extends HBox {

    /** 種類。 */
    public enum Kind {
        INFO(IconName.INFO), WARNING(IconName.WARNING), ERROR(IconName.ERROR);

        private final IconName icon;

        Kind(IconName icon) {
            this.icon = icon;
        }

        String styleClass() {
            return "sv-banner-" + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param kind    種類
     * @param message 訊息（「發生什麼＋可以怎麼做」）
     */
    public Banner(Kind kind, String message) {
        this(kind, message, null, null);
    }

    /**
     * @param kind       種類
     * @param message    訊息
     * @param actionText 動作按鈕文字（動詞）；沒有動作時 {@code null}
     * @param action     動作
     */
    public Banner(Kind kind, String message, String actionText, Runnable action) {
        getStyleClass().addAll("sv-banner", kind.styleClass());
        Label text = new Label(message);
        text.getStyleClass().add("sv-banner-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(Icons.of(kind.icon), text);
        if (actionText != null && action != null) {
            SvButton button = SvButton.secondary(actionText);
            button.getStyleClass().add("sv-banner-action");
            // 空間不夠時讓訊息換行，按鈕文字（動詞）不截斷
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(e -> action.run());
            getChildren().add(button);
        }
    }
}
