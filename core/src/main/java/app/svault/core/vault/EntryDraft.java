package app.svault.core.vault;

/**
 * 新增或修改條目時，畫面送進 {@link VaultEditor} 的標準五欄（AC-02-1）。自訂欄位與附件不在 S-Vault 編輯
 * （PRD Out of Scope），修改條目時原樣保留。
 *
 * @param title    名稱（必填，不能只有空白）
 * @param username 帳號
 * @param password 密碼
 * @param url      網址
 * @param notes    備註
 */
public record EntryDraft(String title, String username, String password, String url, String notes) {

    /**
     * {@code null} 一律當成空字串，畫面不必逐欄判斷。
     */
    public EntryDraft {
        title = title == null ? "" : title;
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        url = url == null ? "" : url;
        notes = notes == null ? "" : notes;
    }

    /**
     * 由現有條目建立草稿（編輯畫面的初始值）。
     *
     * @param entry 條目快照
     * @return 內容相同的草稿
     */
    public static EntryDraft of(EntryView entry) {
        return new EntryDraft(entry.title(), entry.username(), entry.password(), entry.url(), entry.notes());
    }

    /**
     * 條目內容不能因為被印出或寫進日誌而外洩（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "EntryDraft[***]";
    }
}
