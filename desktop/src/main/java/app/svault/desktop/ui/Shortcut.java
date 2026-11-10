package app.svault.desktop.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * desktop 快捷鍵。按鍵組合只定義在這裡；畫面以 {@link ShortcutRegistry} 綁定動作，
 * 選單項目用 {@link #combination()} 顯示同一個組合。
 */
public enum Shortcut {
    LOCK(KeyCode.L, true, false),
    SEARCH(KeyCode.F, true, false),
    NEW_ENTRY(KeyCode.N, true, false),
    EDIT_ENTRY(KeyCode.E, true, false),
    SAVE(KeyCode.S, true, false),
    TOGGLE_GROUPS(KeyCode.G, true, false),
    TOGGLE_PASSWORD(KeyCode.V, false, true),
    COPY_USERNAME(KeyCode.B, true, false),
    /** 焦點在文字欄時是一般的複製，不觸發。 */
    COPY_PASSWORD(KeyCode.C, true, false),
    OPEN_URL(KeyCode.U, true, false),
    /** 焦點在文字欄時是刪除文字，不觸發。 */
    DELETE(KeyCode.DELETE, false, false),
    RENAME_GROUP(KeyCode.F2, false, false),
    /** 對話框的 Esc 由 JavaFX 內建處理；這裡是取消編輯、清空搜尋等畫面層級的 Esc。 */
    CANCEL(KeyCode.ESCAPE, false, false),
    SETTINGS(KeyCode.COMMA, true, false);

    private final KeyCombination combination;

    Shortcut(KeyCode code, boolean shortcutDown, boolean altDown) {
        if (shortcutDown) {
            combination = new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
        } else if (altDown) {
            combination = new KeyCodeCombination(code, KeyCombination.ALT_DOWN);
        } else {
            combination = new KeyCodeCombination(code);
        }
    }

    /** @return 按鍵組合 */
    public KeyCombination combination() {
        return combination;
    }

    /**
     * @return 焦點在文字輸入元件時是否讓給文字編輯（Ctrl+C、Delete）
     */
    public boolean yieldsToTextInput() {
        return this == COPY_PASSWORD || this == DELETE;
    }
}
