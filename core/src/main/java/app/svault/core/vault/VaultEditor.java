package app.svault.core.vault;

import static app.svault.core.vault.VaultTree.collectIds;
import static app.svault.core.vault.VaultTree.findEntry;
import static app.svault.core.vault.VaultTree.findGroup;
import static app.svault.core.vault.VaultTree.keepingGroupTimes;
import static app.svault.core.vault.VaultTree.setValue;
import static app.svault.core.vault.VaultTree.stampNew;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;

/**
 * 條目與群組的寫入（Architecture §6；AC-02-1～AC-02-4）。只改記憶體中的 {@link Vault}，
 * 寫回遠端一律由呼叫端經 {@code SaveCoordinator}（CONVENTIONS §3）。
 *
 * <p>補上函式庫沒有做、但 KDBX 與 KeePassXC 期待的事：
 * <ul>
 *   <li>修改條目前把舊版本推入 History，超過 {@code HistoryMaxItems} 時刪最舊的（{@code HistoryMaxSize}
 *       不處理，KeePassXC 開檔時會自己整理）；</li>
 *   <li>時間一律來自注入的 {@link Clock}，截到秒（KDBX 4 的精度，讀回驗證才會一致）；函式庫在放入條目時
 *       會把群組的修改時間改成當下（帶毫秒），這裡改回原值：條目移進移出不算群組被修改（和 KeePassXC 相同）；</li>
 *   <li>欄位值用保護策略建立，原本受保護的欄位維持受保護；</li>
 *   <li>刪除進資源回收桶時記下 {@code PreviousParentGroup}，還原時回到那裡；永久刪除記進 {@code DeletedObjects}，
 *       合併（T-13）與 KeePassXC 才知道它是被刪掉的。</li>
 * </ul>
 *
 * <p>不是執行緒安全的：和 {@link Vault} 一樣同一時間只由一個背景執行緒使用（CONVENTIONS §2）。
 * 不合理的要求（找不到 UUID、名稱空白、對根群組或資源回收桶本身的刪除、把東西直接放進回收桶）拋
 * {@link IllegalArgumentException}，保管庫內容不變。
 */
public final class VaultEditor {

    private static final String[] FIELDS = {
        Entry.STANDARD_PROPERTY_NAME_TITLE, Entry.STANDARD_PROPERTY_NAME_USER_NAME,
        Entry.STANDARD_PROPERTY_NAME_PASSWORD, Entry.STANDARD_PROPERTY_NAME_URL, Entry.STANDARD_PROPERTY_NAME_NOTES,
    };

    private final Vault vault;
    private final Clock clock;

    /**
     * @param vault 開啟中的保管庫
     * @param clock 修改時間的來源（CONVENTIONS §2）
     */
    public VaultEditor(Vault vault, Clock clock) {
        this.vault = vault;
        this.clock = clock;
    }

