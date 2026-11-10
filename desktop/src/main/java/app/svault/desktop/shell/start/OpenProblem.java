package app.svault.desktop.shell.start;

import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.CostlyKdfException;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.WrongPasswordException;

/**
 * 開啟或建立保管庫失敗時畫面要顯示什麼。只決定文字鍵與呈現方式，
 * 不顯示例外訊息與堆疊。
 *
 * @param messageKey    {@code messages.properties} 的鍵
 * @param fieldError    {@code true}＝顯示在主密碼欄下方（AC-01-3）；{@code false}＝error Banner
 * @param chooseAgain   Banner 是否附「重新選擇檔案」（AC-06-4）
 * @param restoreBackup Banner 是否附「從備份還原」（AC-09-5；優先於 {@code chooseAgain}）
 */
record OpenProblem(String messageKey, boolean fieldError, boolean chooseAgain, boolean restoreBackup) {

    /** 主密碼錯誤（AC-01-3）。 */
    static final OpenProblem WRONG_PASSWORD = new OpenProblem("unlock.error.wrong", true, false, false);

    /** 檔案損壞、本機有這個檔案的備份（AC-09-5；由 {@link RestoreFlow#showUnlockFailure} 判斷）。 */
    static final OpenProblem CORRUPT_RESTORABLE = new OpenProblem("unlock.error.corrupt", false, false, true);

    /**
     * @param e 開啟時的例外
     * @return 對應的呈現方式；損壞時是沒有備份的版本（附「重新選擇檔案」）
     */
    static OpenProblem of(Exception e) {
        if (e instanceof WrongPasswordException) {
            return WRONG_PASSWORD;
        }
        if (e instanceof UnsupportedKdfException u && UnsupportedKdfException.TOO_COSTLY.equals(u.setting())) {
            return new OpenProblem("unlock.error.kdfLimit", false, false, false);
        }
        if (e instanceof CostlyKdfException) {
            // 解鎖畫面會先問（RestoreFlow）；走到這裡是還原或背景同步讀到比目前更貴的設定（C-4）
            return new OpenProblem("unlock.error.costly", false, false, false);
        }
        if (e instanceof UnsupportedKdfException) {
            // AC-01-7 指定的文字；欄位與按鈕保留，使用者在 KeePassXC 改完可以直接重試
            return new OpenProblem("unlock.error.kdf", false, false, false);
        }
        if (e instanceof CorruptVaultException) {
            return new OpenProblem("unlock.error.corrupt", false, true, false);
        }
        if (e instanceof VaultIoException io) {
            return unreadable(io.reason());
        }
        return new OpenProblem("unlock.error.unknown", false, true, false);
    }

    /**
     * 檔案讀不到（AC-06-4）。
     *
     * @param reason 原因
     * @return 對應的呈現方式
     */
    static OpenProblem unreadable(VaultIoException.Reason reason) {
        String key = switch (reason) {
            case NOT_FOUND -> "open.error.notFound";
            case ACCESS_DENIED -> "open.error.denied";
            case IO -> "open.error.offline";
            case TOO_LARGE -> "open.error.tooLarge";
        };
        return new OpenProblem(key, false, true, false);
    }
}
