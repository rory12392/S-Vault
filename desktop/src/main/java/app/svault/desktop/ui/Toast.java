package app.svault.desktop.ui;

import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Toast：主視窗右下的覆蓋層，不搶焦點、有「關閉」按鈕；預設 3 秒後消失，
 * 剪貼簿提示用 {@link #showUntilClosed} 顯示到呼叫端關閉為止。直接出現與消失，不加動畫。
 *
 * <p>{@link Host} 放在 AppShell 的最上層；{@code pickOnBounds=false}，空白處的滑鼠事件會傳到下面的畫面。
 */
public final class Toast extends HBox {

    /** 種類。 */
    public enum Kind {
        INFO(IconName.INFO), SUCCESS(IconName.CHECK_CIRCLE), ERROR(IconName.ERROR);

        private final IconName icon;

        Kind(IconName icon) {
            this.icon = icon;
        }

        String styleClass() {
            return "sv-toast-" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** 自動消失的時間。 */
    public static final Duration DEFAULT_DURATION = Duration.seconds(3);

    private Toast(Kind kind, String message, Runnable onClose) {
        getStyleClass().addAll("sv-toast", kind.styleClass());
        Label text = new Label(message);
        text.getStyleClass().add("sv-toast-text");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        IconButton close = new IconButton(IconName.CLOSE, UiText.get("toast.close"));
        close.getStyleClass().add("sv-icon-button-small");
        close.setOnAction(e -> onClose.run());
        // 螢幕閱讀器把它當成狀態訊息讀出
        setAccessibleRole(AccessibleRole.TEXT);
        setAccessibleText(message);
        getChildren().addAll(Icons.of(kind.icon), text, close);
    }

    /** 放 Toast 的覆蓋層。 */
    public static final class Host extends StackPane {

        private final VBox stack = new VBox();

        /** 建立空的覆蓋層。 */
        public Host() {
            getStyleClass().add("sv-toast-host");
            setPickOnBounds(false);
            stack.getStyleClass().add("sv-toast-stack");
            stack.setPickOnBounds(false);
            stack.setAlignment(Pos.BOTTOM_RIGHT);
            StackPane.setAlignment(stack, Pos.BOTTOM_RIGHT);
            stack.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
            getChildren().add(stack);
        }

        /**
         * 顯示並在 3 秒後自動關閉。
         *
         * @param kind    種類
         * @param message 訊息
         */
        public void show(Kind kind, String message) {
            Runnable close = showUntilClosed(kind, message);
            PauseTransition timer = new PauseTransition(DEFAULT_DURATION);
            timer.setOnFinished(e -> close.run());
            timer.play();
        }

        /**
         * 顯示到呼叫端關閉為止（剪貼簿倒數）。
         *
         * @param kind    種類
         * @param message 訊息
         * @return 關閉這則 Toast 的動作（可重複呼叫）
         */
        public Runnable showUntilClosed(Kind kind, String message) {
            Toast[] holder = new Toast[1];
            Runnable close = () -> stack.getChildren().remove(holder[0]);
            holder[0] = new Toast(kind, message, close);
            stack.getChildren().add(holder[0]);
            return close;
        }

        /** @return 目前顯示中的則數（測試與展示頁用） */
        public int count() {
            return stack.getChildren().size();
        }
    }
}
