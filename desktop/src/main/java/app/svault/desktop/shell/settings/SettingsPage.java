package app.svault.desktop.shell.settings;

import app.svault.core.storage.IntSetting;
import app.svault.core.storage.Settings;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.PageHeader;
import app.svault.desktop.ui.SettingsRow;
import app.svault.desktop.ui.SvButton;
import app.svault.desktop.ui.ThemeMode;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.IntConsumer;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * 設定畫面：外觀、安全、備份、各模組的段落、關於。
 * 每個控制項改了就經 {@link SettingsSaver} 生效並寫檔，沒有「儲存」按鈕。Esc 或「返回」回到條目清單。
 */
final class SettingsPage extends VBox {

    /** 開啟子畫面與關閉頁面的動作。 */
    interface Actions {
        /** 回到條目清單與詳細。 */
        void close();

        /** 開備份還原（同工具列選單）。 */
        void openBackups();

        /** 開關於對話框。 */
        void openAbout();
    }

    private final ResourceBundle text;
    private final SettingsSaver saver;
    private final ComboBox<ThemeMode> theme = new ComboBox<>();

    /**
     * @param text     文字
     * @param saver    設定的變更
     * @param sections 模組段落（{@link ModuleSections#build}）
     * @param toggles  「功能模組」區段的列（{@link ModuleToggleRows#build}，T-38）；沒有模組時是空陣列
     * @param actions  子畫面與關閉
     */
    SettingsPage(ResourceBundle text, SettingsSaver saver, List<ModuleSections.Section> sections, Node[] toggles,
            Actions actions) {
        this.text = text;
        this.saver = saver;
        getStyleClass().add("sv-settings-page");
        setId("settings-page");
        Settings now = saver.current();

        SvButton close = SvButton.ghost(text.getString("settings.close"));
        close.setGraphic(Icons.of(IconName.CHEVRON_LEFT));
        close.setId("settings-close");
        close.setOnAction(e -> actions.close());
        PageHeader header = new PageHeader(text.getString("settings.title"), close);

        VBox body = new VBox();
        body.getStyleClass().add("sv-settings-body");
        body.getChildren().addAll(
                section(text.getString("settings.section.appearance"), themeRow(now)),
                section(text.getString("settings.section.security"), securityRows(now)),
                section(text.getString("settings.section.backup"), SettingsRow.navigation(
                        text.getString("settings.backup"),
                        text.getString("settings.backup.description"), actions::openBackups)));
        for (ModuleSections.Section s : sections) {
            Node content = s.content().orElseGet(() -> {
                Label error = new Label(text.getString("settings.module.error"));
                error.getStyleClass().add("sv-settings-error");
                error.setWrapText(true);
                return error;
            });
            body.getChildren().add(section(s.title(), content));
        }
        if (toggles.length > 0) {
            body.getChildren().add(section(text.getString("settings.section.modules"), toggles));
        }
        body.getChildren().add(section(text.getString("settings.section.about"), SettingsRow.navigation(
                text.getString("settings.about"), text.getString("settings.about.description"),
                actions::openAbout)));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sv-settings-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);
        // 用 Tab 移到捲動區外的控制項時捲過去（ScrollPane 不會跟著焦點捲；T-38 的「功能模組」在最下面）
        ChangeListener<Node> follow = (obs, was, owner) -> reveal(scroll, body, owner);
        sceneProperty().addListener((obs, old, scene) -> {
            if (old != null) {
                old.focusOwnerProperty().removeListener(follow);
            }
            if (scene != null) {
                scene.focusOwnerProperty().addListener(follow);
            }
        });

        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                e.consume();
                actions.close();
            }
        });
    }

    /** 開啟時的焦點：第一個控制項（主題）。 */
    void focusDefault() {
        theme.requestFocus();
    }

    private Node themeRow(Settings now) {
        theme.getItems().setAll(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM);
        theme.setConverter(new StringConverter<>() {
            @Override
            public String toString(ThemeMode mode) {
                return mode == null ? "" : text.getString("settings.theme." + mode.settingValue());
            }

            @Override
            public ThemeMode fromString(String s) {
                return ThemeMode.SYSTEM;
            }
        });
        theme.setValue(ThemeMode.fromSetting(now.theme()));
        theme.setId("settings-theme");
        theme.valueProperty().addListener((obs, old, mode) -> {
            if (mode != null) {
                saver.change(s -> s.withTheme(mode.settingValue()));
            }
        });
        return new SettingsRow(text.getString("settings.theme"), text.getString("settings.theme.description"),
                theme);
    }

    private Node[] securityRows(Settings now) {
        int minutes = now.autoLockDesktopMinutes();
        boolean on = minutes != Settings.AUTO_LOCK_OFF;
        IntSetting range = Settings.AUTO_LOCK_DESKTOP_MINUTES;

        SettingsRow toggle = SettingsRow.toggle(text.getString("settings.autoLock"),
                text.getString("settings.autoLock.description"));
        CheckBox enabled = (CheckBox) toggle.control();
        enabled.setId("settings-autolock");
        enabled.setSelected(on);

        // 不自動上鎖時分鐘數停用；重新打開時用上次的分鐘數（沒有時用預設值）
        Spinner<Integer> minuteSpinner = spinner(Math.max(1, range.min()), range.max(),
                on ? minutes : range.defaultValue(), "settings-autolock-minutes",
                value -> saver.change(s -> range.write(s, value)));
        minuteSpinner.setDisable(!on);
        SettingsRow minuteRow = new SettingsRow(text.getString("settings.autoLockMinutes"),
                text.getString("settings.autoLockMinutes.description"), minuteSpinner);
        enabled.selectedProperty().addListener((obs, old, selected) -> {
            minuteSpinner.setDisable(!selected);
            int value = selected ? minuteSpinner.getValue() : Settings.AUTO_LOCK_OFF;
            saver.change(s -> range.write(s, value));
        });

        IntSetting clip = Settings.CLIPBOARD_CLEAR_SECONDS;
        Spinner<Integer> clipSpinner = spinner(clip.min(), clip.max(), now.clipboardClearSeconds(),
                "settings-clipboard", value -> saver.change(s -> clip.write(s, value)));
        SettingsRow clipRow = new SettingsRow(text.getString("settings.clipboard"),
                text.getString("settings.clipboard.description"), clipSpinner);

        // I-1（D-189）：解鎖時阻擋截圖，預設開；切換後立即套用到目前的視窗（ScreenCaptureGuard 監聽設定）
        SettingsRow capture = SettingsRow.toggle(text.getString("settings.blockCapture"),
                text.getString("settings.blockCapture.description"));
        CheckBox block = (CheckBox) capture.control();
        block.setId("settings-block-capture");
        block.setSelected(now.blockScreenCapture());
        block.selectedProperty().addListener((obs, old, selected) ->
                saver.change(s -> s.with(Settings.BLOCK_SCREEN_CAPTURE, selected)));
        return new Node[] {toggle, minuteRow, clipRow, capture};
    }

    /**
     * 可直接輸入的數字欄。輸入的文字在 Enter 或離開欄位時套用；不是數字時改回原值，超出範圍時取最接近的邊界。
     */
    private static Spinner<Integer> spinner(int min, int max, int value, String id, IntConsumer onChange) {
        Spinner<Integer> spinner = new Spinner<>(min, max, value);
        spinner.setEditable(true);
        spinner.setId(id);
        spinner.getStyleClass().add("sv-settings-number");
        spinner.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) {
                commit(spinner);
            }
        });
        spinner.valueProperty().addListener((obs, old, now) -> {
            if (now != null && !now.equals(old)) {
                onChange.accept(now);
            }
        });
        return spinner;
    }

    private static void commit(Spinner<Integer> spinner) {
        SpinnerValueFactory<Integer> factory = spinner.getValueFactory();
        String typed = spinner.getEditor().getText();
        Integer parsed = SpinnerInput.parse(typed, spinner.getValue(),
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) factory).getMin(),
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) factory).getMax());
        factory.setValue(parsed);
        spinner.getEditor().setText(factory.getConverter().toString(parsed));
    }

    /** 讓 {@code owner}（在 {@code content} 裡時）整個出現在捲動區內，上下留一點邊。 */
    private static void reveal(ScrollPane scroll, Node content, Node owner) {
        boolean inside = false;
        Node target = owner;
        for (Node n = owner; n != null; n = n.getParent()) {
            inside |= n == content;
            // 整列（含說明）都要看得到，不只控制項
            if (!inside && n.getStyleClass().contains("sv-settings-row")) {
                target = n;
            }
        }
        double viewport = scroll.getViewportBounds().getHeight();
        double extra = content.getLayoutBounds().getHeight() - viewport;
        if (!inside || extra <= 0) {
            return;
        }
        Bounds b = content.sceneToLocal(target.localToScene(target.getLayoutBounds()));
        double margin = 16;
        double offset = scroll.getVvalue() * extra;
        if (b.getMinY() - margin < offset) {
            offset = b.getMinY() - margin;
        } else if (b.getMaxY() + margin > offset + viewport) {
            offset = b.getMaxY() + margin - viewport;
        } else {
            return;
        }
        scroll.setVvalue(Math.max(0, Math.min(1, offset / extra)));
    }

    private static VBox section(String title, Node... rows) {
        Label heading = new Label(title);
        heading.getStyleClass().add("sv-section-title");
        heading.setWrapText(true);
        VBox card = new VBox(rows);
        card.getStyleClass().addAll("sv-card", "sv-settings-card");
        // 最後一列的分隔線和卡片外框重疊成兩條線
        rows[rows.length - 1].getStyleClass().add("sv-settings-row-last");
        VBox box = new VBox(heading, card);
        box.getStyleClass().add("sv-settings-section");
        return box;
    }
}
