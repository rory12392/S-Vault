package app.svault.desktop.ui;

import java.util.Optional;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 對話框與確認對話框（Dialog／ConfirmDialog、破壞性操作）。
 *
 * <p>用 JavaFX 內建的 {@link Dialog}（模態、Esc 關閉、關閉後焦點回到原視窗的焦點元件）。
 * 標題寫動作（「刪除這筆條目？」），內文寫後果，確認鈕寫動詞。危險操作的確認鈕是 danger，預設焦點與 Enter 都在「取消」，
 * 避免連按 Enter 誤刪。
 */
public final class ConfirmDialog {

    /** 確認鈕的變體。 */
    public enum Variant { DEFAULT, DANGER }

    private static final PseudoClass LOADING = PseudoClass.getPseudoClass("loading");

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ButtonType confirmType;
    private final ButtonType cancelType;

    /**
     * @param owner       觸發的元件（決定擁有者視窗與主題）
     * @param title       標題（動作，問句）
     * @param body        內文（後果）
     * @param confirmText 確認鈕文字（動詞）
     * @param variant     確認鈕變體
     */
    public ConfirmDialog(Node owner, String title, String body, String confirmText, Variant variant) {
        this(owner, title, body, confirmText, UiText.get("dialog.cancel"), variant);
    }

    /**
     * 取消鈕不是「取消」時（例：「捨棄修改？」的「繼續編輯」）。
     *
     * @param owner       觸發的元件（決定擁有者視窗與主題）
     * @param title       標題（動作，問句）
     * @param body        內文（後果）
     * @param confirmText 確認鈕文字（動詞）
     * @param cancelText  取消鈕文字
     * @param variant     確認鈕變體
     */
    public ConfirmDialog(Node owner, String title, String body, String confirmText, String cancelText,
            Variant variant) {
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
        Label text = new Label(body);
        text.getStyleClass().add("sv-dialog-body");
        text.setWrapText(true);
        // 對話框的高度以單行計算時，長內文會被截成一行「…」（T-38 的停用確認）：高度至少是換行後的高度
        text.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(heading, text);
        content.getStyleClass().add("sv-dialog-content");
        dialog.getDialogPane().getStyleClass().add("sv-dialog");
        dialog.getDialogPane().setContent(content);

        confirmType = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        cancelType = new ButtonType(cancelText, ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelType, confirmType);
        Button confirm = confirmButton();
        Button cancel = (Button) dialog.getDialogPane().lookupButton(cancelType);
        confirm.getStyleClass().addAll("sv-button",
                variant == Variant.DANGER ? "sv-button-danger" : "sv-button-primary");
        cancel.getStyleClass().addAll("sv-button", "sv-button-secondary");
        if (variant == Variant.DANGER) {
            confirm.setDefaultButton(false);
            cancel.setDefaultButton(true);
            dialog.setOnShown(e -> Platform.runLater(cancel::requestFocus));
        }
    }

    /**
     * 顯示並等待使用者選擇。
     *
     * @return 按了確認鈕時 {@code true}；取消、Esc、關閉視窗時 {@code false}
     */
    public boolean showAndWait() {
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == confirmType;
    }

    /**
     * 送出中：確認鈕 loading，取消鈕停用。
     *
     * @param submitting 是否送出中
     */
    public void setSubmitting(boolean submitting) {
        confirmButton().setDisable(submitting);
        dialog.getDialogPane().lookupButton(cancelType).setDisable(submitting);
        confirmButton().pseudoClassStateChanged(LOADING, submitting);
        confirmButton().setGraphic(submitting ? spinner() : null);
    }

    /** @return 確認鈕（例如要在送出時攔截 ACTION 事件） */
    public Button confirmButton() {
        return (Button) dialog.getDialogPane().lookupButton(confirmType);
    }

    /** @return 內部的 JavaFX 對話框（展示頁以非阻塞方式顯示） */
    public Dialog<ButtonType> dialog() {
        return dialog;
    }

    private static Node spinner() {
        ProgressIndicator p = new ProgressIndicator();
        p.getStyleClass().add("sv-button-spinner");
        return p;
    }
}
