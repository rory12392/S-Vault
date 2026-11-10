package app.svault.desktop.shell.start;

import app.svault.core.storage.BackupStore;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.PasswordInput;
import app.svault.desktop.ui.SvButton;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 解鎖（US-01）：保管庫名稱＋路徑、主密碼欄（自動聚焦）、「解鎖」（Enter）、「開啟其他檔案」。
 * 主密碼錯誤時清空欄位並重新聚焦（AC-01-3）；不支援的加密設定與檔案問題以 error Banner 顯示，
 * 欄位與按鈕保留，改好後可以直接重試（AC-01-7）。
 */
final class UnlockScreen extends VBox {

    /** 畫面上的動作（由 {@link StartFlow} 實作）。 */
    interface Actions {
        void unlock();

        void openOther();

        void chooseAgain();

        /** 「從備份還原」「選擇其他版本」（AC-09-5）。 */
        void restoreBackup();
    }

    private final ResourceBundle text;
    private final Actions actions;
    private final Path file;
    private final VBox problemSlot = new VBox();
    private final PasswordInput password = new PasswordInput(false);
    private final FormField passwordField;
    private final SvButton submit;
    private final SvButton other;
    private final Label busyText = new Label();
    /** 選定要還原的備份（還原模式）；一般解鎖時 {@code null}。 */
    private BackupStore.Backup restoreTarget;

    /**
     * @param text    文字
     * @param file    要解鎖的保管庫
     * @param actions 畫面上的動作
     */
    UnlockScreen(ResourceBundle text, Path file, Actions actions) {
        this.text = text;
        this.actions = actions;
        this.file = file;
        getStyleClass().add("sv-form");

        Label title = new Label(RecentItem.displayName(file));
        title.getStyleClass().add("sv-page-title");
        title.setWrapText(true);
        Label path = new Label(file.toString());
        path.getStyleClass().add("sv-caption");
        path.setWrapText(true);
        VBox heading = new VBox(title, path);
        heading.getStyleClass().add("sv-form-field");
        // 沒有訊息時不佔位置（否則表單多一段間距）
        problemSlot.managedProperty().bind(Bindings.isNotEmpty(problemSlot.getChildren()));

        password.maskedField().setId("unlock-password");
        passwordField = FormField.password(text.getString("unlock.password"), password);
        // 重新輸入時收掉「主密碼錯誤」（showFieldError 先清空欄位再顯示錯誤，不會被這裡清掉）
        password.maskedField().textProperty().addListener((obs, old, now) -> {
            if (!now.isEmpty()) {
                passwordField.setError(null);
            }
        });

        submit = SvButton.primary(text.getString("unlock.submit"));
        submit.setId("unlock-submit");
        submit.setDefaultButton(true);
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(e -> actions.unlock());
        busyText.setText(text.getString("unlock.unlocking"));
        busyText.getStyleClass().add("sv-caption");
        showBusyText(false);
        other = SvButton.ghost(text.getString("unlock.other"));
        other.setId("unlock-other");
        other.setOnAction(e -> actions.openOther());

        getChildren().addAll(heading, problemSlot, passwordField, submit, busyText, other);
    }

    /** @return 要解鎖的保管庫 */
    Path file() {
        return file;
    }

    /** @return 主密碼的複本；呼叫端用完清零 */
    char[] password() {
        return password.password();
    }

    /**
     * 解鎖中：按鈕內 Spinner＋停用，下方「正在解鎖…」。
     *
     * @param busy 是否解鎖中
     */
    void setBusy(boolean busy) {
        submit.setLoading(busy);
        passwordField.setDisable(busy);
        other.setDisable(busy);
        showBusyText(busy);
    }

    /**
     * 欄位下方的錯誤（AC-01-3：「主密碼錯誤」）：清空欄位並重新聚焦。
     *
     * @param messageKey 文字鍵
     */
    void showFieldError(String messageKey) {
        problemSlot.getChildren().clear();
        password.clear();
        passwordField.setError(text.getString(messageKey));
        focusPassword();
    }

    /**
     * error Banner（AC-01-7、AC-06-4、檔案損壞）；欄位與按鈕保留。
     *
     * @param problem 呈現方式
     */
    void showBanner(OpenProblem problem) {
        passwordField.setError(null);
        String message = text.getString(problem.messageKey());
        Banner banner;
        if (problem.restoreBackup()) {
            banner = new Banner(Banner.Kind.ERROR, message, text.getString("open.restore"), actions::restoreBackup);
        } else if (problem.chooseAgain()) {
            banner = new Banner(Banner.Kind.ERROR, message, text.getString("open.chooseAgain"), actions::chooseAgain);
        } else {
            banner = new Banner(Banner.Kind.ERROR, message);
        }
        banner.setId("unlock-problem");
        problemSlot.getChildren().setAll(banner);
        focusPassword();
    }

    /**
     * 還原模式（AC-09-5）：info Banner「將還原到 … 的版本，輸入主密碼後還原」＋「選擇其他版本」，按鈕改成「還原並解鎖」。
     *
     * @param backup 選定的備份
     * @param time   顯示用的備份時間
     */
    void showRestore(BackupStore.Backup backup, String time) {
        restoreTarget = backup;
        passwordField.setError(null);
        Banner banner = new Banner(Banner.Kind.INFO,
                MessageFormat.format(text.getString("restore.pending"), time),
                text.getString("restore.other"), actions::restoreBackup);
        banner.setId("unlock-restore");
        problemSlot.getChildren().setAll(banner);
        submit.setText(text.getString("restore.unlock"));
        focusPassword();
    }

    /**
     * 還原失敗（備份不能用、寫不進去）：離開還原模式，error Banner＋「選擇其他版本」。
     *
     * @param messageKey 文字鍵
     */
    void showRestoreProblem(String messageKey) {
        restoreTarget = null;
        submit.setText(text.getString("unlock.submit"));
        Banner banner = new Banner(Banner.Kind.ERROR, text.getString(messageKey), text.getString("restore.other"),
                actions::restoreBackup);
        banner.setId("unlock-problem");
        problemSlot.getChildren().setAll(banner);
        focusPassword();
    }

    /** @return 選定要還原的備份；一般解鎖時 {@code null} */
    BackupStore.Backup restoreTarget() {
        return restoreTarget;
    }

    /** 解鎖成功或離開畫面時清掉欄位。 */
    void clearPassword() {
        password.clear();
    }

    /** 把焦點放到主密碼欄。 */
    void focusPassword() {
        password.maskedField().requestFocus();
    }

    private void showBusyText(boolean visible) {
        busyText.setVisible(visible);
        busyText.setManaged(visible);
    }
}
