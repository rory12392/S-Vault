package app.svault.core.vault;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 三方合併的輸入（Architecture §4）：一個保管庫在某個時間點的全部條目、群組與刪除紀錄。不可變、不持有金鑰，
 * 合併本身因此是純函式（{@code core.sync.ThreeWayMerger}）。
 *
 * @param vaultId      保管庫 ID（根群組 UUID）
 * @param entries      條目（含資源回收桶裡的；依 UUID 查詢，不可修改）
 * @param groups       群組（含根群組與資源回收桶；不可修改）
 * @param deleted      {@code DeletedObjects}：UUID → 刪除時間（不可修改）
 * @param recycleBinId 資源回收桶的群組 UUID；檔案沒有回收桶，或 {@code RecycleBinUUID} 指向不存在的群組時為 {@code null}
 */
public record VaultSnapshot(UUID vaultId, Map<UUID, EntrySnapshot> entries, Map<UUID, GroupSnapshot> groups,
                            Map<UUID, Instant> deleted, UUID recycleBinId) {

    /**
     * 複製成不可修改的版本（保留走訪順序：群組依樹的前序，父群組一定在子群組前面）。
     */
    public VaultSnapshot {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        deleted = Collections.unmodifiableMap(new LinkedHashMap<>(deleted));
    }

    /**
     * @param groupId 群組
     * @return 群組是資源回收桶本身或在它底下（群組不存在時為 {@code false}）
     */
    public boolean inRecycleBin(UUID groupId) {
        if (recycleBinId == null) {
            return false;
        }
        // 有上限：檔案損壞造成的循環不會讓這裡停不下來
        UUID g = groupId;
        for (int i = 0; g != null && i <= groups.size(); i++) {
            if (g.equals(recycleBinId)) {
                return true;
            }
            GroupSnapshot group = groups.get(g);
            g = group == null ? null : group.parentId();
        }
        return false;
    }

    @Override
    public String toString() {
        return "VaultSnapshot[" + vaultId + ", " + entries.size() + " 筆]";
    }
}
