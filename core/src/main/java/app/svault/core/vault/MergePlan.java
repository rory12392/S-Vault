package app.svault.core.vault;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 三方合併的結果要怎麼寫進遠端那份資料庫物件（Architecture §4 第 7 點；由 {@code core.sync.ThreeWayMerger} 產生、
 * {@link MergeWriter} 套用）。描述的是「相對於遠端」的變更，內容一律從本機那份複製；遠端已經是對的部分不出現在這裡，
 * 所以 S-Vault 看不懂的元素原樣保留。
 *
 * <p>套用順序：{@link #groups()}（依序：新增的在前且上層一定在子群組前面，之後是修改與移動）→ {@link #entries()}
 * → {@link #removedEntries()} → {@link #removedGroups()}（子群組在前）→ {@link #deletedObjects()}。
 *
 * @param groups         群組的新增與修改
 * @param entries        條目的新增與修改
 * @param removedEntries 要從遠端移除的條目（本機刪除、遠端沒修改）
 * @param removedGroups  要從遠端移除的群組（子群組在前）
 * @param deletedObjects 合併後完整的 {@code DeletedObjects}；和遠端相同時為 {@code null}（不改寫）
 * @param recycleBinId   要設成資源回收桶的群組（本機新建了回收桶、遠端沒有時）；不改時為 {@code null}
 */
public record MergePlan(List<GroupChange> groups, List<EntryChange> entries, List<UUID> removedEntries,
                        List<UUID> removedGroups, Map<UUID, Instant> deletedObjects, UUID recycleBinId) {

    /**
     * 複製成不可修改的版本。
     */
    public MergePlan {
        groups = List.copyOf(groups);
        entries = List.copyOf(entries);
        removedEntries = List.copyOf(removedEntries);
        removedGroups = List.copyOf(removedGroups);
        deletedObjects = deletedObjects == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(deletedObjects));
    }

    /**
     * @return 套用後遠端完全不變
     */
    public boolean isEmpty() {
        return groups.isEmpty() && entries.isEmpty() && removedEntries.isEmpty() && removedGroups.isEmpty()
                && deletedObjects == null && recycleBinId == null;
    }

    /**
     * 一個群組的變更。
     *
     * @param uuid            群組
     * @param add             {@code true}：遠端沒有，從本機整個複製（不含子項目）；{@code false}：修改遠端的群組
     * @param fieldsFromLocal 修改時要改成本機值的欄位（{@link GroupSnapshot} 的欄位鍵）
     * @param parentId        合併後的上層群組（和遠端相同時不移動）
     * @param lastModified    合併後的修改時間；{@code null} 表示不改
     * @param locationChanged 合併後的移動時間；{@code null} 表示不改
     */
    public record GroupChange(UUID uuid, boolean add, Set<String> fieldsFromLocal, UUID parentId,
                              Instant lastModified, Instant locationChanged) {

        /**
         * 複製成不可修改的版本。
         */
        public GroupChange {
            fieldsFromLocal = Collections.unmodifiableSet(new TreeSet<>(fieldsFromLocal));
        }
    }

    /**
     * 一筆條目的變更。
     *
     * @param uuid              條目
     * @param add               {@code true}：遠端沒有，從本機整個複製（含歷史版本）；{@code false}：修改遠端的條目
     * @param fieldsFromLocal   修改時要改成本機值的欄位（{@link EntrySnapshot} 的欄位鍵；本機沒有該欄位＝移除）
     * @param groupId           合併後所在的群組（和遠端相同時不移動）
     * @param lastModified      合併後的修改時間；{@code null} 表示不改
     * @param locationChanged   合併後的移動時間；{@code null} 表示不改
     * @param historyFromLocal  要從本機複製過來的歷史版本（以修改時間指定；遠端已有同一時間的版本時不在這裡）
     * @param pushRemoteCurrent 修改前先把遠端目前的版本推入歷史紀錄（衝突時沒被選的那一邊，AC-08-2）
     * @param pushLocalCurrent  把本機目前的版本推入歷史紀錄（衝突時沒被選的那一邊、沒有基準時較舊的那一份）
     */
    public record EntryChange(UUID uuid, boolean add, Set<String> fieldsFromLocal, UUID groupId,
                              Instant lastModified, Instant locationChanged, List<Instant> historyFromLocal,
                              boolean pushRemoteCurrent, boolean pushLocalCurrent) {

        /**
         * 複製成不可修改的版本。
         */
        public EntryChange {
            fieldsFromLocal = Collections.unmodifiableSet(new TreeSet<>(fieldsFromLocal));
            historyFromLocal = List.copyOf(historyFromLocal);
        }
    }
}
