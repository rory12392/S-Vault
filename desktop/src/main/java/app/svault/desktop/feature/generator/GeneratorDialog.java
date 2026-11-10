package app.svault.desktop.feature.generator;

import app.svault.core.feature.generator.GeneratorModel;
import app.svault.desktop.ui.ThemeManager;
import app.svault.desktop.ui.UiText;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * 編輯畫面密碼欄旁「產生」的彈出面板：產生器內容＋「使用這個密碼」（primary、Enter）／「取消」（Esc）。
 * 用 JavaFX 內建的模態 {@link Dialog}，關閉後焦點回到「產生」按鈕；選項不合法時「使用這個密碼」不可按。
 */
final class GeneratorDialog {

    private GeneratorDialog() {
    }

    /**
     * 顯示並等待使用者選擇（在 JavaFX 執行緒，由 {@code PasswordFieldAction} 呼叫）。
     *
     * @param model 狀態；關閉時清掉結果
     * @param text  模組的文字
     * @return 按「使用這個密碼」時是結果的副本（外殼填進密碼欄後清零）；取消時為空
     */
    static Optional<char[]> show(GeneratorModel model, ResourceBundle text) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(text.getString("dialog.title"));
        dialog.initModality(Modality.WINDOW_MODAL);
        // 擁有者是觸發「產生」的視窗：外殼的擴充點沒有傳元件進來，以有焦點、套著主題的視窗為準；
        // 程式不在前景時（例如畫面腳本）沒有視窗有焦點，退回任何一個套著主題的視窗
        Window owner = Window.getWindows().stream().filter(GeneratorDialog::themed).filter(Window::isFocused)
                .findFirst()
                .orElseGet(() -> Window.getWindows().stream().filter(GeneratorDialog::themed).findFirst().orElse(null));
        if (owner != null) {
            dialog.initOwner(owner);
            ThemeManager theme = ThemeManager.of(owner.getScene());
            if (theme != null) {
                theme.track(dialog.getDialogPane());
            }
        }
        GeneratorPanel panel = new GeneratorPanel(model, text);
        Label heading = new Label(text.getString("dialog.title"));
        heading.getStyleClass().add("sv-dialog-title");
        VBox content = new VBox(heading, panel);
        content.getStyleClass().add("sv-dialog-content");
        dialog.getDialogPane().getStyleClass().addAll("sv-dialog", "sv-gen-dialog");
        dialog.getDialogPane().getStylesheets().addAll(panel.getStylesheets());
        dialog.getDialogPane().setContent(content);

        ButtonType use = new ButtonType(text.getString("dialog.use"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(UiText.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancel, use);
        Button useButton = (Button) dialog.getDialogPane().lookupButton(use);
        useButton.setId("gen-use");
        useButton.getStyleClass().addAll("sv-button", "sv-button-primary");
        dialog.getDialogPane().lookupButton(cancel).getStyleClass().addAll("sv-button", "sv-button-secondary");
        useButton.setDisable(model.invalid());
        model.addListener(() -> useButton.setDisable(model.invalid()));
        // 開啟時焦點在選項的第一個控制項
        dialog.setOnShown(e -> Platform.runLater(() -> panel.firstControl().requestFocus()));
        // Enter 一律是「使用這個密碼」（DESIGN §3 預設按鈕）：核取方塊與切換鈕預設會把 Enter 當成切換，先攔下來
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            Node focused = dialog.getDialogPane().getScene().getFocusOwner();
            if (e.getCode() == KeyCode.ENTER && (focused instanceof CheckBox || focused instanceof ToggleButton)) {
                e.consume();
                if (!useButton.isDisabled()) {
                    useButton.fire();
                }
            }
        });

        try {
            Optional<ButtonType> chosen = dialog.showAndWait();
            return chosen.isPresent() && chosen.get() == use ? model.take() : Optional.empty();
        } finally {
            model.clear();
        }
    }

    private static boolean themed(Window w) {
        return w.isShowing() && ThemeManager.of(w.getScene()) != null;
    }
}
