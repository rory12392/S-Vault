package app.svault.desktop.ui;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 設定的一列（SettingsRow）：標題＋說明＋原生控制項（開關、選項、數字）或導覽按鈕。
 * 標題以 {@link Label#setLabelFor} 連到控制項。
 */
public final class SettingsRow extends HBox {

    private final Node control;
    private final Label body = new Label();

    /**
     * @param title       標題
     * @param description 說明；沒有時 {@code null}
     * @param control     控制項
     */
    public SettingsRow(String title, String description, Node control) {
        this.control = control;
        getStyleClass().add("sv-settings-row");
        Label heading = new Label(title);
        heading.getStyleClass().add("sv-settings-title");
        heading.setWrapText(true);
        body.getStyleClass().add("sv-settings-description");
        body.setWrapText(true);
        VBox texts = new VBox(heading, body);
        texts.getStyleClass().add("sv-settings-texts");
        setDescription(description);
        HBox.setHgrow(texts, Priority.ALWAYS);
        heading.setLabelFor(control);
        if (control instanceof Control c && c.getAccessibleText() == null) {
            c.setAccessibleText(title);
        }
        getChildren().addAll(texts, control);
    }

    /** @param title 標題 @param description 說明 @return 開關列（{@link CheckBox}） */
    public static SettingsRow toggle(String title, String description) {
        return new SettingsRow(title, description, new CheckBox());
    }

    /**
     * @param <T>         選項型別
     * @param title       標題
     * @param description 說明
     * @param options     選項
     * @return 選項列（{@link ComboBox}）
     */
    public static <T> SettingsRow choice(String title, String description, List<T> options) {
        ComboBox<T> box = new ComboBox<>();
        box.getItems().addAll(options);
        if (!options.isEmpty()) {
            box.getSelectionModel().selectFirst();
        }
        return new SettingsRow(title, description, box);
    }

    /**
     * @param title       標題
     * @param description 說明
     * @param min         最小值
     * @param max         最大值
     * @param value       目前值
     * @return 數字列（{@link Spinner}，可直接輸入）
     */
    public static SettingsRow number(String title, String description, int min, int max, int value) {
        Spinner<Integer> spinner = new Spinner<>(min, max, value);
        spinner.setEditable(true);
        spinner.getStyleClass().add("sv-settings-number");
        return new SettingsRow(title, description, spinner);
    }

    /**
     * @param title       標題
     * @param description 說明
     * @param onOpen      開啟子頁面
     * @return 導覽列（右側箭頭按鈕）
     */
    public static SettingsRow navigation(String title, String description, Runnable onOpen) {
        IconButton open = new IconButton(IconName.CHEVRON_RIGHT, title);
        open.setOnAction(e -> onOpen.run());
        return new SettingsRow(title, description, open);
    }

    /** @return 控制項 */
    public Node control() {
        return control;
    }

    /** @param description 新的說明；{@code null} 時隱藏說明（例：「功能模組」的開關改了之後的提示，T-38） */
    public void setDescription(String description) {
        body.setText(description == null ? "" : description);
        body.setVisible(description != null);
        body.setManaged(description != null);
    }

    /** @return 顯示中的說明；隱藏時 {@code null} */
    public String description() {
        return body.isManaged() ? body.getText() : null;
    }
}
