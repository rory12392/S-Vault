package app.svault.desktop.shell.entries;

import app.svault.core.vault.EntryView;
import app.svault.desktop.ui.FieldRow;
import javafx.application.Platform;

/**
 * 條目清單的動作（新增、選取、開啟、複製密碼）。T-14 從 {@link VaultScreen} 拆出（行為不變，檔案行數上限）。
 */
final class ListActions implements EntryListPane.Actions {

    private final VaultScreen screen;

    /**
     * @param screen 三欄畫面（建構時其他欄位還沒建好，用到時才取）
     */
    ListActions(VaultScreen screen) {
        this.screen = screen;
    }

    @Override
    public void add() {
        screen.editFlow().startNew();
    }

    @Override
    public void selected(EntryView entry) {
        EditFlow editFlow = screen.editFlow();
        if (editFlow.editing() && !editFlow.leaveEdit()) {
            EntryView editing = editFlow.editingEntry();
            // 不在選取變更的通知裡再改選取
            Platform.runLater(() -> screen.list().selectSilently(editing == null ? null : editing.uuid()));
            return;
        }
        screen.showDetail(entry);
    }

    @Override
    public void open(EntryView entry) {
        screen.detail().focusFirst();
    }

    @Override
    public void copyPassword(EntryView entry) {
        screen.entryActions().copy(entry, FieldRow.Kind.PASSWORD, null);
    }
}
