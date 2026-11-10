package app.svault.desktop.shell.start;

import app.svault.core.shell.Texts;
import app.svault.core.storage.BackupStore;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.ThemeManager;
import java.text.MessageFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 備份還原（AC-09-2）：備份時間清單（新到舊，最多 10 列）→
 * 選一列 →「還原這個版本」（danger）→ ConfirmDialog「還原到 … 的版本？」。沒有備份時 EmptyState（無行動）。
 *
 * <p>只負責選擇與確認；真正的還原（背景執行、進度、結果）在 {@link RestoreFlow}。
 * 鍵盤：方向鍵選取、Enter 或「還原這個版本」開確認、Esc 關閉。
 */
final class BackupDialog {

    /** 清單與確認對話框的時間格式；備份檔名到秒，同一分鐘內常有好幾份，所以顯示到秒。 */
    static final DateTimeFormatter TIME = Texts.SECONDS;

    private static final double ROW_HEIGHT = 48;
    private static final double LIST_WIDTH = 360;
    private static final int VISIBLE_ROWS = 6;

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ResourceBundle text;
    private final ListView<BackupStore.Backup> list = new ListView<>();
    private final ButtonType restoreType;
    private BackupStore.Backup confirmed;

    /**
     * @param owner   觸發的元件（決定擁有者視窗與主題）
     * @param text    文字
     * @param backups 備份，最新的在前
     */
    BackupDialog(Node owner, ResourceBundle text, List<BackupStore.Backup> backups) {
        this.text = text;
        String title = text.getString("restore.title");
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
        VBox content = new VBox(heading);
        content.getStyleClass().add("sv-dialog-content");
        dialog.getDialogPane().getStyleClass().add("sv-dialog");
        dialog.getDialogPane().setContent(content);

        restoreType = new ButtonType(text.getString("restore.submit"), ButtonBar.ButtonData.OK_DONE);
        ButtonType closeType = new ButtonType(text.getString("restore.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button close = (Button) dialog.getDialogPane().lookupButton(closeType);
        close.getStyleClass().addAll("sv-button", "sv-button-secondary");

        if (backups.isEmpty()) {
            EmptyState empty = new EmptyState(IconName.LOCK, text.getString("restore.empty.title"),
                    text.getString("restore.empty.description"), null, null);
            empty.setId("restore-empty");
            content.getChildren().add(empty);
            close.setDefaultButton(true);
            dialog.setOnShown(e -> Platform.runLater(close::requestFocus));
            return;
        }

        Label hint = new Label(text.getString("restore.hint"));
        hint.getStyleClass().add("sv-dialog-body");
        hint.setWrapText(true);
        list.setId("restore-list");
        list.setAccessibleText(text.getString("restore.list.accessible"));
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefWidth(LIST_WIDTH);
        list.setPrefHeight(Math.min(backups.size(), VISIBLE_ROWS) * ROW_HEIGHT + 4);
        list.setCellFactory(v -> new BackupCell());
        list.getItems().setAll(backups);
        content.getChildren().addAll(hint, list);

        dialog.getDialogPane().getButtonTypes().add(restoreType);
        Button restore = (Button) dialog.getDialogPane().lookupButton(restoreType);
        restore.setId("restore-submit");
        restore.getStyleClass().addAll("sv-button", "sv-button-danger");
        // 危險操作不當預設按鈕；Enter 在清單上由下面的按鍵處理接手
        restore.setDefaultButton(false);
        restore.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        // 按下時先確認；取消確認就留在清單上
        restore.addEventFilter(ActionEvent.ACTION, e -> {
            if (!confirm()) {
                e.consume();
            }
        });
        // ListView 自己吃掉 Esc（取消編輯），對話框就關不掉；先在清單上接住（DESIGN §3：對話框 Esc 關閉）
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                dialog.setResult(closeType);
                dialog.close();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && list.getSelectionModel().getSelectedItem() != null) {
                e.consume();
                if (confirm()) {
                    dialog.setResult(restoreType);
                    dialog.close();
                }
            }
        });
        dialog.setOnShown(e -> Platform.runLater(() -> {
            list.getSelectionModel().selectFirst();
            list.requestFocus();
        }));
    }

    /**
     * 顯示並等待使用者選擇。
     *
     * @return 確認要還原的備份；關閉、Esc 或取消確認時為空
     */
    Optional<BackupStore.Backup> showAndWait() {
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == restoreType ? Optional.ofNullable(confirmed) : Optional.empty();
    }

    /** @return 內部的 JavaFX 對話框（開發版的畫面腳本以非阻塞方式顯示） */
    Dialog<ButtonType> dialog() {
        return dialog;
    }

    private boolean confirm() {
        BackupStore.Backup chosen = list.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            return false;
        }
        ConfirmDialog ask = new ConfirmDialog(list,
                MessageFormat.format(text.getString("restore.confirm.title"), chosen.time().format(TIME)),
                text.getString("restore.confirm.body"), text.getString("restore.confirm.action"),
                ConfirmDialog.Variant.DANGER);
        if (ask.showAndWait()) {
            confirmed = chosen;
            return true;
        }
        list.requestFocus();
        return false;
    }

    private final class BackupCell extends ListCell<BackupStore.Backup> {

        BackupCell() {
            getStyleClass().add("sv-backup-cell");
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null
                        && confirm()) {
                    dialog.setResult(restoreType);
                    dialog.close();
                }
            });
        }

        @Override
        protected void updateItem(BackupStore.Backup item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setAccessibleText(null);
                return;
            }
            String time = item.time().format(TIME);
            boolean latest = getIndex() == 0;
            setText(latest ? MessageFormat.format(text.getString("restore.item.latest"), time) : time);
            setAccessibleText(getText());
        }
    }
}
