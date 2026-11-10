package app.svault.core.sync;

import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultEditor;
import java.util.Map;
import java.util.UUID;

/** 一邊的保管庫，以案例裡的名稱操作條目與群組（條目標題＝名稱）。 */
public final class MergeDevice {
    private final Vault vault;
    private final VaultEditor editor;
    private final MutableClock clock;
    private final Map<String, UUID> ids;

    MergeDevice(Vault vault, MutableClock clock, Map<String, UUID> ids) {
        this.vault = vault;
        this.editor = new VaultEditor(vault, clock);
        this.clock = clock;
        this.ids = ids;
    }

    /** @param name 新條目（放在根群組；標題與帳號都是名稱） */
    public void entry(String name) {
        ids.put(name, editor.addEntry(vault.id(), new EntryDraft(name, name, "pw-" + name, "", "")));
    }

    /** 在群組裡新增條目。 */
    public void entryIn(String group, String name) {
        ids.put(name, editor.addEntry(id(group), new EntryDraft(name, name, "pw-" + name, "", "")));
    }

    /** @param name 新群組（放在根群組；名稱就是群組名稱） */
    public void group(String name) {
        ids.put(name, editor.addGroup(vault.id(), name));
    }

    /** 在群組裡新增子群組。 */
    public void groupIn(String parent, String name) {
        ids.put(name, editor.addGroup(id(parent), name));
    }

    /** 為第一筆有附件的既有條目（KeePassXC 測試檔裡的）取名字。 */
    public void nameEntryWithAttachments(String name) {
        for (EntryView e : vault.entries()) {
            if (!e.attachmentNames().isEmpty()) {
                ids.put(name, e.uuid());
                return;
            }
        }
        throw new IllegalArgumentException("沒有附件的條目");
    }

    /** 為既有的條目（例如 KeePassXC 測試檔裡的）取名字，以標題找。 */
    public void name(String name, String title) {
        for (EntryView e : vault.entries()) {
            if (e.title().equals(title)) {
                ids.put(name, e.uuid());
                return;
            }
        }
        throw new IllegalArgumentException("找不到條目：" + title);
    }

    /**
     * 修改標準欄位。
     *
     * @param field {@code title}、{@code user}、{@code password}、{@code url}、{@code notes}
     */
    public void edit(String name, String field, String value) {
        EntryView e = view(name);
        EntryDraft d = new EntryDraft(field.equals("title") ? value : e.title(),
                field.equals("user") ? value : e.username(), field.equals("password") ? value : e.password(),
                field.equals("url") ? value : e.url(), field.equals("notes") ? value : e.notes());
        if (!editor.updateEntry(id(name), d)) {
            throw new IllegalArgumentException("沒有修改：" + name);
        }
    }

    /** 把條目移到群組（{@code null}＝根群組）。 */
    public void move(String name, String group) {
        editor.moveEntry(id(name), group == null ? vault.id() : id(group));
    }

    /** 刪除條目（移到資源回收桶）。 */
    public void recycle(String name) {
        editor.recycleEntry(id(name));
    }

    /** 永久刪除條目（先移到資源回收桶再永久刪除，寫進 DeletedObjects）。 */
    public void purge(String name) {
        editor.recycleEntry(id(name));
        editor.deleteEntryPermanently(id(name));
    }

    /** 刪除群組（移到資源回收桶）。 */
    public void recycleGroup(String name) {
        editor.recycleGroup(id(name));
    }

    /** 永久刪除群組。 */
    public void purgeGroup(String name) {
        editor.recycleGroup(id(name));
        editor.deleteGroupPermanently(id(name));
    }

    /** 重新命名群組。 */
    public void renameGroup(String name, String newName) {
        editor.renameGroup(id(name), newName);
    }

    /** @param seconds 時鐘往前撥 */
    public void later(long seconds) {
        clock.advanceSeconds(seconds);
    }

    private UUID id(String name) {
        UUID id = ids.get(name);
        if (id == null) {
            throw new IllegalArgumentException("案例裡沒有這個名稱：" + name);
        }
        return id;
    }

    private EntryView view(String name) {
        for (EntryView e : vault.entries()) {
            if (e.uuid().equals(id(name))) {
                return e;
            }
        }
        throw new IllegalArgumentException("這一邊沒有這筆條目：" + name);
    }
}
