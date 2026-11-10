package app.svault.desktop.shell.entries;

import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.ThemeManager;
import app.svault.desktop.ui.UiText;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 新增與重新命名群組時輸入名稱的對話框（Dialog：JavaFX 內建 {@link Dialog}，模態、Esc 關閉，
 * 樣式與 {@code ConfirmDialog} 相同）。名稱空白時不關閉，欄位下方顯示錯誤。
 */
final class NameDialog {

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final FormField field;
    private final ButtonType confirmType;

    /**
     * @param owner       觸發的元件（決定擁有者視窗與主題）
     * @param text        畫面文字
     * @param title       標題
     * @param initial     預設名稱
     * @param confirmText 確認鈕文字（動詞）
     */
    NameDialog(Node owner, ResourceBundle text, String title, String initial, String confirmText) {
        dialog.setTitle(title);
        dialog.initModality(Modality.WINDOW_MODAL);
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            ThemeManager theme = ThemeManager.of(owner.getScene());
            if (theme != null) {
                theme.track(dialog.getDialogPane());
            }
        }
        Label heading = new Label(title);
        heading.getStyleClass().add("sv-dialog-title");
        field = FormField.text(text.getString("name.label"));
        field.textField().setId("group-name");
        field.textField().setText(initial);
        field.textField().textProperty().addListener((obs, old, now) -> field.setError(null));
        VBox content = new VBox(heading, field);
        content.getStyleClass().add("sv-dialog-content");
        dialog.getDialogPane().getStyleClass().add("sv-dialog");
        dialog.getDialogPane().setContent(content);

        confirmType = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(UiText.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelType, confirmType);
        Button confirm = (Button) dialog.getDialogPane().lookupButton(confirmType);
        Button cancel = (Button) dialog.getDialogPane().lookupButton(cancelType);
        confirm.getStyleClass().addAll("sv-button", "sv-button-primary");
        cancel.getStyleClass().addAll("sv-button", "sv-button-secondary");
        String emptyError = text.getString("name.error");
        confirm.addEventFilter(ActionEvent.ACTION, e -> {
            if (name().isEmpty()) {
                field.setError(emptyError);
                field.textField().requestFocus();
                e.consume();
            }
        });
        dialog.setOnShown(e -> Platform.runLater(() -> {
            field.textField().requestFocus();
            field.textField().selectAll();
        }));
    }

    /**
     * @return 輸入的名稱（去掉前後空白）；取消時為空
     */
    Optional<String> showAndWait() {
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == confirmType ? Optional.of(name()) : Optional.empty();
    }

    private String name() {
        return field.textField().getText() == null ? "" : field.textField().getText().strip();
    }
}
