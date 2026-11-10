package app.svault.android.shell.entries;

import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import java.util.UUID;

/**
 * 編輯畫面目前的欄位值：名稱必填、與開始時比較有沒有修改（離開時「捨棄修改？」）。
 *
 * <p>畫面重建（旋轉、深色模式切換）時放在 ViewModel 裡帶過去，不放進 {@code onSaveInstanceState} 的 Bundle：
 * Bundle 可能被系統寫到磁碟，條目內容不寫暫存檔（CLAUDE.md 安全底線）。
 *
 * @param title    名稱
 * @param username 帳號
 * @param password 密碼
 * @param url      網址
 * @param notes    備註
 * @param group    群組
 */
record EntryForm(String title, String username, String password, String url, String notes, UUID group) {

    /**
     * @param entry 要編輯的條目
     * @return 條目目前的值
     */
    static EntryForm of(EntryView entry) {
        return new EntryForm(entry.title(), entry.username(), entry.password(), entry.url(), entry.notes(),
                entry.groupId());
    }

    /**
     * @param group 新增時預設的群組
     * @return 新增條目的初始值（全部空白）
     */
    static EntryForm blank(UUID group) {
        return new EntryForm("", "", "", "", "", group);
    }

    /** @return 名稱是空的或只有空白（「名稱不能是空的」） */
    boolean nameMissing() {
        return title.trim().isEmpty();
    }

    /**
     * @param now 目前的欄位值
     * @return 和這份（開始編輯時的值）有任何不同
     */
    boolean dirtyComparedTo(EntryForm now) {
        return !equals(now);
    }

    /** @return 送進 {@code VaultEditor} 的標準五欄 */
    EntryDraft draft() {
        return new EntryDraft(title, username, password, url, notes);
    }

    /** 條目內容不能因為被印出或寫進日誌而外洩（CONVENTIONS §2）。 */
    @Override
    public String toString() {
        return "EntryForm[]";
    }
}