    /**
     * 新增條目（AC-02-1）。
     *
     * @param groupId 所屬群組（不能是資源回收桶或其中的群組）
     * @param draft   內容；名稱不能空白
     * @return 新條目的 UUID
     * @throws IllegalArgumentException 名稱空白、群組不存在或在資源回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public UUID addEntry(UUID groupId, EntryDraft draft) {
        requireTitle(draft);
        JacksonGroup target = group(groupId);
        requireOutsideRecycleBin(target);
        JacksonDatabase db = vault.database();
        JacksonEntry entry = db.newEntry();
        String[] values = values(draft);
        for (int i = 0; i < FIELDS.length; i++) {
            setValue(db, entry, FIELDS[i], values[i]);
        }
        stampNew(KdbxModelAccess.entryTimes(entry), now());
        keepingGroupTimes(() -> target.addEntry(entry), target);
        return entry.getUuid();
    }

    /**
     * 修改條目的標準五欄（AC-02-2）。有任何欄位不同時，先把目前的版本推入歷史紀錄，再更新內容與修改時間；
     * 自訂欄位、附件、標籤等 S-Vault 不編輯的內容原樣保留。
     *
     * @param entryId 條目
     * @param draft   新內容；名稱不能空白
     * @return 有修改時為 {@code true}；內容完全相同時為 {@code false}（不產生歷史版本、不改時間）
     * @throws IllegalArgumentException 名稱空白或條目不存在
     * @throws IllegalStateException    保管庫已上鎖
     */
    public boolean updateEntry(UUID entryId, EntryDraft draft) {
        requireTitle(draft);
        JacksonEntry entry = entry(entryId);
        String[] values = values(draft);
        boolean changed = false;
        for (int i = 0; i < FIELDS.length; i++) {
            changed |= !values[i].equals(current(entry, FIELDS[i]));
        }
        if (!changed) {
            return false;
        }
        JacksonDatabase db = vault.database();
        pushHistory(db, entry);
        for (int i = 0; i < FIELDS.length; i++) {
            setValue(db, entry, FIELDS[i], values[i]);
        }
        Date now = now();
        Times times = KdbxModelAccess.entryTimes(entry);
        times.setLastModificationTime(now);
        times.setLastAccessTime(now);
        return true;
    }

