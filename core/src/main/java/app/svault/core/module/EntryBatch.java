package app.svault.core.module;

import app.svault.core.shell.Texts;
import app.svault.core.vault.EntryDraft;
import java.util.List;
import java.util.Objects;

/**
 * 功能模組要一次加進保管庫的條目（T-33，經 {@code ModuleHost.addEntries}）：在根群組底下新建一個群組，把全部條目放進去，
 * 存檔一次。只能新增，不能修改或刪除既有的條目與群組。
 *
 * <p>建立時就檢查內容（群組名稱與每筆條目的名稱不能空白、筆數在 1～{@link #MAX_ENTRIES}），不合格的不會排進背景執行緒。
 * {@link #toString()} 不含任何內容（CONVENTIONS §2）。
 *
 * @param groupName 新群組的名稱（前後空白會去掉）；已有同名群組時照樣新建一個
 * @param entries   條目，依序加入（不可修改的複本）
 */
public record EntryBatch(String groupName, List<EntryDraft> entries) {

    /** 一批最多幾筆（Chrome 匯出的密碼一般是幾百到幾千筆；超過時分批或另開任務）。 */
    public static final int MAX_ENTRIES = 10_000;

    /**
     * @throws IllegalArgumentException 群組名稱空白、沒有條目、超過 {@link #MAX_ENTRIES} 筆，或有條目的名稱空白
     * @throws NullPointerException     參數或其中的條目是 {@code null}
     */
    public EntryBatch {
        Objects.requireNonNull(groupName, "groupName");
        groupName = Texts.strip(groupName);
        if (groupName.isEmpty()) {
            throw new IllegalArgumentException("群組名稱不能是空的");
        }
        entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("條目數要在 1～" + MAX_ENTRIES + "：" + entries.size());
        }
        for (int i = 0; i < entries.size(); i++) {
            if (Texts.strip(entries.get(i).title()).isEmpty()) {
                // 只報位置，不報內容
                throw new IllegalArgumentException("第 " + (i + 1) + " 筆條目的名稱是空的");
            }
        }
    }

    /** @return 筆數 */
    public int size() {
        return entries.size();
    }

    @Override
    public String toString() {
        return "EntryBatch[" + entries.size() + " 筆]";
    }
}
