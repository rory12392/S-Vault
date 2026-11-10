package app.svault.desktop.shell.entries;

import app.svault.core.security.PasswordStrength;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.Toast;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Function;

/**
 * 新增與編輯條目的流程（AC-02-1、AC-02-2）：右欄換成編輯畫面 → 「儲存」（{@code Ctrl+S}）經
 * {@link VaultOps} 寫入 → 回到詳細並選到這筆條目。離開有修改的編輯畫面前確認「捨棄修改？」。
 */
final class EditFlow {

    private final VaultScreen screen;
    private final ResourceBundle text;
    private final EntryEditPane edit;
    private boolean editing;

    /**
     * @param screen    三欄畫面
     * @param text      畫面文字
     * @param estimator 密碼強度
     * @param buttons   密碼欄旁的按鈕
     * @param pathOf    群組下拉選單的顯示名稱
     */
    EditFlow(VaultScreen screen, ResourceBundle text, Function<CharSequence, PasswordStrength> estimator,
            List<EntryEditPane.PasswordButton> buttons, Function<GroupView, String> pathOf) {
        this.screen = screen;
        this.text = text;
        this.edit = new EntryEditPane(text, estimator, buttons, pathOf, new EntryEditPane.Actions() {
            @Override
            public void save() {
                EditFlow.this.save();
            }

            @Override
            public void cancel() {
                EditFlow.this.cancel();
            }
        });
    }

    /** @return 是否在編輯畫面 */
    boolean editing() {
        return editing;
    }

    /** @return 編輯中的條目；新增或不在編輯時 {@code null} */
    EntryView editingEntry() {
        return editing ? edit.editing() : null;
    }

    /** 新增條目（{@code Ctrl+N}、「新增」、空狀態的「新增條目」）：預設放在目前的群組（回收桶時放根群組）。 */
    void startNew() {
        ScreenData data = screen.data();
        if (screen.busy() || !leaveEdit()) {
            return;
        }
        Listing.Scope scope = screen.scope();
        UUID target = scope.recycleBin() || data.inRecycleBin(scope.groupId()) ? data.root().uuid()
                : scope.groupId();
        begin(null, target);
    }

    /** @param entry 要編輯的條目（{@code Ctrl+E}、「編輯」；回收桶裡的不能編輯） */
    void startEdit(EntryView entry) {
        if (screen.busy() || screen.data().inRecycleBin(entry.groupId()) || !leaveEdit()) {
            return;
        }
        begin(entry, entry.groupId());
    }

    /**
     * 離開編輯畫面；有修改時確認「捨棄修改？」（DESIGN §6）。
     *
     * @return 已離開；使用者選「繼續編輯」時 {@code false}
     */
    boolean leaveEdit() {
        if (!editing) {
            return true;
        }
        if (screen.busy()) {
            // 儲存中不離開（結果回來時才回到詳細）
            return false;
        }
        if (edit.dirty() && !new ConfirmDialog(screen.right(), text.getString("edit.discard.title"),
                text.getString("edit.discard.body"), text.getString("edit.discard.confirm"),
                text.getString("edit.discard.keep"), ConfirmDialog.Variant.DANGER).showAndWait()) {
            return false;
        }
        close();
        return true;
    }

    /** {@code Esc}：在編輯畫面時取消編輯。 */
    void cancelShortcut() {
        if (editing) {
            cancel();
        }
    }

    /** {@code Ctrl+S}：編輯中是儲存；上一次存檔失敗時是再存一次。 */
    void saveShortcut() {
        if (editing) {
            save();
        } else if (screen.sync().state() == VaultOps.SyncState.PENDING) {
            retrySave();
        }
    }

    /** 再存一次（「尚未同步」Banner 的「再試一次」）。 */
    void retrySave() {
        screen.apply(screen.ops()::retrySave, null, out -> {
            if (out.saved()) {
                screen.shell().toasts().show(Toast.Kind.SUCCESS, text.getString("toast.saved"));
            }
        }, () -> { });
    }

    /**
     * 上鎖前（JavaFX 執行緒）：編輯畫面上還沒按「儲存」的內容，上鎖時先存（AC-12-3；DESIGN §6 不詢問）。名稱空白的由
     * {@code VaultOps.lock} 略過（同「儲存」的檢查）。
     *
     * @return 編輯中而且有修改時的內容；否則 {@code null}
     */
    PendingEdit pendingEdit() {
        if (!editing || !edit.dirty()) {
            return null;
        }
        EntryView original = edit.editing();
        return new PendingEdit(original == null ? null : original.uuid(), edit.draft(), edit.initialGroup(),
                edit.group());
    }

    /** 上鎖：丟掉編輯中的內容。 */
    void clear() {
        editing = false;
        edit.clear();
    }

    private void begin(EntryView entry, UUID group) {
        editing = true;
        edit.start(entry, screen.data().activeGroups(), group);
        screen.right().getChildren().setAll(edit);
        screen.shell().applyCss();
        screen.shell().layout();
        edit.focusDefault();
    }

    private void close() {
        editing = false;
        edit.clear();
        screen.showDetailPane();
    }

    private void save() {
        if (screen.busy() || !edit.validate()) {
            return;
        }
        EntryView original = edit.editing();
        EntryDraft draft = edit.draft();
        UUID from = edit.initialGroup();
        UUID group = edit.group();
        VaultOps ops = screen.ops();
        // 儲存中停在編輯畫面（「儲存」loading）；完成後回到詳細、群組樹選到條目所在的群組並選到這筆條目
        edit.setSaving(true);
        screen.apply(() -> original == null ? ops.addEntry(group, draft)
                : ops.updateEntry(original.uuid(), draft, from, group), out -> {
                    editing = false;
                    edit.clear();
                    if (screen.notSearching()) {
                        screen.selectGroup(group.toString(), false);
                    }
                    return out.subject();
                }, out -> {
                    screen.showDetailPane();
                    if (out.saved()) {
                        screen.shell().toasts().show(Toast.Kind.SUCCESS, text.getString("toast.saved"));
                    }
                    screen.list().focusDefault();
                }, () -> edit.setSaving(false));
    }

    private void cancel() {
        if (leaveEdit()) {
            screen.list().focusDefault();
        }
    }
}
