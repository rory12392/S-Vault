package app.svault.core.vault;

/**
 * 主密碼錯誤（AC-01-3）。只代表「這組主密碼解不開這個檔案」，檔案本身沒有被讀寫以外的任何動作。
 */
public final class WrongPasswordException extends VaultException {

    private static final long serialVersionUID = 1L;

    /** 建立例外。 */
    public WrongPasswordException() {
        super("主密碼錯誤");
    }
}
