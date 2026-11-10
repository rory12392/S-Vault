package app.svault.desktop.shell.entries;

import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.GroupTree;
import app.svault.desktop.ui.Toast;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;

/**
 * 群組樹的操作（AC-02-4）：右鍵／選單鍵的「新增群組」「重新命名」（{@code F2}）「刪除」
 * （{@code Delete}，含條目時提示條目數並確認）、資源回收桶裡的群組「還原群組」「永久刪除」，以及把條目拖放到群組。
 */
final class GroupActions {

    private final VaultScreen screen;
    private final ResourceBundle text;

    /**
     * @param screen 三欄畫面
     * @param text   畫面文字
     */
    GroupActions(VaultScreen screen, ResourceBundle text) {
        this.screen = screen;
        this.text = text;
    }

    /**
     * @param item 選取的群組
     * @return 這個群組可以做的事
     */
    List<MenuItem> menu(GroupTree.Item item) {
        List<MenuItem> out = new ArrayList<>();
        switch (item.kind()) {
            case ROOT -> out.add(item("groups.add", () -> add(item)));
            case GROUP -> {
                out.add(item("groups.add", () -> add(item)));
                out.add(item("groups.rename", () -> rename(item)));
                out.add(item("groups.delete", () -> delete(item)));
            }
            case RECYCLED -> {
                out.add(item("groups.restore", () -> restore(item)));
                out.add(item("groups.deletePermanently", () -> deletePermanently(item)));
            }
            case RECYCLE_BIN -> { }
        }
        for (MenuItem m : out) {
            m.setDisable(screen.busy());
        }
        return out;
    }

    /**
     * 條目拖放到群組上（AC-02-4）。
     *
     * @param entryId 條目 UUID 字串
     * @param target  目標群組
     */
    void drop(String entryId, GroupTree.Item target) {
        UUID entry = UUID.fromString(entryId);
        if (screen.busy() || screen.editFlow().editing() || !target.kind().acceptsEntries()
                || screen.data().entry(entry).map(e -> e.groupId().toString().equals(target.id())).orElse(true)) {
            return;
        }
        screen.apply(() -> screen.ops().moveEntry(entry, UUID.fromString(target.id())), out -> null, out -> {
            if (out.saved()) {
                screen.shell().toasts().show(Toast.Kind.SUCCESS,
                        MessageFormat.format(text.getString("toast.moved"), target.name()));
            }
        }, () -> { });
    }

    /** {@code F2}：重新命名選取的群組（根群組與資源回收桶不行）。 */
    void renameSelected() {
        GroupTree.Item item = selected();
        if (item != null && item.kind() == GroupTree.Kind.GROUP) {
            rename(item);
        }
    }

    /** {@code Delete}（焦點在群組樹）：刪除或永久刪除選取的群組。 */
    void deleteSelected() {
        GroupTree.Item item = selected();
        if (item == null) {
            return;
        }
        if (item.kind() == GroupTree.Kind.GROUP) {
            delete(item);
        } else if (item.kind() == GroupTree.Kind.RECYCLED) {
            deletePermanently(item);
        }
    }

    private void add(GroupTree.Item parent) {
        if (screen.busy() || !screen.editFlow().leaveEdit()) {
            return;
        }
        new NameDialog(screen.tree(), text, text.getString("name.addTitle"), "", text.getString("name.add"))
                .showAndWait().ifPresent(name -> screen.apply(
                        () -> screen.ops().addGroup(UUID.fromString(parent.id()), name), out -> null, out -> {
                            screen.selectGroup(out.subject().toString(), true);
                            screen.tree().requestFocus();
                            if (out.saved()) {
                                toast("toast.groupAdded", name);
                            }
                        }, () -> { }));
    }

    private void rename(GroupTree.Item item) {
        if (screen.busy()) {
            return;
        }
        new NameDialog(screen.tree(), text, text.getString("name.renameTitle"), item.name(),
                text.getString("name.rename")).showAndWait()
                .filter(name -> !name.equals(item.name()))
                .ifPresent(name -> screen.apply(() -> screen.ops().renameGroup(UUID.fromString(item.id()), name),
                        null, out -> {
                            screen.tree().requestFocus();
                            if (out.saved()) {
                                toast("toast.groupRenamed", name);
                            }
                        }, () -> { }));
    }

    private void delete(GroupTree.Item item) {
        int count = screen.data().query().countInGroup(UUID.fromString(item.id()));
        if (screen.busy() || !screen.editFlow().leaveEdit()
                || !confirm("confirm.deleteGroup", item, count, "confirm.deleteGroup.confirm")) {
            return;
        }
        // 刪除後回到根群組（不留在已移進回收桶的群組上）
        screen.apply(() -> screen.ops().recycleGroup(UUID.fromString(item.id())), out -> {
            screen.selectGroup(screen.data().root().uuid().toString(), false);
            return null;
        }, out -> {
            screen.tree().requestFocus();
            if (out.saved()) {
                screen.shell().toasts().show(Toast.Kind.SUCCESS,
                        text.getString(out.recycled() ? "toast.recycled" : "toast.deletedNoBin"));
            }
        }, () -> { });
    }

    private void restore(GroupTree.Item item) {
        if (screen.busy() || !screen.editFlow().leaveEdit()) {
            return;
        }
        screen.apply(() -> screen.ops().restoreGroup(UUID.fromString(item.id())), out -> null, out -> {
            screen.tree().requestFocus();
            if (out.saved()) {
                screen.shell().toasts().show(Toast.Kind.SUCCESS, text.getString("toast.restored"));
            }
        }, () -> { });
    }

    private void deletePermanently(GroupTree.Item item) {
        int count = screen.data().query().countInGroup(UUID.fromString(item.id()));
        if (screen.busy() || !screen.editFlow().leaveEdit()
                || !confirm("confirm.deleteGroupPermanently", item, count, "confirm.deletePermanently.confirm")) {
            return;
        }
        screen.apply(() -> screen.ops().deleteGroupPermanently(UUID.fromString(item.id())), out -> null, out -> {
            screen.tree().requestFocus();
            if (out.saved()) {
                screen.shell().toasts().show(Toast.Kind.SUCCESS, text.getString("toast.deletedPermanently"));
            }
        }, () -> { });
    }

    private GroupTree.Item selected() {
        TreeItem<GroupTree.Item> item = screen.tree().getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private boolean confirm(String prefix, GroupTree.Item item, int count, String confirmKey) {
        String body = count == 0 ? text.getString(prefix + ".bodyEmpty")
                : MessageFormat.format(text.getString(prefix + ".body"), count);
        return new ConfirmDialog(screen.tree(), MessageFormat.format(text.getString(prefix + ".title"), item.name()),
                body, text.getString(confirmKey), ConfirmDialog.Variant.DANGER).showAndWait();
    }

    private MenuItem item(String key, Runnable action) {
        MenuItem m = new MenuItem(text.getString(key));
        m.setOnAction(e -> action.run());
        return m;
    }

    private void toast(String key, String name) {
        screen.shell().toasts().show(Toast.Kind.SUCCESS, MessageFormat.format(text.getString(key), name));
    }
}
