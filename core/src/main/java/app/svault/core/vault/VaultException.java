package app.svault.core.vault;

/**
 * 開啟、建立或序列化保管庫時預期內的失敗（CONVENTIONS §3）。平台外殼依子類別顯示繁體中文訊息。
 *
 * <p>例外訊息只放類型與非機密的上下文，不放主密碼、條目內容或函式庫的原始訊息
 * （函式庫的訊息可能夾帶解密到一半的內容），因此也不保留 cause 的訊息文字。
 */
public abstract class VaultException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 非機密的說明（給日誌與開發者看，不直接顯示給使用者）
     */
    protected VaultException(String message) {
        super(message);
    }
}