    /**
     * 把條目移到另一個群組（AC-02-4）。只更新 {@code LocationChanged}，修改時間不變（和 KeePassXC 相同）。
     * 從資源回收桶拖出來也可以，等於還原到指定的群組。
     *
     * @param entryId 條目
     * @param groupId 目標群組（不能是資源回收桶或其中的群組；要刪除用 {@link #recycleEntry}）
     * @throws IllegalArgumentException 條目或群組不存在、目標在資源回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void moveEntry(UUID entryId, UUID groupId) {
        JacksonEntry entry = entry(entryId);
        JacksonGroup target = group(groupId);
        requireOutsideRecycleBin(target);
        if (entry.getParent() != target) {
            relocate(entry, target);
        }
    }

    /**
     * 刪除條目：移到資源回收桶（AC-02-3；回收桶不存在時建立）。檔案停用資源回收桶時（KeePassXC 的資料庫
     * 設定）照 KeePassXC 的做法直接永久刪除。
     *
     * @param entryId 條目（不能已經在資源回收桶裡；那裡的條目用 {@link #deleteEntryPermanently}）
     * @return 移到資源回收桶時為 {@code true}；因為停用回收桶而永久刪除時為 {@code false}
     * @throws IllegalArgumentException 條目不存在或已在資源回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public boolean recycleEntry(UUID entryId) {
        JacksonEntry entry = entry(entryId);
        if (inRecycleBin(entry.getParent())) {
            throw new IllegalArgumentException("條目已經在資源回收桶裡");
        }
        if (!recycleBinEnabled()) {
            removePermanently(entry);
            return false;
        }
        JacksonGroup bin = recycleBin();
        KdbxModelAccess.setPreviousParent(entry, entry.getParent().getUuid());
        relocate(entry, bin);
        return true;
    }

    /**
     * 把資源回收桶裡的條目還原到刪除前的群組；那個群組已不存在或也在回收桶裡時，還原到根群組（AC-02-3）。
     *
     * @param entryId 資源回收桶裡的條目
     * @throws IllegalArgumentException 條目不存在或不在資源回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void restoreEntry(UUID entryId) {
        JacksonEntry entry = entry(entryId);
        if (!inRecycleBin(entry.getParent())) {
            throw new IllegalArgumentException("條目不在資源回收桶裡");
        }
        relocate(entry, restoreTarget(KdbxModelAccess.previousParent(entry)));
    }

    /**
     * 永久刪除資源回收桶裡的條目，並記進 {@code DeletedObjects}。
     *
     * @param entryId 資源回收桶裡的條目
     * @throws IllegalArgumentException 條目不存在或不在資源回收桶裡（要先 {@link #recycleEntry}）
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void deleteEntryPermanently(UUID entryId) {
        JacksonEntry entry = entry(entryId);
        if (!inRecycleBin(entry.getParent())) {
            throw new IllegalArgumentException("只能永久刪除資源回收桶裡的條目");
        }
        removePermanently(entry);
    }

    /**
     * 新增群組（AC-02-4）。
     *
     * @param parentId 上層群組（不能是資源回收桶或其中的群組）
     * @param name     名稱；不能空白
     * @return 新群組的 UUID
     * @throws IllegalArgumentException 名稱空白、上層群組不存在或在資源回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public UUID addGroup(UUID parentId, String name) {
        requireName(name);
        JacksonGroup parent = group(parentId);
        requireOutsideRecycleBin(parent);
        JacksonGroup group = vault.database().newGroup();
        group.setName(name);
        stampNew(KdbxModelAccess.groupTimes(group), now());
        keepingGroupTimes(() -> parent.addGroup(group), parent);
        return group.getUuid();
    }

    /**
     * 重新命名群組（AC-02-4），更新群組的修改時間。
     *
     * @param groupId 群組（不能是根群組：根群組名稱就是保管庫名稱）
     * @param name    新名稱；不能空白
     * @throws IllegalArgumentException 名稱空白、群組不存在或是根群組
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void renameGroup(UUID groupId, String name) {
        requireName(name);
        JacksonGroup group = group(groupId);
        if (isRoot(group)) {
            throw new IllegalArgumentException("根群組不能在這裡重新命名");
        }
        if (name.equals(group.getName())) {
            return;
        }
        group.setName(name);
        Date now = now();
        Times times = KdbxModelAccess.groupTimes(group);
        times.setLastModificationTime(now);
        times.setLastAccessTime(now);
    }

    /**
     * 刪除群組：連同裡面的條目與子群組移到資源回收桶（AC-02-4）。刪除前要提示的條目數由
     * {@link EntryQuery#countInGroup} 提供。檔案停用資源回收桶時直接永久刪除。
     *
     * @param groupId 群組（不能是根群組、資源回收桶本身或其中的群組）
     * @return 移到資源回收桶時為 {@code true}；因為停用回收桶而永久刪除時為 {@code false}
     * @throws IllegalArgumentException 群組不存在，或是根群組、資源回收桶、已在回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public boolean recycleGroup(UUID groupId) {
        JacksonGroup group = group(groupId);
        if (isRoot(group) || inRecycleBin(group)) {
            throw new IllegalArgumentException("根群組、資源回收桶與其中的群組不能這樣刪除");
        }
        if (!recycleBinEnabled()) {
            removePermanently(group);
            return false;
        }
        JacksonGroup bin = recycleBin();
        KdbxModelAccess.setPreviousParent(group, group.getParent().getUuid());
        relocate(group, bin);
        return true;
    }

    /**
     * 把資源回收桶裡的群組（連同內容）還原到刪除前的位置；那裡已不存在時還原到根群組。
     *
     * @param groupId 資源回收桶裡的群組
     * @throws IllegalArgumentException 群組不存在、是資源回收桶本身或不在回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void restoreGroup(UUID groupId) {
        JacksonGroup group = group(groupId);
        if (isRecycleBin(group) || !inRecycleBin(group)) {
            throw new IllegalArgumentException("群組不在資源回收桶裡");
        }
        relocate(group, restoreTarget(KdbxModelAccess.previousParent(group)));
    }

    /**
     * 永久刪除資源回收桶裡的群組；群組本身、所有子群組與條目都記進 {@code DeletedObjects}。
     *
     * @param groupId 資源回收桶裡的群組
     * @throws IllegalArgumentException 群組不存在、是資源回收桶本身或不在回收桶裡
     * @throws IllegalStateException    保管庫已上鎖
     */
    public void deleteGroupPermanently(UUID groupId) {
        JacksonGroup group = group(groupId);
        if (isRecycleBin(group) || !inRecycleBin(group)) {
            throw new IllegalArgumentException("只能永久刪除資源回收桶裡的群組");
        }
        removePermanently(group);
    }

