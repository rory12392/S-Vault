package app.svault.core.storage;

import app.svault.core.vault.VaultException;

/**
 * 讀寫保管庫位置（{@link VaultLocation}）或本機資料夾失敗（CONVENTIONS §3）。平台外殼依 {@link #reason()}
 * 顯示原因與「重新選擇檔案」（AC-06-4）。
 *
 * <p>訊息只放失敗類型與檔名這類非機密的上下文；不保留底層例外，避免訊息夾帶路徑以外的內容。
 */
public final class VaultIoException extends VaultException {

    private static final long serialVersionUID = 1L;

    /** 失敗的原因分類（畫面依此選擇提示文字）。 */
    public enum Reason {
        /** 檔案不存在或已被刪除。 */
        NOT_FOUND,
        /** 沒有讀寫權限（Android 的長期權限被撤銷、PC 的檔案被鎖定或唯讀）。 */
        ACCESS_DENIED,
        /** 其他 I/O 失敗（離線、磁碟已滿、寫入後讀回不符）。 */
        IO,
        /** 檔案超過 {@link VaultLocation#MAX_BYTES}，不是正常的保管庫（可能被替換）。 */
        TOO_LARGE
    }

    private final Reason reason;

    /**
     * @param reason  原因分類
     * @param message 非機密的說明，例如「寫入暫存檔失敗：AccessDeniedException」
     */
    public VaultIoException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** @return 原因分類 */
    public Reason reason() {
        return reason;
    }
}
