package app.svault.core.module;

import app.svault.core.vault.EntryView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 外殼給功能模組的唯讀保管庫查詢（T-31；第一個使用者是 AF 的 {@code SVaultAutofillService}）。只有查詢，沒有寫入、
 * 拿不到主密碼與金鑰；查到的 {@link EntryView} 是不可變的快照。android 從 {@code ModuleHost.of(context).unlockedEntries()}
 * 取得，上鎖或內容還在載入時拿不到。
 *
 * <p><b>一次解鎖一份</b>：每個方法都查「取得時那次解鎖」目前的內容（編輯、存檔、合併後自動換新）。那次解鎖結束（上鎖、
 * 還原後換了內容）之後，{@link #isOpen()} 變成 {@code false}，查詢一律回傳空的結果（空清單、{@code Optional.empty()}、
 * 空字串），不丟例外，之後再解鎖也不會恢復——要再向 {@code ModuleHost} 取一次。模組不要把查到的條目（含密碼）留到
 * 這次請求或畫面結束之後。
 *
 * <p>可以在任何執行緒呼叫；不做 I/O，查詢只讀記憶體裡的快照（2,000 筆的搜尋在毫秒等級）。
 */
public interface UnlockedEntries {

    /**
     * @return 取得時的那次解鎖還在；{@code false} 時所有查詢都是空的結果
     */
    boolean isOpen();

    /**
     * @return 全部條目（不含資源回收桶），依名稱排序；已結束時為空清單
     */
    List<EntryView> all();

    /**
     * @param keyword 關鍵字（名稱、帳號、網址，不分大小寫；空白時等於 {@link #all()}）
     * @return 符合的條目（不含資源回收桶）；已結束時為空清單
     */
    List<EntryView> search(String keyword);

    /**
     * 自動填入的候選條目（AC-10-3），規則同 {@code EntryQuery#autofill}。
     *
     * @param packageName 請求來源 App 的套件名稱
     * @param webDomain   網頁的網域；不是網頁時 {@code null}
     * @return 最多 {@code EntryQuery.AUTOFILL_LIMIT} 筆，主機相同的在前；已結束時為空清單
     */
    List<EntryView> autofill(String packageName, String webDomain);

    /**
     * @param entryId 條目的 KDBX UUID
     * @return 那筆條目（含資源回收桶裡的）；不在、{@code null} 或已結束時為空
     */
    Optional<EntryView> entry(UUID entryId);

    /**
     * @param groupId 群組的 UUID
     * @return 群組路徑（顯示用，例如「網站 / 工作」）；找不到或已結束時為空字串
     */
    String groupPath(UUID groupId);
}
