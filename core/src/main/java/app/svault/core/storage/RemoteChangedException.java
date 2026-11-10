package app.svault.core.storage;

import app.svault.core.vault.VaultException;

/**
 * 寫回遠端前重讀時，遠端已經不是合併時讀到的那一份（另一台裝置或同步程式剛寫入；C-2）。遠端沒有被改動；
 * {@code SyncService} 收到後重新讀取、合併再寫。
 */
public final class RemoteChangedException extends VaultException {

    private static final long serialVersionUID = 1L;

    /** 建立例外。 */
    public RemoteChangedException() {
        super("遠端在合併之後又被改過");
    }
}