    private void pushHistory(JacksonDatabase db, JacksonEntry entry) {
        List<JacksonEntry> history = KdbxModelAccess.historyForWrite(entry);
        history.add(KdbxModelAccess.copyForHistory(db, entry));
        int max = vault.historyMaxItems();
        // KeePassXC：-1 表示不限制
        while (max >= 0 && history.size() > max) {
            history.remove(0);
        }
    }

    private static String current(JacksonEntry entry, String name) {
        String value = entry.getProperty(name);
        return value == null ? "" : value;
    }

    private void relocate(JacksonEntry entry, JacksonGroup target) {
        JacksonGroup from = entry.getParent();
        keepingGroupTimes(() -> target.addEntry(entry), from, target);
        KdbxModelAccess.entryTimes(entry).setLocationChanged(now());
    }

    private void relocate(JacksonGroup group, JacksonGroup target) {
        JacksonGroup from = group.getParent();
        // addGroup 會先從原本的上層移除（removeGroup），兩邊的修改時間都會被函式庫改掉
        keepingGroupTimes(() -> target.addGroup(group), from, target);
        KdbxModelAccess.groupTimes(group).setLocationChanged(now());
    }

    private void removePermanently(JacksonEntry entry) {
        JacksonGroup from = entry.getParent();
        keepingGroupTimes(() -> from.removeEntry(entry), from);
        KdbxModelAccess.addDeletedObjects(vault.database(), Collections.singletonList(entry.getUuid()), now());
    }

    private void removePermanently(JacksonGroup group) {
        List<UUID> deleted = new ArrayList<>();
        collectIds(group, deleted);
        JacksonGroup from = group.getParent();
        keepingGroupTimes(() -> from.removeGroup(group), from);
        KdbxModelAccess.addDeletedObjects(vault.database(), deleted, now());
    }

    private JacksonGroup recycleBin() {
        return VaultTree.recycleBin(vault.database(), now());
    }

    private boolean recycleBinEnabled() {
        // 檔案沒寫時視為啟用（KeePass 的預設）
        return !Boolean.FALSE.equals(KdbxModelAccess.file(vault.database()).meta.recycleBinEnabled);
    }

    private JacksonGroup restoreTarget(UUID previousParent) {
        return VaultTree.restoreTarget(vault.database(), previousParent);
    }

    private boolean isRoot(JacksonGroup group) {
        return group == vault.database().getRootGroup();
    }

    private boolean isRecycleBin(JacksonGroup group) {
        return VaultTree.isRecycleBin(vault.database(), group);
    }

    private boolean inRecycleBin(JacksonGroup group) {
        return VaultTree.inRecycleBin(vault.database(), group);
    }

    private void requireOutsideRecycleBin(JacksonGroup group) {
        if (inRecycleBin(group)) {
            throw new IllegalArgumentException("不能直接放進資源回收桶");
        }
    }

    /** 找條目，包含資源回收桶裡的。 */
    private JacksonEntry entry(UUID id) {
        JacksonEntry entry = findEntry(vault.database().getRootGroup(), id);
        if (entry == null) {
            throw new IllegalArgumentException("找不到條目：" + id);
        }
        return entry;
    }

    private JacksonGroup group(UUID id) {
        JacksonGroup group = findGroup(vault.database().getRootGroup(), id);
        if (group == null) {
            throw new IllegalArgumentException("找不到群組：" + id);
        }
        return group;
    }

    /** @return 現在時間，截到秒（KDBX 4 只存到秒；記憶體與檔案一致，讀回驗證才會通過） */
    private Date now() {
        return Date.from(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    private static String[] values(EntryDraft draft) {
        return new String[] {draft.title(), draft.username(), draft.password(), draft.url(), draft.notes()};
    }

    private static void requireTitle(EntryDraft draft) {
        requireName(draft.title());
    }

    private static void requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("名稱不能是空的");
        }
    }
}
