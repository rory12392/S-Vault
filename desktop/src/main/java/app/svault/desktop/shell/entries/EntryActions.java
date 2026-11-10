package app.svault.desktop.shell.entries;

import app.svault.core.session.ClipboardGuard;
import app.svault.core.shell.Links;
import app.svault.core.vault.EntryView;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.FieldRow;
import app.svault.desktop.ui.Toast;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * 詳細頁的條目動作：刪除到資源回收桶（確認，AC-02-3）、還原、永久刪除（確認）、
 * 複製欄位（密碼依設定的秒數後清除，T-19）、用系統瀏覽器開網址。
 */
final class EntryActions implements EntryDetailPane.Actions {

    private final VaultScreen screen;
    private final ResourceBundle text;
    private final ClipboardGuard clipboard;
    private final Consumer<String> browser;

    /**
     * @param screen    三欄畫面
     * @param text      畫面文字
     * @param clipboard 剪貼簿
     * @param browser   用系統瀏覽器開網址
     */
    EntryActions(VaultScreen screen, ResourceBundle text, ClipboardGuard clipboard, Consumer<String> browser) {
        this.screen = screen;
        this.text = text;
        this.clipboard = clipboard;
        this.browser = browser;
    }

    @Override
    public void edit(EntryView entry) {
        screen.editFlow().startEdit(entry);
    }

    /** @param entry {@code Delete}：一般條目移到回收桶，回收桶裡的永久刪除（都先確認） */
    void deleteShown(EntryView entry) {
        if (screen.data().inRecycleBin(entry.groupId())) {
            deletePermanently(entry);
        } else {
            delete(entry);
        }
    }

    @Override
    public void delete(EntryView entry) {
        if (screen.busy() || !confirm("confirm.deleteEntry", entry.title(), "confirm.deleteEntry.confirm")) {
            return;
        }
        int index = selectedIndex();
        screen.apply(() -> screen.ops().recycleEntry(entry.uuid()), out -> null, out -> {
            selectIndex(index);
            if (out.saved()) {
                toast(Toast.Kind.SUCCESS, out.recycled() ? "toast.recycled" : "toast.deletedNoBin");
            }
        }, () -> { });
    }

    @Override
    public void restore(EntryView entry) {
        if (screen.busy()) {
            return;
        }
        int index = selectedIndex();
        screen.apply(() -> screen.ops().restoreEntry(entry.uuid()), out -> null, out -> {
            selectIndex(index);
            if (out.saved()) {
                toast(Toast.Kind.SUCCESS, "toast.restored");
            }
        }, () -> { });
    }

    @Override
    public void deletePermanently(EntryView entry) {
        if (screen.busy()
                || !confirm("confirm.deletePermanently", entry.title(), "confirm.deletePermanently.confirm")) {
            return;
        }
        int index = selectedIndex();
        screen.apply(() -> screen.ops().deleteEntryPermanently(entry.uuid()), out -> null, out -> {
            selectIndex(index);
            if (out.saved()) {
                toast(Toast.Kind.SUCCESS, "toast.deletedPermanently");
            }
        }, () -> { });
    }

    @Override
    public void copy(EntryView entry, FieldRow.Kind kind, FieldRow row) {
        if (kind == FieldRow.Kind.PASSWORD) {
            // 提示的秒數用實際排定的那一個（AC-05-1）
            long seconds = clipboard.copySecret(entry.password()).toSeconds();
            if (row != null) {
                row.markCopied();
            }
            screen.shell().toasts().show(Toast.Kind.INFO,
                    MessageFormat.format(text.getString("toast.copiedPassword"), seconds));
            return;
        }
        switch (kind) {
            case TEXT -> clipboard.copy(entry.username());
            case URL -> clipboard.copy(entry.url());
            default -> clipboard.copy(entry.notes());
        }
        if (row != null) {
            row.markCopied();
        }
        toast(Toast.Kind.INFO, switch (kind) {
            case TEXT -> "toast.copiedUsername";
            case URL -> "toast.copiedUrl";
            default -> "toast.copiedNotes";
        });
    }

    @Override
    public void openUrl(EntryView entry) {
        Links.browsable(entry.url()).ifPresentOrElse(browser, () -> toast(Toast.Kind.ERROR, "toast.urlFailed"));
    }

    private int selectedIndex() {
        return screen.list().list().getSelectionModel().getSelectedIndex();
    }

    /** 刪除或還原後選到原本位置的下一筆，鍵盤可以連續操作。 */
    private void selectIndex(int index) {
        int size = screen.list().list().getItems().size();
        if (size > 0 && index >= 0) {
            screen.list().list().getSelectionModel().select(Math.min(index, size - 1));
        }
        screen.list().focusDefault();
    }

    private boolean confirm(String prefix, String name, String confirmKey) {
        return new ConfirmDialog(screen.list(), text.getString(prefix + ".title"),
                MessageFormat.format(text.getString(prefix + ".body"), name), text.getString(confirmKey),
                ConfirmDialog.Variant.DANGER).showAndWait();
    }

    private void toast(Toast.Kind kind, String key) {
        screen.shell().toasts().show(kind, text.getString(key));
    }
}
