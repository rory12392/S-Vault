package app.svault.desktop.shell.entries;

import app.svault.core.vault.EntryView;
import app.svault.desktop.ui.FieldRow;
import app.svault.desktop.ui.Shortcut;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 三欄畫面的快捷鍵（DESIGN §7）對應到的動作。T-18 從 {@link VaultScreen} 拆出（行為不變），{@code Ctrl+L} 上鎖由
 * {@code AppShell} 綁定、不在這裡。
 */
final class VaultShortcuts {

    private VaultShortcuts() {
    }

    /**
     * @param screen 三欄畫面
     * @param groups 群組的動作
     * @return 快捷鍵與動作
     */
    static Map<Shortcut, Runnable> of(VaultScreen screen, GroupActions groups) {
        EditFlow edit = screen.editFlow();
        EntryActions entries = screen.entryActions();
        return Map.ofEntries(
                Map.entry(Shortcut.SEARCH, () -> screen.list().search().focusField()),
                Map.entry(Shortcut.NEW_ENTRY, edit::startNew),
                Map.entry(Shortcut.EDIT_ENTRY, () -> withShown(screen, edit::startEdit)),
                Map.entry(Shortcut.SAVE, edit::saveShortcut),
                Map.entry(Shortcut.COPY_USERNAME,
                        () -> withShown(screen, e -> entries.copy(e, FieldRow.Kind.TEXT, null))),
                Map.entry(Shortcut.COPY_PASSWORD,
                        () -> withShown(screen, e -> entries.copy(e, FieldRow.Kind.PASSWORD, null))),
                Map.entry(Shortcut.OPEN_URL, () -> withShown(screen, entries::openUrl)),
                Map.entry(Shortcut.DELETE, () -> delete(screen, groups)),
                Map.entry(Shortcut.RENAME_GROUP, groups::renameSelected),
                Map.entry(Shortcut.CANCEL, edit::cancelShortcut),
                Map.entry(Shortcut.TOGGLE_PASSWORD, () -> {
                    if (!edit.editing()) {
                        screen.detail().toggleReveal();
                    }
                }));
    }

    private static void withShown(VaultScreen screen, Consumer<EntryView> action) {
        if (!screen.editFlow().editing() && screen.detail().entry() != null) {
            action.accept(screen.detail().entry());
        }
    }

    private static void delete(VaultScreen screen, GroupActions groups) {
        if (screen.editFlow().editing()) {
            return;
        }
        if (screen.shell().getScene() != null && screen.shell().getScene().getFocusOwner() == screen.tree()) {
            groups.deleteSelected();
        } else if (screen.detail().entry() != null) {
            screen.entryActions().deleteShown(screen.detail().entry());
        }
    }
}
