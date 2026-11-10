package app.svault.core.vault;

/**
 * 檔案不是可讀的 KDBX 4（不是 KDBX、被截斷、標頭或資料區塊的雜湊不符、內容無法解析）。
 * Android 依此顯示「檔案可能在上次存檔時損壞」（Architecture §5、AC-09-5）。
 */
public final class CorruptVaultException extends VaultException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason 非機密的原因，例如「不是 KDBX 檔案」「內容無法解析：EOFException」
     */
    public CorruptVaultException(String reason) {
        super("檔案無法讀取：" + reason);
    }
}
