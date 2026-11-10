package app.svault.android.shell.start;

import androidx.annotation.VisibleForTesting;
import app.svault.android.platform.DocumentAccess;
import app.svault.android.platform.OpenFailure;
import app.svault.android.platform.SafVaultLocation;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.RestoreResult;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.storage.VaultIoException;
import app.svault.core.storage.VaultLocation;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 歡迎、建立、解鎖畫面背後的操作（同步執行，畫面放到背景執行緒；CONVENTIONS §2）。不依賴 Android 畫面，
 * JVM 單元測試用假的文件提供者。開啟與建立只透過 {@link VaultCodec}，寫入只透過 {@link SaveCoordinator}
 * （CLAUDE.md 安全底線）；主密碼陣列一律由呼叫端清零。
 */
public final class StartActions {

    /** 建立保管庫的結果（失敗用例外）。 */
    public enum CreateOutcome {
        /** 已寫出並解鎖。 */
        CREATED,
        /** 選到的文件已經有內容：不覆寫（可能是另一個保管庫，D-52），什麼都沒做。 */
        NOT_EMPTY
    }

    private final VaultCodec codec;
    private final SaveCoordinator saver;
    private final VaultSession session;
    private final RecentStore recent;
    private final BackupStore backups;
    // 只有儀器測試會換（replaceDocuments）；volatile：測試在自己的執行緒換，畫面在背景執行緒讀
    private volatile DocumentAccess documents;

    /**
     * @param codec     建立保管庫用
     * @param saver     寫出第一版用（和 {@code session} 用同一個）
     * @param session   解鎖狀態
     * @param recent    最近開啟清單
     * @param backups   本機備份（損壞時判斷能不能從備份還原）
     * @param documents 檔案選擇器給的 Uri 的存取
     */
    public StartActions(VaultCodec codec, SaveCoordinator saver, VaultSession session, RecentStore recent,
            BackupStore backups, DocumentAccess documents) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.saver = Objects.requireNonNull(saver, "saver");
        this.session = Objects.requireNonNull(session, "session");
        this.recent = Objects.requireNonNull(recent, "recent");
        this.backups = Objects.requireNonNull(backups, "backups");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /**
     * 只給儀器測試（T-36）：換掉文件的存取。測試的假文件拿不到 SAF 的長期權限，正式的存取讀不到，解鎖畫面就解不開。
     *
     * @param replacement 測試用的存取
     * @return 換掉之前的，測試結束時換回來
     */
    @VisibleForTesting
    public DocumentAccess replaceDocuments(DocumentAccess replacement) {
        DocumentAccess previous = documents;
        documents = Objects.requireNonNull(replacement, "replacement");
        return previous;
    }

    /** @return 最近開啟清單（最新的在前，AC-06-3） */
    public List<RecentVault> recent() {
        return recent.list();
    }

    /**
     * 從最近開啟清單重建位置，不需要重新選檔（AC-06-2）。不做 I/O。
     *
     * @param item 清單列
     * @return 位置
     */
    public SafVaultLocation location(RecentVault item) {
        return SafVaultLocation.reopen(documents, item.location(), item.displayName());
    }

    /**
     * 使用者剛從檔案選擇器選了檔案：取得長期權限。
     *
     * @param uri 選擇器回傳的 Uri 字串
     * @return 位置
     * @throws VaultIoException 拿不到長期權限或文件不存在（AC-06-4）
     */
    public SafVaultLocation pick(String uri) throws VaultIoException {
        return SafVaultLocation.adopt(documents, uri);
    }

    /**
     * 從最近開啟清單移除並放棄長期權限；不刪檔。
     *
     * @param item 清單列
     * @throws IOException 寫不進 {@code recent.json}
     */
    public void forget(RecentVault item) throws IOException {
        recent.remove(item.location());
        location(item).forget();
    }

    /**
     * 輸入主密碼前先確認檔案還在、權限還在（AC-06-4），讓使用者不必白打一次主密碼。
     * 離線時多半仍查得到名稱，要到解鎖讀內容時才知道。
     *
     * @param location 位置
     * @return 讀不到的原因；看起來沒問題時為 {@code null}
     */
    public OpenFailure check(SafVaultLocation location) {
        try {
            if (location.exists()) {
                return null;
            }
            return OpenFailure.of(new VaultIoException(VaultIoException.Reason.NOT_FOUND, "查詢"), false);
        } catch (VaultIoException e) {
            return OpenFailure.of(e, false);
        }
    }

    /**
     * 解鎖（AC-01-3、AC-01-7、AC-06-4）。失敗時檔案不會被改動：開啟只讀取位元組。
     *
     * @param location 位置
     * @param password 主密碼（UTF-8）；這裡不清零
     * @throws VaultException 讀不到、主密碼錯誤、加密設定不支援或損壞
     */
    public void unlock(SafVaultLocation location, byte[] password) throws VaultException {
        unlock(location, password, false);
    }

    /**
     * @param location        位置
     * @param password        主密碼（UTF-8）；這裡不清零
     * @param costlyConfirmed 使用者已確認成本過高的金鑰衍生（C-4）
     * @throws VaultException 同 {@link #unlock(SafVaultLocation, byte[])}，另有沒確認時的 {@code CostlyKdfException}
     */
    public void unlock(SafVaultLocation location, byte[] password, boolean costlyConfirmed) throws VaultException {
        session.unlock(location, password, costlyConfirmed);
    }

    /**
     * @param e        開啟時的例外
     * @param location 開啟的位置（損壞時用 {@code recent.json} 記的保管庫 ID 找備份）
     * @return 畫面要顯示的原因與按鈕
     */
    public OpenFailure failure(VaultException e, VaultLocation location) {
        return OpenFailure.of(e, OpenFailure.hasBackups(recent, backups, location.persistentId()));
    }

