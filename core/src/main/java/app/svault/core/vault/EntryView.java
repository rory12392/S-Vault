package app.svault.core.vault;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 條目的唯讀快照。由 {@link Vault#entries()} 依當下內容建立，之後保管庫再改也不會變。
 *
 * <p>Java 字串無法保證從記憶體清除（Architecture §10）；上鎖時丟棄整個 {@link Vault} 與這些快照的參照。
 *
 * @param uuid           條目 UUID
 * @param groupId        所在群組的 UUID
 * @param title          標題
 * @param username       帳號
 * @param password       密碼
 * @param url            網址
 * @param notes          備註
 * @param customFields   標準五欄以外的字串欄位（依名稱排序，不可修改）
 * @param attachmentNames 附件名稱（依檔案中的順序，不可修改）
 * @param tags           標籤原文（KDBX 以分號或逗號分隔；沒有時為空字串）
 * @param created        建立時間
 * @param lastModified   最後修改時間
 * @param historyCount   歷史版本數（KDBX 內建的 entry History）
 */
public record EntryView(UUID uuid, UUID groupId, String title, String username, String password, String url,
                        String notes, Map<String, String> customFields, List<String> attachmentNames, String tags,
                        Instant created, Instant lastModified, int historyCount) {

    /**
     * 複製集合成不可修改的版本（自訂欄位依名稱排序，畫面與比對的順序固定）。
     */
    public EntryView {
        customFields = Collections.unmodifiableMap(new TreeMap<>(customFields));
        attachmentNames = List.copyOf(attachmentNames);
    }

    /**
     * 只輸出 UUID：條目的任何內容都不能因為被印出或寫進日誌而外洩（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "EntryView[" + uuid + "]";
    }
}
