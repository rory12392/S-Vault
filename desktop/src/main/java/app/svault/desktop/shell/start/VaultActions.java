package app.svault.desktop.shell.start;

import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.RestoreResult;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import app.svault.desktop.platform.FileVaultLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 歡迎、建立、解鎖畫面背後的操作（同步執行，呼叫端放在背景執行緒；CONVENTIONS §2）。
 * 開啟與建立只透過 {@link VaultCodec}，寫入只透過 {@link SaveCoordinator}（CLAUDE.md 安全底線）。
 */
final class VaultActions {

    private final VaultCodec codec;
    private final SaveCoordinator saver;
    private final VaultSession session;
    private final RecentStore recent;
    private final ZoneId zone;

    /**
     * @param codec   建立保管庫用
     * @param saver   第一次寫出新保管庫用
     * @param session 解鎖狀態
     * @param recent  最近開啟清單
     * @param zone    最近開啟時間的顯示時區
     */
    VaultActions(VaultCodec codec, SaveCoordinator saver, VaultSession session, RecentStore recent, ZoneId zone) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.saver = Objects.requireNonNull(saver, "saver");
        this.session = Objects.requireNonNull(session, "session");
        this.recent = Objects.requireNonNull(recent, "recent");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * 正式程式的組合：建立保管庫用 AC-01-8 的參數，{@link VaultSession} 與存檔共用同一個時鐘
     * （備份檔名、最近開啟時間用裝置當地時間）。
     *
     * @param store 本機資料夾
     * @param clock 時鐘（正式程式是 {@code Clock.systemDefaultZone()}）
     * @return 操作
     */
    static VaultActions standard(LocalStore store, Clock clock) {
        return withCodec(new VaultCodec(), store, clock);
    }

    /**
     * @param codec 開啟與建立用（測試傳入低 KDF 參數的版本）
     * @param store 本機資料夾
     * @param clock 時鐘
     * @return 操作
     */
    static VaultActions withCodec(VaultCodec codec, LocalStore store, Clock clock) {
        RecentStore recent = new RecentStore(store.recentFile());
        SaveCoordinator saver = new SaveCoordinator(codec, store, recent, clock);
        VaultSession session = new VaultSession(codec, saver, recent, clock);
        return new VaultActions(codec, saver, session, recent, clock.getZone());
    }

    /** @return 最近開啟清單（最新的在前） */
    List<RecentItem> recent() {
        List<RecentItem> out = new ArrayList<>();
        for (RecentVault v : recent.list()) {
            out.add(RecentItem.of(v, zone));
        }
        return List.copyOf(out);
    }

    /**
     * 從最近開啟清單移除（不刪檔）。
     *
     * @param item 清單列
     * @throws IOException 寫不進 {@code recent.json} 時
     */
    void forget(RecentItem item) throws IOException {
        recent.remove(item.location());
    }

    /**
     * 解鎖（AC-01-3、AC-01-7、AC-06-4）。失敗時檔案不會被改動：開啟只讀取位元組。
     *
     * @param file     保管庫檔案
     * @param password 主密碼（UTF-8）；這裡不清零，呼叫端用完清零
     * @throws VaultException 讀不到、主密碼錯誤、加密設定不支援或損壞
     */
    void unlock(Path file, byte[] password) throws VaultException {
        unlock(file, password, false);
    }

    /**
     * @param file            保管庫檔案
     * @param password        主密碼（UTF-8）；這裡不清零，呼叫端用完清零
     * @param costlyConfirmed 使用者已確認成本過高的金鑰衍生（C-4）
     * @throws VaultException 同 {@link #unlock(Path, byte[])}，另有沒確認時的 {@code CostlyKdfException}
     */
    void unlock(Path file, byte[] password, boolean costlyConfirmed) throws VaultException {
        session.unlock(new FileVaultLocation(file), password, costlyConfirmed);
    }

    /**
     * 建立新的保管庫並直接解鎖（完成後不需再輸入一次）。
     *
     * <p>經 {@link SaveCoordinator} 寫出第一版（暫存檔＋讀回驗證＋原子取代，AC-09-3），再由
     * {@link VaultSession#adopt} 接手剛建立的保管庫：不再以主密碼重開一次（少一次 KDF，D-51），
     * {@code recent.json} 與遠端雜湊照常記下。接手後由 {@code VaultSession} 負責上鎖。
     *
     * @param name     保管庫名稱（也是根群組名稱）
     * @param file     新檔案的位置（呼叫端已確認不存在）
     * @param password 主密碼（UTF-8）；這裡不清零，呼叫端用完清零
     * @throws VaultException 寫出失敗；這時不會留下新檔案，建立的保管庫也已上鎖
     */
    void create(String name, Path file, byte[] password) throws VaultException {
        FileVaultLocation location = new FileVaultLocation(file);
        Vault created = codec.create(name.strip(), password);
        SaveResult written;
        try {
            written = saver.save(created, location);
        } catch (VaultException | RuntimeException e) {
            // 沒有交給 VaultSession 的保管庫要自己上鎖（清零金鑰材料）
            created.close();
            throw e;
        }
        session.adopt(created, location, written.remoteHash());
    }

    /**
     * 解鎖中的備份清單（AC-09-2）。
     *
     * @return 開啟中保管庫的備份，最新的在前
     * @throws VaultIoException 無法列出備份資料夾
     */
    List<BackupStore.Backup> backups() throws VaultIoException {
        return list(session.vault().id());
    }

    /**
     * 上鎖中的備份清單（檔案損壞時，AC-09-5）：損壞的檔案讀不到保管庫 ID，以 {@code recent.json} 記下的 ID 找。
     *
     * @param file 保管庫檔案
     * @return 這個檔案的備份，最新的在前；沒開過這個檔案時為空
     * @throws VaultIoException 無法列出備份資料夾
     */
    List<BackupStore.Backup> backupsOf(Path file) throws VaultIoException {
        Optional<RecentVault> known = recent.find(new FileVaultLocation(file).persistentId());
        return known.isPresent() ? list(known.get().vaultId()) : List.of();
    }

    private List<BackupStore.Backup> list(UUID vaultId) throws VaultIoException {
        try {
            return saver.listBackups(vaultId);
        } catch (IOException e) {
            throw new VaultIoException(VaultIoException.Reason.IO, "無法列出備份：" + e.getClass().getName());
        }
    }

    /**
     * 解鎖中還原（AC-09-2）：開啟中的保管庫換成還原後的版本（{@link VaultSession#restore}），不經過上鎖。
     *
     * @param backup 要還原的備份
     * @throws VaultException 還原失敗；檔案維持原狀
     */
    void restore(BackupStore.Backup backup) throws VaultException {
        session.restore(backup);
    }

    /**
     * 上鎖中還原並解鎖（AC-09-5）：用主密碼解開選定的備份確認可用，才備份目前的檔案並寫回，接著直接解鎖。
     * 主密碼錯誤時什麼都不寫。
     *
     * @param file     保管庫檔案
     * @param backup   要還原的備份
     * @param password 主密碼（UTF-8）；這裡不清零，呼叫端用完清零
     * @throws VaultException 主密碼錯誤、備份無法使用或寫回失敗；檔案維持原狀
     */
    void restoreAndUnlock(Path file, BackupStore.Backup backup, byte[] password) throws VaultException {
        FileVaultLocation location = new FileVaultLocation(file);
        RestoreResult restored = saver.restoreWithPassword(password, location, backup);
        session.adopt(restored.vault(), location, restored.remoteHash());
    }

    /** @return 解鎖狀態 */
    VaultSession session() {
        return session;
    }
}
