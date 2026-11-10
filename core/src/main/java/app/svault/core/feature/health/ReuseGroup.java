package app.svault.core.feature.health;

import app.svault.core.vault.EntryView;
import java.util.List;
import java.util.UUID;

/**
 * 共用同一個密碼的一組條目（AC-13-1 的「重複使用」）。只保留條目的快照，不另外保存密碼或雜湊：
 * 畫面只需要條目名稱與「與 n 筆條目使用相同密碼」，跳到條目用 UUID。
 *
 * @param entries 共用同一密碼的條目，至少 2 筆，依名稱排序（{@code EntryQuery} 的順序）
 */
public record ReuseGroup(List<EntryView> entries) {

    /**
     * 複製成不可修改的清單。
     *
     * @throws IllegalArgumentException 少於 2 筆
     */
    public ReuseGroup {
        entries = List.copyOf(entries);
        if (entries.size() < 2) {
            throw new IllegalArgumentException("重複使用的一組至少要有 2 筆條目");
        }
    }

    /**
     * @param entryId 這一組裡的條目
     * @return 和它使用相同密碼的其他條目數（畫面的「與 n 筆條目使用相同密碼」）
     * @throws IllegalArgumentException 條目不在這一組
     */
    public int othersCount(UUID entryId) {
        for (EntryView e : entries) {
            if (e.uuid().equals(entryId)) {
                return entries.size() - 1;
            }
        }
        throw new IllegalArgumentException("條目不在這一組：" + entryId);
    }

    /**
     * 只輸出數量：預設的 record 字串會帶出條目清單，雖然 {@link EntryView} 已經遮罩，這裡仍不輸出任何條目資訊。
     */
    @Override
    public String toString() {
        return "ReuseGroup[" + entries.size() + " 筆]";
    }
}
