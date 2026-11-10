package app.svault.core.storage;

import app.svault.core.vault.VaultException;

/**
 * 存檔前的讀回驗證失敗（Architecture §5 步驟 5）：剛序列化的位元組解不開，或解開後的內容和記憶體中的保管庫不同。
 * 發生時遠端檔案沒有被動過。
 */
public final class SaveVerificationException extends VaultException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason 非機密的原因，例如「條目內容不一致」
     */
    public SaveVerificationException(String reason) {
        super("存檔前的讀回驗證失敗：" + reason);
    }
}
