package app.svault.core.feature.importer;

import java.util.List;
import java.util.UUID;

/**
 * 要匯入的一列，以及它和保管庫中哪些既有條目可能重複（AC-14-4）。
 *
 * @param row         CSV 的一列
 * @param duplicateOf 網址的主機名稱與帳號都相同的既有條目；不重複時為空清單
 */
public record ImportCandidate(ChromeCsvRow row, List<UUID> duplicateOf) {

    /**
     * 複製成不可修改的清單。
     */
    public ImportCandidate {
        duplicateOf = List.copyOf(duplicateOf);
    }

    /**
     * @return 是否標示為「可能重複」（畫面讓使用者選擇略過或照樣匯入）
     */
    public boolean possibleDuplicate() {
        return !duplicateOf.isEmpty();
    }

    /**
     * 只輸出行號與重複的筆數，不含內容（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "ImportCandidate[line=" + row.line() + ", duplicates=" + duplicateOf.size() + "]";
    }
}
