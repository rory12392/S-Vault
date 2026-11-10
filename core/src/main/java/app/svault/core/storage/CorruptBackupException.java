package app.svault.core.storage;

import app.svault.core.vault.VaultException;

/**
 * 選定的備份檔無法使用：解不開（截斷、內容被改動）或不是這個保管庫的備份。發生時遠端沒有被動過，
 * 也沒有新增備份（Architecture §5「備份還原」）。
 */
public final class CorruptBackupException extends VaultException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason 非機密的原因，例如「解不開」
     */
    public CorruptBackupException(String reason) {
        super("備份檔無法使用：" + reason);
    }
}
