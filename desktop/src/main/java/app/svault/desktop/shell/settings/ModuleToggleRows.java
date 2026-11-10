package app.svault.desktop.shell.settings;

import app.svault.core.module.ModuleToggle;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.SettingsRow;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;

/**
 * 設定頁「功能模組」區段（T-38）：探索到的每個模組一個啟用開關（停用中的也列出），寫
 * {@code modules.<id>.enabled}。模組只在啟動時載入，所以改了之後說明換成「重新啟動 S-Vault 後…」；停用前 danger 確認，
 * 取消時開關不變。列名稱只用 {@code displayName()}（{@code ModuleRegistry#toggles} 在錯誤邊界內取得）。
 */
final class ModuleToggleRows {

    private ModuleToggleRows() {
    }

    /**
     * @param toggle 一列的狀態
     * @param text   文字
     * @return 列的說明：要重新啟動才生效時的提示；不用時 {@code null}
     */
    static String note(ModuleToggle toggle, ResourceBundle text) {
        if (!toggle.restartNeeded()) {
            return null;
        }
        return text.getString(toggle.enabled() ? "settings.modules.restartOn" : "settings.modules.restartOff");
    }

    /**
     * 在 JavaFX 執行緒上呼叫。
     *
     * @param text    文字
     * @param saver   設定的變更
     * @param toggles {@code ModuleRegistry#toggles} 的結果
     * @return 每個模組一列；沒有模組時是空陣列
     */
    static Node[] build(ResourceBundle text, SettingsSaver saver, List<ModuleToggle> toggles) {
        List<Node> rows = new ArrayList<>();
        for (ModuleToggle toggle : toggles) {
            rows.add(row(text, saver, toggle));
        }
        return rows.toArray(new Node[0]);
    }

    private static SettingsRow row(ResourceBundle text, SettingsSaver saver, ModuleToggle initial) {
        SettingsRow row = SettingsRow.toggle(initial.name(), note(initial, text));
        CheckBox box = (CheckBox) row.control();
        box.setId("settings-module-" + initial.id());
        box.setSelected(initial.enabled());
        boolean[] reverting = {false};
        box.selectedProperty().addListener((obs, old, on) -> {
            if (reverting[0]) {
                return;
            }
            if (!on && !confirmDisable(box, text, initial.name())) {
                // 取消：開關回到開啟，不寫設定
                reverting[0] = true;
                box.setSelected(true);
                reverting[0] = false;
                return;
            }
            saver.change(s -> s.withModuleEnabled(initial.id(), on));
            row.setDescription(note(new ModuleToggle(initial.id(), initial.name(), initial.running(), on), text));
        });
        return row;
    }

    private static boolean confirmDisable(Node owner, ResourceBundle text, String name) {
        return new ConfirmDialog(owner, MessageFormat.format(text.getString("settings.modules.disable.title"), name),
                text.getString("settings.modules.disable.body"), text.getString("settings.modules.disable.confirm"),
                ConfirmDialog.Variant.DANGER).showAndWait();
    }
}
