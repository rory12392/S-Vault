package app.svault.android.platform;

import app.svault.android.R;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.CorruptBackupException;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.CostlyKdfException;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.VaultException;
import app.svault.core.vault.WrongPasswordException;
import java.io.IOException;
import java.util.Optional;

/**
 * 開啟保管庫失敗時畫面要顯示的原因與行動按鈕（畫面在 T-08）。
 *
 * <p>損壞偵測（AC-09-5）：解析失敗且不是主密碼錯誤＝{@link CorruptVaultException}，例如覆寫途中中斷留下被截斷的檔案。
 * 這時提示「檔案可能在上次存檔時損壞」；這個保管庫在本機有備份時給「從備份還原」（開 {@code BackupActivity}，T-12），
 * 沒有備份時給「重新選擇檔案」。
 *
 * @param kind       原因分類
 * @param messageRes 訊息字串資源
 * @param messageArg 訊息的參數（不支援的加密設定名稱）；沒有時為 {@code null}
 * @param action     行動按鈕
 */
public record OpenFailure(Kind kind, int messageRes, String messageArg, Action action) {

    /** 原因分類。 */
    public enum Kind {
        /** 檔案不存在或已被刪除（AC-06-4）。 */
        NOT_FOUND,
        /** 長期權限被撤銷（AC-06-4）。 */
        ACCESS_DENIED,
        /** 離線且沒有本機快取，或其他讀取失敗（AC-06-4）。 */
        UNAVAILABLE,
        /** 主密碼錯誤（AC-01-3）。 */
        WRONG_PASSWORD,
        /** 不支援的加密設定（AC-01-7）。 */
        UNSUPPORTED,
        /** 金鑰衍生成本超過門檻，要先問使用者（C-4，D-186）；{@code messageArg} 是成本倍數（四捨五入的整數字串）。 */
        COSTLY,
        /** 檔案可能損壞（AC-09-5）。 */
        CORRUPT,
        /** 還原時選定的備份解不開或不是這個保管庫的（T-12）。 */
        BACKUP_UNUSABLE,
        /** 還原時讀不到或寫不進去（T-12）；檔案維持原狀。 */
        RESTORE_FAILED
    }

    /** 行動按鈕。 */
    public enum Action {
        /** 不顯示按鈕（使用者在原畫面重試）。 */
        NONE,
        /** 「重新選擇檔案」。 */
        RESELECT_FILE,
        /** 「從備份還原」（T-12）。 */
        RESTORE_BACKUP,
        /** 「選擇其他版本」：還原失敗後重新開備份清單（T-12）。 */
        OTHER_BACKUP
    }

    /**
     * 還原並解鎖（AC-09-5）失敗時的說明。主密碼錯誤同一般解鎖（欄位下方）；其他一律附「選擇其他版本」。
     *
     * @param e {@code SaveCoordinator.restoreWithPassword} 或 {@code VaultSession.adopt} 丟出的例外
     * @return 畫面要顯示的說明
     */
    public static OpenFailure ofRestore(VaultException e) {
        if (e instanceof WrongPasswordException) {
            return new OpenFailure(Kind.WRONG_PASSWORD, R.string.sv_open_wrong_password, null, Action.NONE);
        }
        if (e instanceof CorruptBackupException) {
            return new OpenFailure(Kind.BACKUP_UNUSABLE, R.string.restore_error_backup, null, Action.OTHER_BACKUP);
        }
        return new OpenFailure(Kind.RESTORE_FAILED, R.string.restore_error_failed, null, Action.OTHER_BACKUP);
    }

    /**
     * @param e          {@code VaultSession.unlock} 丟出的例外
     * @param hasBackups 這個保管庫在本機有備份（{@link #hasBackups}）
     * @return 畫面要顯示的說明
     */
    public static OpenFailure of(VaultException e, boolean hasBackups) {
        if (e instanceof VaultIoException) {
            switch (((VaultIoException) e).reason()) {
                case NOT_FOUND:
                    return new OpenFailure(Kind.NOT_FOUND, R.string.sv_open_not_found, null, Action.RESELECT_FILE);
                case ACCESS_DENIED:
                    return new OpenFailure(Kind.ACCESS_DENIED, R.string.sv_open_access_denied, null,
                            Action.RESELECT_FILE);
                case TOO_LARGE:
                    // 被換成超大檔案（資安總檢 L-1）：和損壞同樣處理，有備份時給「從備份還原」
                    return new OpenFailure(Kind.CORRUPT, R.string.sv_open_too_large, null,
                            hasBackups ? Action.RESTORE_BACKUP : Action.RESELECT_FILE);
                default:
                    return unavailable();
            }
        }
        if (e instanceof WrongPasswordException) {
            return new OpenFailure(Kind.WRONG_PASSWORD, R.string.sv_open_wrong_password, null, Action.NONE);
        }
        if (e instanceof UnsupportedKdfException
                && UnsupportedKdfException.TOO_COSTLY.equals(((UnsupportedKdfException) e).setting())) {
            return new OpenFailure(Kind.UNSUPPORTED, R.string.sv_open_kdf_too_costly, null, Action.NONE);
        }
        if (e instanceof UnsupportedKdfException) {
            return new OpenFailure(Kind.UNSUPPORTED, R.string.sv_open_unsupported_kdf,
                    ((UnsupportedKdfException) e).setting(), Action.NONE);
        }
        if (e instanceof CostlyKdfException) {
            return new OpenFailure(Kind.COSTLY, R.string.sv_open_costly,
                    String.valueOf(Math.round(((CostlyKdfException) e).factor())), Action.NONE);
        }
        if (e instanceof CorruptVaultException) {
            return new OpenFailure(Kind.CORRUPT, R.string.sv_open_corrupt, null,
                    hasBackups ? Action.RESTORE_BACKUP : Action.RESELECT_FILE);
        }
        // 開啟時不會出現其他例外；萬一出現也當成讀不到，讓使用者能重新選檔，不閃退
        return unavailable();
    }

    private static OpenFailure unavailable() {
        return new OpenFailure(Kind.UNAVAILABLE, R.string.sv_open_unavailable, null, Action.RESELECT_FILE);
    }

    /**
     * 損壞的檔案解不開，讀不到保管庫 ID：以 {@code recent.json} 記下的 ID 找本機備份。
     *
     * @param recent       最近開啟清單
     * @param backups      本機備份
     * @param persistentId 位置的 {@code persistentId}
     * @return 有至少一份備份；沒開過這個檔案或讀不到備份資料夾時為 {@code false}
     */
    public static boolean hasBackups(RecentStore recent, BackupStore backups, String persistentId) {
        Optional<RecentVault> known = recent.find(persistentId);
        if (!known.isPresent()) {
            return false;
        }
        try {
            return !backups.list(known.get().vaultId()).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }
}
