package app.svault.core.vault;

import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 條目的唯讀查詢（Architecture §6）：全部、依群組、資源回收桶、關鍵字搜尋（AC-03-1）。
 *
 * <p>建立時做好排序與搜尋用的小寫字串，之後每次輸入一個字元只是線性比對（AC-03-2：2,000 筆在 300 毫秒內，
 * T-09 實測見 dev-log）。內容是建立當下的快照，保管庫修改或存檔後由畫面重新建立一個
 * （{@link #of(Vault)}）。建立後不可變，可以在任何執行緒使用。
 *
 * <p>回傳的清單依名稱排序（繁體中文 {@link Collator}，英文不分大小寫；名稱相同時依 UUID，順序固定）。
 * 資源回收桶裡的條目不出現在 {@link #all()} 與 {@link #search}，另由 {@link #recycled()} 提供。
 * 自動填入依網域或套件名稱找條目用 {@link #autofill}（T-25，規則在 {@link UrlMatch}）。
 */
public final class EntryQuery {

    /** 搜尋字串中三個欄位之間的分隔字元：使用者從搜尋框打不出來，關鍵字不會跨欄位比對成功。 */
    private static final char SEPARATOR = '\u0000';

    /** 自動填入一次最多提供的條目數。 */
    public static final int AUTOFILL_LIMIT = 10;

    private final List<EntryView> sorted;
    private final List<EntryView> active;
    private final List<EntryView> recycled;
    /** 和 {@link #active} 同順序：名稱、帳號、網址的小寫，以 {@link #SEPARATOR} 相接。不含密碼與備註。 */
    private final String[] haystack;
    private final Map<UUID, GroupView> groups = new HashMap<>();
    private final Map<UUID, List<UUID>> children = new HashMap<>();

    /**
     * @param entries 條目快照（{@link Vault#entries()}）
     * @param groups  群組快照（{@link Vault#groups()}）
     */
    public EntryQuery(List<EntryView> entries, List<GroupView> groups) {
        for (GroupView g : groups) {
            this.groups.put(g.uuid(), g);
            if (g.parentId() != null) {
                children.computeIfAbsent(g.parentId(), k -> new ArrayList<>()).add(g.uuid());
            }
        }
        Set<UUID> binGroups = new HashSet<>();
        for (GroupView g : groups) {
            if (g.recycleBin()) {
                collectSubtree(g.uuid(), binGroups);
            }
        }

        Collator collator = Collator.getInstance(Locale.TRADITIONAL_CHINESE);
        collator.setStrength(Collator.SECONDARY);
        List<Keyed> keyed = new ArrayList<>(entries.size());
        for (EntryView e : entries) {
            keyed.add(new Keyed(collator.getCollationKey(e.title()), e));
        }
        Collections.sort(keyed);
        List<EntryView> all = new ArrayList<>(keyed.size());
        List<EntryView> outside = new ArrayList<>();
        List<EntryView> inBin = new ArrayList<>();
        for (Keyed k : keyed) {
            all.add(k.entry);
            (binGroups.contains(k.entry.groupId()) ? inBin : outside).add(k.entry);
        }
        this.sorted = Collections.unmodifiableList(all);
        this.active = Collections.unmodifiableList(outside);
        this.recycled = Collections.unmodifiableList(inBin);
        this.haystack = new String[outside.size()];
        for (int i = 0; i < haystack.length; i++) {
            EntryView e = outside.get(i);
            haystack[i] = lower(e.title()) + SEPARATOR + lower(e.username()) + SEPARATOR + lower(e.url());
        }
    }

    /**
     * @param vault 開啟中的保管庫
     * @return 目前內容的查詢
     * @throws IllegalStateException 保管庫已上鎖
     */
    public static EntryQuery of(Vault vault) {
        return new EntryQuery(vault.entries(), vault.groups());
    }

    /**
     * @return 資源回收桶以外的所有條目（「全部條目」）
     */
    public List<EntryView> all() {
        return active;
    }

    /**
     * @param groupId 群組
     * @return 直接屬於這個群組的條目（不含子群組）；資源回收桶裡的群組也可以查
     */
    public List<EntryView> inGroup(UUID groupId) {
        List<EntryView> out = new ArrayList<>();
        for (EntryView e : sorted) {
            if (e.groupId().equals(groupId)) {
                out.add(e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @return 資源回收桶（含其中的子群組）裡的條目
     */
    public List<EntryView> recycled() {
        return recycled;
    }

    /**
     * 關鍵字搜尋（AC-03-1）：名稱、帳號或網址包含關鍵字就符合，不分大小寫；不搜尋密碼與備註；
     * 不含資源回收桶。關鍵字前後的空白不算。
     *
     * @param keyword 關鍵字；空白或空字串時回傳 {@link #all()}
     * @return 符合的條目；沒有時為空清單（畫面顯示 AC-03-3 的「找不到符合的條目」）
     */
    public List<EntryView> search(String keyword) {
        String k = keyword == null ? "" : lower(keyword.trim());
        if (k.isEmpty()) {
            return active;
        }
        List<EntryView> out = new ArrayList<>();
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i].contains(k)) {
                out.add(active.get(i));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 自動填入的候選條目（AC-10-3）：只列出網址符合請求的條目（規則見 {@link UrlMatch}），不含資源回收桶。
     * 主機名稱或套件名稱完全相同的在前、上層網域的在後，各自依名稱排序，最多 {@link #AUTOFILL_LIMIT} 筆。
     * 不符的條目一律不在結果裡（AC-10-5）；空清單時由模組改提供「搜尋保管庫」。
     *
     * @param packageName 請求來源 App 的套件名稱；可為 null
     * @param webDomain   網頁網域；一般 App 為 null 或空字串
     * @return 符合的條目
     */
    public List<EntryView> autofill(String packageName, String webDomain) {
        UrlMatch request = UrlMatch.forRequest(packageName, webDomain);
        List<EntryView> exact = new ArrayList<>();
        List<EntryView> parent = new ArrayList<>();
        for (EntryView e : active) {
            switch (request.levelOf(e)) {
                case EXACT -> exact.add(e);
                case PARENT_DOMAIN -> parent.add(e);
                default -> { }
            }
        }
        exact.addAll(parent);
        return List.copyOf(exact.subList(0, Math.min(exact.size(), AUTOFILL_LIMIT)));
    }

    /**
     * @param groupId 群組
     * @return 群組與所有子群組裡的條目數（AC-02-4：刪除含有條目的群組前提示）
     * @throws IllegalArgumentException 群組不存在
     */
    public int countInGroup(UUID groupId) {
        requireGroup(groupId);
        Set<UUID> subtree = new HashSet<>();
        collectSubtree(groupId, subtree);
        int count = 0;
        for (EntryView e : sorted) {
            if (subtree.contains(e.groupId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * @param groupId 群組
     * @return 從根群組到這個群組的名稱，以「 / 」相接（搜尋結果每列的群組路徑）
     * @throws IllegalArgumentException 群組不存在
     */
    public String groupPath(UUID groupId) {
        requireGroup(groupId);
        List<String> names = new ArrayList<>();
        for (GroupView g = groups.get(groupId); g != null; g = g.parentId() == null ? null : groups.get(g.parentId())) {
            names.add(g.name());
        }
        Collections.reverse(names);
        return String.join(" / ", names);
    }

    private void requireGroup(UUID groupId) {
        if (!groups.containsKey(groupId)) {
            throw new IllegalArgumentException("找不到群組：" + groupId);
        }
    }

    private void collectSubtree(UUID groupId, Set<UUID> out) {
        out.add(groupId);
        for (UUID child : children.getOrDefault(groupId, Collections.emptyList())) {
            collectSubtree(child, out);
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** 排序用：名稱的 CollationKey（只算一次），相同時依 UUID。 */
    private static final class Keyed implements Comparable<Keyed> {
        final CollationKey key;
        final EntryView entry;

        Keyed(CollationKey key, EntryView entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public int compareTo(Keyed o) {
            int c = key.compareTo(o.key);
            return c != 0 ? c : entry.uuid().compareTo(o.entry.uuid());
        }
    }
}
