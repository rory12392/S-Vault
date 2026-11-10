package app.svault.desktop.ui;

import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * 表單欄位：可見標籤＋輸入元件＋錯誤文字（TextField）。
 * 標籤以 {@link Label#setLabelFor} 連到輸入元件；錯誤時外框與文字改用 danger，並把錯誤文字設成輸入元件的說明，
 * 螢幕閱讀器讀得到。
 */
public final class FormField extends VBox {

    private final Label label;
    private final Label error = new Label();
    private final Control input;
    private final Node body;

    /**
     * @param labelText 標籤（一律可見，不只用 placeholder）
     * @param input     輸入元件
     * @param body      實際放進版面的節點（通常就是 {@code input}；PasswordInput 時是整個元件）
     */
    public FormField(String labelText, Control input, Node body) {
        this.input = input;
        this.body = body;
        label = new Label(labelText);
        label.getStyleClass().add("sv-field-label");
        label.setLabelFor(input);
        error.getStyleClass().add("sv-field-error");
        error.setWrapText(true);
        showError(false);
        getStyleClass().add("sv-form-field");
        getChildren().addAll(label, body, error);
    }

    /**
     * @param labelText 標籤
     * @return 單行文字欄位
     */
    public static FormField text(String labelText) {
        TextField field = new TextField();
        return new FormField(labelText, field, field);
    }

    /**
     * @param message 錯誤訊息（「發生什麼＋可以怎麼做」）；{@code null} 清除錯誤
     */
    public void setError(String message) {
        boolean hasError = message != null && !message.isBlank();
        error.setText(hasError ? message : "");
        input.setAccessibleHelp(hasError ? message : null);
        markError(input, hasError);
        if (body != input) {
            // PasswordInput：明文欄位顯示時也要有錯誤外框
            markError(body, hasError);
        }
        showError(hasError);
    }

    /** @return 輸入元件 */
    public Control input() {
        return input;
    }

    /**
     * @param labelText 標籤
     * @param password  密碼欄
     * @return 密碼欄位（標籤連到遮罩欄位）
     */
    public static FormField password(String labelText, PasswordInput password) {
        return new FormField(labelText, password.maskedField(), password);
    }

    /** @return 單行文字欄位（{@link #text} 建立時） */
    public TextField textField() {
        return (TextField) input;
    }

    private static void markError(Node node, boolean hasError) {
        node.getStyleClass().remove("sv-error");
        if (hasError) {
            node.getStyleClass().add("sv-error");
        }
    }

    private void showError(boolean visible) {
        error.setVisible(visible);
        error.setManaged(visible);
    }
}
