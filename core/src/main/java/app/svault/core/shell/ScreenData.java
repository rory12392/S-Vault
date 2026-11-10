package app.svault.core.shell;

import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.Vault;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 條目畫面要顯示的保管庫內容：同一個時間點的條目、群組與查詢（不可變的快照）。在背景執行緒以
 * {@link #of(Vault)} 建立（2,000 筆約 0.2 秒），每次編輯、存檔、合併後重建；畫面只讀這份，不直接碰 {@link Vault}
 * （{@code VaultEditor} 不是執行緒安全的，CONVENTIONS §2）。兩平台共用（T-14 從雙胞胎檔案搬來）。
 */
public final class ScreenData {

    private final EntryQuery query;
    private final Map<UUID, GroupView> groups;
    private final Map<UUID, List<GroupView>> children;
    private final Map<UUID, EntryView> entries;
    private final Map<UUID, Integer> directCounts;
    private final GroupView root;
    private final GroupView recycleBin;

    private ScreenData(List<EntryView> entryList, List<GroupView> groupList) {
        this.query = new EntryQuery(entryList, groupList);
        Map<UUID, GroupView> byId = new LinkedHashMap<>();
        Map<UUID, List<GroupView>> kids = new HashMap<>();
        GroupView top = null;
        GroupView bin = null;
        for (GroupView g : groupList) {
            byId.put(g.uuid(), g);
            if (g.parentId() == null) {
                top = g;
            } else {
                kids.computeIfAbsent(g.parentId(), k -> new ArrayList<>()).add(g);
            }
            if (g.recycleBin()) {
                bin = g;
            }
        }
        if (top == null) {
            throw new IllegalArgumentException("保管庫沒有根群組");
        }
        Map<UUID, EntryView> e = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (EntryView v : entryList) {
            e.put(v.uuid(), v);
            counts.merge(v.groupId(), 1, Integer::sum);
        }
        this.groups = Collections.unmodifiableMap(byId);
        this.children = kids;
        this.entries = e;
        this.directCounts = counts;
        this.root = top;
        this.recycleBin = bin;
    }

    /**
     * @param vault 開啟中的保管庫（在背景執行緒呼叫）
     * @return 目前內容的快照
     */
    public static ScreenData of(Vault vault) {
        return new ScreenData(vault.entries(), vault.groups());
    }

    /**
     * @param entries 條目
     * @param groups  群組（根群組在第一個，依前序）
     * @return 快照（測試用）
     */
    public static ScreenData of(List<EntryView> entries, List<GroupView> groups) {
        return new ScreenData(entries, groups);
    }

    /** @return 查詢（清單、搜尋、條目數、群組路徑） */
    public EntryQuery query() {
        return query;
    }

    /** @return 根群組（名稱就是保管庫名稱） */
    public GroupView root() {
        return root;
    }

    /** @return 資源回收桶；檔案還沒有回收桶時（還沒刪過東西）為空 */
    public Optional<GroupView> recycleBin() {
        return Optional.ofNullable(recycleBin);
    }

    /**
     * @param groupId 群組
     * @return 直接的子群組（依檔案中的順序；desktop 的群組樹把資源回收桶另外放在最下面）
     */
    public List<GroupView> children(UUID groupId) {
        return children.getOrDefault(groupId, Collections.<GroupView>emptyList());
    }

    /**
     * @param groupId 群組
     * @return 直接放在這個群組的條目數（不含子群組；android 首頁群組清單的數字＝點進去看到的筆數，D-69）
     */
    public int directCount(UUID groupId) {
        return directCounts.getOrDefault(groupId, 0);
    }

    /**
     * @param groupId 群組
     * @return 群組；找不到時為空
     */
    public Optional<GroupView> group(UUID groupId) {
        return Optional.ofNullable(groupId == null ? null : groups.get(groupId));
    }

    /**
     * @param entryId 條目（含資源回收桶裡的）
     * @return 條目；找不到時為空
     */
    public Optional<EntryView> entry(UUID entryId) {
        return Optional.ofNullable(entryId == null ? null : entries.get(entryId));
    }

    /**
     * @param groupId 群組
     * @return 是資源回收桶本身或在它裡面
     */
    public boolean inRecycleBin(UUID groupId) {
        for (GroupView g = groups.get(groupId); g != null; g = g.parentId() == null ? null : groups.get(g.parentId())) {
            if (g.recycleBin()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return 可以放條目的群組（資源回收桶以外，依前序），編輯畫面的「群組」下拉選單用
     */
    public List<GroupView> activeGroups() {
        List<GroupView> out = new ArrayList<>();
        for (GroupView g : groups.values()) {
            if (!inRecycleBin(g.uuid())) {
                out.add(g);
            }
        }
        return List.copyOf(out);
    }

    /** @return 保管庫裡（資源回收桶以外）完全沒有條目 */
    public boolean vaultEmpty() {
        return query.all().isEmpty();
    }
}
