package app.svault.core.shell;

import app.svault.core.vault.EntryDraft;
import java.util.UUID;

/**
 * 上鎖時編輯畫面上還沒按「儲存」的內容（T-18；DESIGN §6「上鎖時依 AC-12-3 先存檔，不詢問」），交給
 * {@link VaultOps#lock} 先存再上鎖。內容是條目的欄位，{@code toString()} 不輸出。
 *
 * @param entryId   編輯中的條目；新增時 {@code null}
 * @param draft     欄位目前的值
 * @param fromGroup 編輯開始時所在的群組（新增時同 {@code group}）
 * @param group     選定的群組
 */
public record PendingEdit(UUID entryId, EntryDraft draft, UUID fromGroup, UUID group) {

    @Override
    public String toString() {
        return "PendingEdit[***]";
    }
}