    /**
     * 解鎖中的備份清單（AC-09-2）。
     *
     * @return 開啟中保管庫的備份，最新的在前
     * @throws IOException 無法列出備份資料夾
     */
    public List<BackupStore.Backup> backups() throws IOException {
        return backups.list(session.vault().id());
    }

    /**
     * 上鎖中的備份清單（檔案損壞時，AC-09-5）：損壞的檔案讀不到保管庫 ID，以 {@code recent.json} 記下的 ID 找。
     *
     * @param persistentId 位置的 {@code persistentId}（Uri 字串）
     * @return 這個檔案的備份，最新的在前；沒開過這個檔案時為空
     * @throws IOException 無法列出備份資料夾
     */
    public List<BackupStore.Backup> backupsOf(String persistentId) throws IOException {
        Optional<RecentVault> known = recent.find(persistentId);
        return known.isPresent() ? backups.list(known.get().vaultId()) : Collections.emptyList();
    }

    /**
     * 解鎖中還原（AC-09-2）：開啟中的保管庫換成還原後的版本（{@link VaultSession#restore}），不經過上鎖。
     *
     * @param backup 要還原的備份
     * @throws VaultException 還原失敗；檔案維持原狀
     */
    public void restore(BackupStore.Backup backup) throws VaultException {
        session.restore(backup);
    }

    /**
     * 上鎖中還原並解鎖（AC-09-5）：用主密碼解開選定的備份確認可用，才備份目前的檔案並寫回（{@code "wt"}＋讀回比對），
     * 接著直接解鎖。主密碼錯誤時什麼都不寫。
     *
     * @param location 位置
     * @param backup   要還原的備份
     * @param password 主密碼（UTF-8）；這裡不清零
     * @throws VaultException 主密碼錯誤、備份無法使用或寫回失敗；檔案維持原狀
     */
    public void restoreAndUnlock(SafVaultLocation location, BackupStore.Backup backup, byte[] password)
            throws VaultException {
        RestoreResult restored = saver.restoreWithPassword(password, location, backup);
        session.adopt(restored.vault(), location, restored.remoteHash());
    }

    /**
     * 建立新的保管庫並直接解鎖（完成後不需再輸入一次）。
     *
     * <p>{@code uri} 是另存新檔選擇器（{@code ACTION_CREATE_DOCUMENT}）剛建立的空文件。經 {@link SaveCoordinator}
     * 寫出第一版（序列化 → 讀回驗證 → 整檔覆寫 → 讀回比對），再以 {@link VaultSession#adopt} 接手同一個
     * {@link Vault}，不再解密一次（D-51）。寫出失敗時刪掉那個空文件，Drive 上不留打不開的 {@code .kdbx}。
     *
     * @param name     保管庫名稱（也是根群組名稱）
     * @param uri      新文件的 Uri 字串
     * @param password 主密碼（UTF-8）；這裡不清零
     * @return {@link CreateOutcome#CREATED}，或文件已經有內容時 {@link CreateOutcome#NOT_EMPTY}
     * @throws VaultException 拿不到權限、寫出或讀回驗證失敗；這時維持上鎖
     */
    public CreateOutcome create(String name, String uri, byte[] password) throws VaultException {
        SafVaultLocation location = pick(uri);
        if (location.readAll().length > 0) {
            // 選擇器通常會把同名檔案改名成「名稱 (1).kdbx」；真的拿到有內容的文件時不碰它
            location.forget();
            return CreateOutcome.NOT_EMPTY;
        }
        Vault created = codec.create(name.trim(), password);
        try {
            SaveResult written = saver.save(created, new EmptyDocument(location));
            session.adopt(created, location, written.remoteHash());
            return CreateOutcome.CREATED;
        } catch (VaultException | RuntimeException e) {
            created.close();
            documents.deleteDocument(uri);
            location.forget();
            throw e;
        }
    }

    /** @return 解鎖狀態 */
    public VaultSession session() {
        return session;
    }

    /**
     * 畫面上的保管庫名稱＝檔名去掉 {@code .kdbx}。
     *
     * @param displayName {@code VaultLocation.displayName()}
     * @return 保管庫名稱
     */
    public static String title(String displayName) {
        String n = displayName.trim();
        int cut = n.length() - CreateForm.EXTENSION.length();
        if (cut > 0 && n.regionMatches(true, cut, CreateForm.EXTENSION, 0, CreateForm.EXTENSION.length())) {
            return n.substring(0, cut);
        }
        return n;
    }

    /**
     * 剛建立的空文件。{@link SaveCoordinator} 在覆寫前會把遠端目前的內容備份一份；空文件沒有東西可以備份，
     * 留一份 0 位元組的「備份」反而會讓損壞提示以為有版本可以還原（AC-09-5），所以第一次寫出時當成不存在。
     */
    private static final class EmptyDocument implements VaultLocation {

        private final VaultLocation target;

        EmptyDocument(VaultLocation target) {
            this.target = target;
        }

        @Override
        public String displayName() {
            return target.displayName();
        }

        @Override
        public String persistentId() {
            return target.persistentId();
        }

        @Override
        public boolean exists() {
            return false;
        }

        @Override
        public byte[] readAll() throws VaultIoException {
            return target.readAll();
        }

        @Override
        public void writeAll(byte[] data) throws VaultIoException {
            target.writeAll(data);
        }

        @Override
        public boolean supportsAtomicReplace() {
            return target.supportsAtomicReplace();
        }
    }
}
