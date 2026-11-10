package app.svault.core.storage;

import app.svault.core.vault.RestoreMarks;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import app.svault.core.vault.WrongPasswordException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 寫入保管庫的唯一入口（CLAUDE.md 安全底線；Architecture §5）。這裡做步驟 3–7（T-05）：
 * 備份 → 序列化 → 讀回驗證 → 寫回（暫存＋原子取代）→ 更新 {@code base/}、遠端雜湊、刪除 {@code pending/}。
 * 步驟 1–2（讀遠端、和 {@code base/} 比對、三方合併）在 {@code core.sync.SyncService}（T-14），合併完再呼叫
 * {@link #write}；本機資料夾的 {@code base/}、{@code pending/} 也只由這裡讀寫（檔名與升級在 {@link SyncFiles}，
 * 以保管庫 ID＋位置區分，T-27）。
 * 備份還原（T-12）走同一條路：先在記憶體中解開選定的備份確認可用 → 備份目前的遠端 → 加上還原的註記
 * （{@link RestoreMarks}，T-27）→ 寫回 → 更新本機狀態。
 *
 * <p>同步執行（KDF 不會重算，但仍有檔案 I/O），呼叫端放在背景執行緒。
 */
public final class SaveCoordinator {

    private static final Logger LOG = Logger.getLogger(SaveCoordinator.class.getName());

    /** 序列化的方式；正式程式是 {@link VaultCodec#serialize}，故障注入測試換成產生壞位元組的版本。 */
    @FunctionalInterface
    interface Serializer {

        /**
         * @param vault 開啟中的保管庫
         * @return 加密後的檔案位元組
         */
        byte[] serialize(Vault vault);
    }

    private final VaultCodec codec;
    private final Serializer serializer;
    private final LocalStore store;
    private final RecentStore recent;
    private final BackupStore backups;
    private final SyncFiles files;
    private final ConflictDraftFile drafts;
    private final Clock clock;

    /**
     * @param codec  序列化與讀回驗證用
     * @param store  本機資料夾（{@code backups/}、{@code base/}、{@code pending/}）
     * @param recent 記錄遠端雜湊
     * @param clock  備份檔名的時間
     */
    public SaveCoordinator(VaultCodec codec, LocalStore store, RecentStore recent, Clock clock) {
        this(codec, codec::serialize, store, recent, clock);
    }

    SaveCoordinator(VaultCodec codec, Serializer serializer, LocalStore store, RecentStore recent, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.store = Objects.requireNonNull(store, "store");
        this.recent = Objects.requireNonNull(recent, "recent");
        this.backups = new BackupStore(store.backupsDir(), clock);
        this.files = new SyncFiles(store, recent);
        this.drafts = new ConflictDraftFile(codec, serializer, clock);
        this.clock = clock;
    }

    /**
     * 把開啟中的保管庫存回 {@code location}，不和遠端比對、不合併（建立保管庫寫出第一版用；解鎖後的存檔走
     * {@code SyncService}，遠端有變更時先合併）。任何一步失敗時遠端維持原狀（原子取代的位置）。
     *
     * @param vault    開啟中的保管庫
     * @param location 遠端位置
     * @return 結果
     * @throws VaultIoException          讀遠端、備份或寫回失敗（遠端沒有被改動）
     * @throws SaveVerificationException 讀回驗證失敗（遠端沒有被改動）
     * @throws VaultException            其他保管庫錯誤
     * @throws IllegalStateException     保管庫已上鎖
     */
    public SaveResult save(Vault vault, VaultLocation location) throws VaultException {
        if (vault.isClosed()) {
            throw new IllegalStateException("保管庫已上鎖，不能存檔");
        }
        return write(vault, location, location.exists() ? location.readAll() : null);
    }

    /**
     * 步驟 3–7：以 {@code vault} 取代遠端。步驟 1–2（讀遠端、比對、合併）由 {@code SyncService} 做完後呼叫這裡，
     * 備份的是它在步驟 1 讀到、拿來合併的那一份位元組。
     *
     * @param vault         要寫出的保管庫（沒有合併時是本機那份，合併後是套用了合併結果的遠端那份）
     * @param location      遠端位置
     * @param currentRemote 遠端目前的位元組（備份用）；遠端不存在時 {@code null}
     * @return 結果
     * @throws VaultIoException          備份或寫回失敗（遠端沒有被改動）
     * @throws SaveVerificationException 讀回驗證失敗（遠端沒有被改動）
     * @throws VaultException            其他保管庫錯誤
     * @throws IllegalStateException     保管庫已上鎖
     */
    public SaveResult write(Vault vault, VaultLocation location, byte[] currentRemote) throws VaultException {
        if (vault.isClosed()) {
            throw new IllegalStateException("保管庫已上鎖，不能存檔");
        }
        // 3. 備份目前的遠端（AC-09-1）。備份不了就不覆寫：寧可存檔失敗，也不要在沒有退路時動原檔
        Path backup = null;
        if (currentRemote != null) {
            try {
                backup = backups.backup(vault.id(), currentRemote);
            } catch (IOException e) {
                throw new VaultIoException(VaultIoException.Reason.IO, "無法寫入備份：" + e.getClass().getName());
            }
        }

        // 4. 序列化（沿用已衍生的金鑰，0 次 KDF；每次換新主種子與 IV，T-04）
        byte[] bytes = serializer.serialize(vault);

        // 5. 讀回驗證：用同一把金鑰解開剛序列化的位元組（0 次 KDF），內容要和記憶體中的完全相同
        ReadBack.verify(codec, bytes, vault);

        // 6. 寫回遠端（PC：暫存檔＋讀回比對＋原子取代；Android：T-06）。取代前再讀一次：合併之後另一台或同步程式
        // 剛寫入時不蓋掉它，交給 SyncService 重新合併（C-2）。VaultLocation 沒有條件式寫入，SAF／雲端資料夾在這次
        // 重讀與寫入之間仍有很短的空檔（SECURITY T7）
        ensureUnchanged(location, currentRemote);
        location.writeAll(bytes);

        // 7. 本機狀態。遠端已經寫好了，這裡失敗不算存檔失敗：只回報，下次解鎖會當成遠端有變更再比對一次
        String hash = Hashes.sha256(bytes);
        boolean localUpdated = updateLocalState(vault, location, bytes, hash);
        return new SaveResult(hash, backup, localUpdated);
    }

    /**
     * @param vaultId 保管庫 ID
     * @return 這個保管庫的備份，最新的在前（AC-09-2 的清單）
     * @throws IOException 無法列出備份資料夾
     */
    public List<BackupStore.Backup> listBackups(UUID vaultId) throws IOException {
        return backups.list(vaultId);
    }

    /**
     * 解鎖中還原（AC-09-2）：用開啟中保管庫的金鑰解開選定的備份（0 次 KDF），確認可用後才備份目前的遠端、
     * 寫回備份位元組。{@code current} 不會被修改或上鎖；換成回傳的保管庫由呼叫端（{@code VaultSession}）負責。
     *
     * @param current  開啟中的保管庫（提供金鑰，也決定只能還原同一個保管庫的備份）
     * @param location 遠端位置
     * @param backup   要還原的備份（{@link #listBackups} 的一筆）
     * @return 結果，含以還原內容開啟的保管庫
     * @throws CorruptBackupException 備份解不開或不是這個保管庫的（遠端沒有被改動）
     * @throws VaultIoException       讀備份、讀遠端、備份遠端或寫回失敗（遠端沒有被改動）
     * @throws VaultException         其他保管庫錯誤
     * @throws IllegalStateException  {@code current} 已上鎖
     */
    public RestoreResult restore(Vault current, VaultLocation location, BackupStore.Backup backup)
            throws VaultException {
        if (current.isClosed()) {
            throw new IllegalStateException("保管庫已上鎖，不能還原");
        }
        byte[] bytes = readBackup(backup);
        Vault restored;
        try {
            // 使用者自己選的本機備份：成本門檻不擋（Codex M-04 審查 B4，同 restoreWithPassword）
            restored = codec.openWithKeyOf(bytes, current, true);
        } catch (VaultException e) {
            // 金鑰是對的（current 開著），解不開就是備份檔本身壞了；改壞的位元組常被判成金鑰不符
            throw new CorruptBackupException("解不開：" + e.getClass().getSimpleName());
        }
        return writeRestored(restored, current.id(), location, bytes, current);
    }

    /**
     * 上鎖中還原（AC-09-5：檔案損壞、解鎖失敗時）：用主密碼解開選定的備份（1 次 KDF），確認可用後才備份目前的遠端
     * （損壞的檔案也備份，不丟任何東西）、寫回備份位元組。主密碼打錯時什麼都不寫。
     *
     * @param password 主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @param location 遠端位置
     * @param backup   要還原的備份；它所在的資料夾名稱就是保管庫 ID（{@code backups/<保管庫ID>/}）
     * @return 結果，含以還原內容開啟的保管庫（交給 {@code VaultSession.adopt}）
     * @throws WrongPasswordException 主密碼錯誤（遠端沒有被改動）
     * @throws CorruptBackupException 備份解不開或不是這個保管庫的（遠端沒有被改動）
     * @throws VaultIoException       讀備份、讀遠端、備份遠端或寫回失敗（遠端沒有被改動）
     * @throws VaultException         其他保管庫錯誤
     */
    public RestoreResult restoreWithPassword(byte[] password, VaultLocation location, BackupStore.Backup backup)
            throws VaultException {
        byte[] bytes = readBackup(backup);
        Vault restored;
        try {
            // 本機的備份是這台自己存的（使用者當時已接受它的金鑰衍生成本），使用者也明確選了它：成本門檻不再問，
            // 也不能把「要先確認」當成備份損壞（Codex M-04 審查 B4）
            restored = codec.open(bytes, password, true);
        } catch (WrongPasswordException e) {
            throw e;
        } catch (VaultException e) {
            throw new CorruptBackupException("解不開：" + e.getClass().getSimpleName());
        }
        UUID expected;
        try {
            expected = UUID.fromString(backup.file().getParent().getFileName().toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            restored.close();
            throw new CorruptBackupException("不在備份資料夾裡");
        }
        return writeRestored(restored, expected, location, bytes, null);
    }

    private static void ensureUnchanged(VaultLocation location, byte[] expected) throws VaultException {
        if (expected == null) {
            if (location.exists()) {
                throw new RemoteChangedException();
            }
            return;
        }
        if (!Arrays.equals(expected, location.readAll())) {
            throw new RemoteChangedException();
        }
    }

    private static byte[] readBackup(BackupStore.Backup backup) throws VaultIoException {
        try {
            return BoundedRead.file(backup.file(), VaultLocation.MAX_BYTES);
        } catch (NoSuchFileException e) {
            throw new VaultIoException(VaultIoException.Reason.NOT_FOUND, "備份檔不存在");
        } catch (IOException e) {
            throw new VaultIoException(VaultIoException.Reason.IO, "無法讀取備份：" + e.getClass().getName());
        }
    }

    // 還原的步驟 2–4：備份目前的遠端 → 加註記 → 寫回 → 本機狀態。和目前內容相同的備份寫回原始位元組（已在記憶體中
    // 解開確認過）；不同時加上 RestoreMarks 的註記（被移除的記進刪除紀錄、被換掉的版本推入歷史），重新序列化並讀回驗證，
    // 其他裝置才會把還原造成的刪除當成刪除，而不是「遠端被取代」（T-27）。寫入本身仍是 VaultLocation 的暫存＋讀回比對＋
    // 原子取代（PC）或 "wt"＋讀回比對（Android）
    private RestoreResult writeRestored(Vault restored, UUID expectedId, VaultLocation location, byte[] bytes,
                                        Vault current) throws VaultException {
        boolean done = false;
        Vault latest = null;
        try {
            if (!restored.id().equals(expectedId)) {
                throw new CorruptBackupException("不是這個保管庫的備份");
            }
            Path safety = null;
            byte[] remoteBytes = null;
            if (location.exists()) {
                remoteBytes = location.readAll();
                try {
                    safety = backups.backup(expectedId, remoteBytes);
                } catch (IOException e) {
                    throw new VaultIoException(VaultIoException.Reason.IO, "無法寫入備份：" + e.getClass().getName());
                }
            }
            // 目前的內容：遠端（最新的）；遠端解不開（例如檔案損壞）時用這台的基準
            latest = remoteBytes == null ? null : openQuietly(remoteBytes, restored);
            if (latest == null) {
                byte[] base = files.readBase(expectedId, location);
                latest = base == null ? null : openQuietly(base, restored);
            }
            byte[] out = bytes;
            if (RestoreMarks.apply(restored, Arrays.asList(current, latest), clock.instant())) {
                out = serializer.serialize(restored);
                ReadBack.verify(codec, out, restored);
            }
            // 同 write：備份之後遠端又被改過時不蓋掉（那一版不在剛才的安全備份裡；C-2）
            ensureUnchanged(location, remoteBytes);
            location.writeAll(out);
            String hash = Hashes.sha256(out);
            boolean localUpdated = updateLocalState(restored, location, out, hash);
            done = true;
            return new RestoreResult(restored, hash, safety, localUpdated);
        } finally {
            if (latest != null) {
                latest.close();
            }
            if (!done) {
                restored.close();
            }
        }
    }

    private Vault openQuietly(byte[] bytes, Vault keySource) {
        try {
            return codec.openWithKeyOf(bytes, keySource);
        } catch (VaultException e) {
            LOG.log(Level.INFO, "還原時解不開目前的內容，略過註記：{0}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * @param vaultId  保管庫 ID
     * @param location 遠端位置（基準以保管庫 ID＋位置區分，T-27）
     * @return 上次同步成功時的遠端位元組（{@code base/}，三方合併的基準，AC-07-8）；沒有或讀不到時 {@code null}
     */
    public byte[] readBase(UUID vaultId, VaultLocation location) {
        return files.readBase(vaultId, location);
    }

    /**
     * @param vaultId  保管庫 ID
     * @param location 遠端位置
     * @return 還沒寫回遠端的本機版本（{@code pending/}）；沒有或讀不到時 {@code null}
     */
    public byte[] readPending(UUID vaultId, VaultLocation location) {
        return files.readPending(vaultId, location);
    }

    /**
     * 把還沒寫回遠端的本機版本存進 {@code pending/}（加密的 KDBX，同一主密碼；Architecture §2.2）：離線或寫不進去、
     * 有衝突沒解決時，修改不會只留在記憶體。下次解鎖時以它當本機版本。
     *
     * @param vault    本機版本
     * @param location 遠端位置
     * @return 已寫入；失敗時只記錄類型（修改仍在記憶體）
     */
    public boolean writePending(Vault vault, VaultLocation location) {
        try {
            AtomicFiles.write(files.pending(vault.id(), location), serializer.serialize(vault));
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "無法寫入本機暫存：{0}", e.getClass().getName());
            return false;
        }
    }

    /**
     * 衝突沒解決時被上鎖（AC-08-5，T-18）：把對照畫面上的選擇存進 {@link LocalStore#conflictFile}。和 {@code pending/}
     * 一樣經 {@link VaultCodec} 加密（同一把金鑰與 KDF 參數的另一個 KDBX，內容放在唯一一筆條目的備註），絕不寫明文；
     * 本機的修改本身已在 {@code pending/}。
     *
     * @param keySource 開啟中的保管庫（提供金鑰與保管庫 ID）
     * @param location  遠端位置
     * @param content   選擇的內容（{@code core.sync.ConflictDraft} 的文字；不含欄位的值，只有雜湊）
     * @return 已寫入；失敗時只記錄類型（下次解鎖仍會回到衝突，只是選擇要重選）
     */
    public boolean writeConflictDraft(Vault keySource, VaultLocation location, String content) {
        return drafts.write(keySource, files.conflict(keySource.id(), location), content);
    }

    /**
     * @param keySource 開啟中的保管庫（用它的金鑰解開；鹽相同時 0 次 KDF）
     * @param location  遠端位置
     * @return {@link #writeConflictDraft} 存的內容；沒有、讀不到或解不開時 {@code null}（解不開的刪掉：只是選擇，
     *         不是使用者的資料，留著也用不到）
     */
    public String readConflictDraft(Vault keySource, VaultLocation location) {
        return drafts.read(keySource, files.conflict(keySource.id(), location));
    }

    /**
     * 解不開的 {@code pending/}（例如主密碼在別台裝置改過）改名成 {@code <檔名>.unusable} 留著，不刪除使用者的資料，
     * 也不再擋住之後的同步。
     *
     * @param vaultId  保管庫 ID
     * @param location 遠端位置
     */
    public void setAsidePending(UUID vaultId, VaultLocation location) {
        files.setAsidePending(vaultId, location);
    }

    /**
     * 遠端沒有要寫的（合併結果就是遠端那份、或第一次在這台裝置開啟）時，只把本機狀態更新成「已和遠端同步」：
     * {@code base/} ← {@code remoteBytes}、記下雜湊、刪除 {@code pending/}（步驟 7）。
     *
     * @param vault       內容和 {@code remoteBytes} 相同的保管庫
     * @param location    遠端位置
     * @param remoteBytes 遠端目前的位元組
     * @return 本機狀態都已更新
     */
    public boolean markSynced(Vault vault, VaultLocation location, byte[] remoteBytes) {
        return updateLocalState(vault, location, remoteBytes, Hashes.sha256(remoteBytes));
    }

    private boolean updateLocalState(Vault vault, VaultLocation location, byte[] bytes, String hash) {
        try {
            // base 直接存遠端成功寫入的同一份位元組（Architecture §2.2）：本身就是加密的 KDBX
            AtomicFiles.write(files.base(vault.id(), location), bytes);
            recent.updateRemoteHash(location.persistentId(), hash);
            Files.deleteIfExists(files.pending(vault.id(), location));
            // 已和遠端同步＝沒有衝突了：上鎖前留下的選擇（AC-08-5）一起刪
            Files.deleteIfExists(files.conflict(vault.id(), location));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "存檔成功，但本機狀態更新失敗：{0}", e.getClass().getName());
            return false;
        }
    }
}
