package app.svault.core.sync;

import app.svault.core.storage.Hashes;
import app.svault.core.storage.RemoteChangedException;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.storage.VaultIoException;
import app.svault.core.storage.VaultLocation;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.MergeWriter;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import app.svault.core.vault.VaultSnapshot;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 存檔與同步的步驟 1–2（Architecture §5；US-07）：讀遠端、和 {@code base/} 比對、有變更就三方合併，再交給
 * {@link SaveCoordinator#write} 做步驟 3–7。解鎖、存檔、回到前景的更新、自動重試、衝突解決都走這裡。
 *
 * <p>「遠端有沒有變」以遠端位元組的 SHA-256 和 {@code base/} 位元組的 SHA-256 比對（{@code base/} 存的就是上次同步成功時
 * 遠端的那一份位元組）；不需要解密就能判斷。三份保管庫：基準 B＝{@code base/}；本機 L＝這台有沒寫出去的修改時是記憶體
 * 中的版本，沒有時就是 B（這台自上次同步後沒改過）；遠端 R。合併套用在 R 上（Architecture §4 第 7 點）。
 *
 * <p>這台的修改還沒寫進遠端時（離線、寫不進去、有衝突），一律先把本機版本寫進 {@code pending/} 再回報，修改不會只留在
 * 記憶體。同步執行，呼叫端放在背景執行緒；不是執行緒安全的，由 {@code VaultSession} 串起來。
 */
public final class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class.getName());

    /** 寫回前發現遠端又被改過時，連同第一次最多同步幾次（C-2）。 */
    static final int MAX_SYNC_ATTEMPTS = 3;

    /**
     * 解鎖的結果。
     *
     * @param vault 要顯示的版本：有 {@code pending/} 時是它（這台還沒寫出去的修改），否則是遠端
     * @param dirty 這台有還沒寫進遠端的修改（有 {@code pending/}）
     */
    public record Opened(Vault vault, boolean dirty) {
    }

    /**
     * 一次同步的結果。
     *
     * @param vault      之後要用的保管庫：合併後換成套用了合併結果的遠端那份，否則是傳入的本機那份
     * @param report     給畫面的結果
     * @param conflict   有衝突時的合併結果（{@code MergeResult#resolve} 用）；沒有時 {@code null}
     * @param remoteHash 這次比對的遠端位元組的 SHA-256（衝突解決時確認遠端沒再變）
     */
    public record Outcome(Vault vault, SyncReport report, MergeResult conflict, String remoteHash) {
    }

    private final VaultCodec codec;
    private final SaveCoordinator saver;
    /** {@link #commit} 期間為 {@code false}：失敗時不把那一份寫進 {@code pending/}（由 {@code VaultSession} 串起來，單一執行緒）。 */
    private boolean keepPendingOnFailure = true;

    /**
     * @param codec 開啟遠端、基準與本機暫存
     * @param saver 步驟 3–7 與 {@code base/}、{@code pending/} 的讀寫
     */
    public SyncService(VaultCodec codec, SaveCoordinator saver) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.saver = Objects.requireNonNull(saver, "saver");
    }

    /**
     * 解鎖：以主密碼開啟遠端（1 次 KDF）。有 {@code pending/} 時改用它當本機版本（AC-08-5 的基礎：上次沒寫出去的修改
     * 不遺失）；這台第一次開這個保管庫時，遠端就是基準。合併不在這裡做：畫面顯示後再 {@link #sync}（DESIGN §6 的
     * 「正在同步最新資料…」），所以遠端沒變時的解鎖只有這一次 KDF（PRD §3 的 2 秒）。
     *
     * @param location 遠端位置
     * @param password 主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @return 開啟的版本
     * @throws VaultException 讀不到、主密碼錯誤、格式不支援或損壞
     */
    public Opened open(VaultLocation location, byte[] password) throws VaultException {
        return open(location, password, false);
    }

    /**
     * 同 {@link #open(VaultLocation, byte[])}；{@code costlyConfirmed} 是使用者已確認成本過高的金鑰衍生（C-4）。
     *
     * @param location        遠端位置
     * @param password        主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @param costlyConfirmed 使用者已確認
     * @return 開啟的版本
     * @throws VaultException 讀不到、主密碼錯誤、格式不支援、損壞，或沒確認而成本過高（{@code CostlyKdfException}）
     */
    public Opened open(VaultLocation location, byte[] password, boolean costlyConfirmed) throws VaultException {
        byte[] bytes = location.readAll();
        Vault remote = codec.open(bytes, password, costlyConfirmed);
        boolean handedOver = false;
        try {
            UUID id = remote.id();
            byte[] pending = saver.readPending(id, location);
            if (pending != null) {
                Vault local = openQuietly(pending, remote, false);
                if (local != null && local.id().equals(id)) {
                    handedOver = true;
                    remote.close();
                    return new Opened(local, true);
                }
                if (local != null) {
                    local.close();
                }
                LOG.warning("本機暫存解不開或不是這個保管庫，移到一旁");
                saver.setAsidePending(id, location);
            }
            if (saver.readBase(id, location) == null) {
                // 第一次在這台裝置開啟（或基準遺失）：這台沒有自己的修改，遠端就是同步點
                saver.markSynced(remote, location, bytes);
            }
            handedOver = true;
            return new Opened(remote, false);
        } finally {
            if (!handedOver) {
                remote.close();
            }
        }
    }

    /**
     * 同步一次（Architecture §5 的步驟 1–7）。
     *
     * @param local    開啟中的保管庫（這台的內容）
     * @param dirty    {@code local} 有還沒寫進遠端的修改
     * @param location 遠端位置
     * @param save     {@code true}：存檔（遠端不存在時照樣寫出）；{@code false}：解鎖後、回到前景、自動重試的更新
     *                 （遠端不存在時丟 {@code NOT_FOUND}，不自動重建檔案）
     * @return 結果；{@link Outcome#vault()} 不是 {@code local} 時，{@code local} 由呼叫端上鎖丟掉
     * @throws VaultException 讀寫遠端失敗等（{@code dirty} 時本機版本已寫進 {@code pending/}；遠端沒有被改動）
     */
    public Outcome sync(Vault local, boolean dirty, VaultLocation location, boolean save) throws VaultException {
        // 合併到寫回之間遠端又被別人改了（SaveCoordinator 寫入前重讀發現）：遠端沒動，用新的遠端重來一次（C-2）
        for (int attempt = 1; ; attempt++) {
            try {
                return syncOnce(local, dirty, location, save);
            } catch (RemoteChangedException e) {
                if (attempt >= MAX_SYNC_ATTEMPTS) {
                    throw e;
                }
                LOG.info("遠端在合併之後又被改過，重新同步");
            }
        }
    }

    private Outcome syncOnce(Vault local, boolean dirty, VaultLocation location, boolean save) throws VaultException {
        byte[] remoteBytes;
        try {
            if (!location.exists()) {
                if (!save) {
                    throw new VaultIoException(VaultIoException.Reason.NOT_FOUND, "遠端檔案不存在");
                }
                SaveResult written = saver.write(local, location, null);
                return new Outcome(local, SyncReport.of(SyncReport.Status.SAVED), null, written.remoteHash());
            }
            // 1. 讀遠端，和上次同步的版本比對
            remoteBytes = location.readAll();
        } catch (VaultException e) {
            keepPending(local, dirty, location);
            throw e;
        }
        String remoteHash = Hashes.sha256(remoteBytes);
        byte[] baseBytes = saver.readBase(local.id(), location);
        if (baseBytes != null && remoteHash.equals(Hashes.sha256(baseBytes))) {
            if (!dirty) {
                return new Outcome(local, SyncReport.of(SyncReport.Status.UP_TO_DATE), null, remoteHash);
            }
            write(local, local, location, remoteBytes);
            return new Outcome(local, SyncReport.of(SyncReport.Status.SAVED), null, remoteHash);
        }
        // 2. 遠端有變更（或沒有基準）：解開遠端與基準，三方合併
        return merge(local, dirty, location, remoteBytes, remoteHash, baseBytes);
    }

    /**
     * 套用使用者在衝突對照畫面的選擇（AC-08-2；T-15／T-16 的畫面呼叫）。遠端在衝突之後又變了時，改成重新合併一次
     * （可能又有衝突）。
     *
     * @param local      開啟中的保管庫（產生衝突時的那一份）
     * @param location   遠端位置
     * @param resolved   {@code MergeResult#resolve} 的結果（沒有衝突）
     * @param remoteHash 產生衝突時遠端的雜湊（{@link Outcome#remoteHash()}）
     * @return 結果
     * @throws VaultException 讀寫遠端失敗等（本機版本已寫進 {@code pending/}）
     */
    public Outcome resolve(Vault local, VaultLocation location, MergeResult resolved, String remoteHash)
            throws VaultException {
        byte[] remoteBytes;
        try {
            remoteBytes = location.readAll();
        } catch (VaultException e) {
            keepPending(local, true, location);
            throw e;
        }
        if (!Hashes.sha256(remoteBytes).equals(remoteHash)) {
            return sync(local, true, location, true);
        }
        Vault remote = openRemote(local, location, remoteBytes);
        try {
            return finish(local, local, remote, location, remoteBytes, remoteHash, resolved);
        } catch (RemoteChangedException e) {
            // 和上面「衝突之後遠端又變了」相同：重新合併一次（C-2）
            return sync(local, true, location, true);
        }
    }

    private Outcome merge(Vault local, boolean dirty, VaultLocation location, byte[] remoteBytes, String remoteHash,
                          byte[] baseBytes) throws VaultException {
        Vault remote = openRemote(local, location, remoteBytes);
        // 基準只在這個方法裡用（快照、沒有修改時當本機版本），不封存密碼；finally 上鎖（T-29，D-101 修訂）
        Vault base = baseBytes == null ? null : openQuietly(baseBytes, local, true);
        try {
            if (base != null && !base.id().equals(remote.id())) {
                base.close();
                base = null;
            }
            // 這台沒有修改時，本機版本就是基準（記憶體中的可能已是解鎖時讀到的遠端，拿它比會算不出「合併 n 筆」）
            Vault mine = dirty || base == null ? local : base;
            // 快照要解開每個受保護的欄位與附件（2,000 筆在手機上每份約 0.3 秒）：同一份只算一次
            VaultSnapshot baseSnapshot = base == null ? null : base.snapshot();
            VaultSnapshot mineSnapshot = mine == base ? baseSnapshot : mine.snapshot();
            MergeResult result = ThreeWayMerger.merge(baseSnapshot, mineSnapshot, remote.snapshot());
            if (result.hasConflicts()) {
                // AC-08-3：不寫遠端；這台的修改進 pending/，等使用者選
                remote.close();
                keepPending(local, true, location);
                return new Outcome(local, SyncReport.of(result), result, remoteHash);
            }
            return finish(local, mine, remote, location, remoteBytes, remoteHash, result);
        } finally {
            if (base != null) {
                base.close();
            }
        }
    }

    // 套用合併結果到遠端那份；有要寫的就經 SaveCoordinator 寫回，沒有就只更新本機狀態。成功時交出 remote
    private Outcome finish(Vault local, Vault mine, Vault remote, VaultLocation location, byte[] remoteBytes,
                           String remoteHash, MergeResult result) throws VaultException {
        boolean handedOver = false;
        try {
            MergeWriter.apply(remote, mine, result.plan());
            String hash = remoteHash;
            if (result.plan().isEmpty()) {
                saver.markSynced(remote, location, remoteBytes);
            } else {
                hash = write(local, remote, location, remoteBytes).remoteHash();
            }
            handedOver = true;
            return new Outcome(remote, SyncReport.of(result), null, hash);
        } finally {
            if (!handedOver) {
                remote.close();
            }
        }
    }

    // 步驟 3–7；失敗時這台的修改進 pending/（寫的是本機那份：下次解鎖再合併一次，不會遺失遠端的修改）
    private SaveResult write(Vault local, Vault target, VaultLocation location, byte[] remoteBytes)
            throws VaultException {
        try {
            return saver.write(target, location, remoteBytes);
        } catch (VaultException | RuntimeException e) {
            keepPending(local, true, location);
            throw e;
        }
    }

    private Vault openRemote(Vault local, VaultLocation location, byte[] remoteBytes) throws VaultException {
        Vault remote;
        try {
            // 同一主密碼：S-Vault 寫的檔案鹽不變，0 次 KDF；KeePassXC 存檔會換鹽，1 次
            remote = codec.openWithKeyOf(remoteBytes, local);
        } catch (VaultException e) {
            keepPending(local, true, location);
            throw e;
        }
        if (!remote.id().equals(local.id())) {
            remote.close();
            keepPending(local, true, location);
            throw new CorruptVaultException("遠端已換成另一個保管庫");
        }
        return remote;
    }

    /** @param mergeBase 只為合併暫時開的基準（{@link VaultCodec#openForMerge}，不封存）；{@code pending/} 會繼續開著，要封存 */
    private Vault openQuietly(byte[] bytes, Vault keySource, boolean mergeBase) {
        try {
            return mergeBase ? codec.openForMerge(bytes, keySource) : codec.openWithKeyOf(bytes, keySource);
        } catch (VaultException e) {
            // 基準或暫存解不開（主密碼在別台改過、檔案壞了）：當成沒有，合併改用「沒有基準」的規則，資料不會遺失
            LOG.log(Level.WARNING, "本機的基準或暫存解不開：{0}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 整批寫入（T-33，模組的寫入入口）：在 {@code current} 的複本上套用 {@code change}，以複本存檔一次。全部成功才算數：
     * 套用丟例外、存檔失敗或合併遇到衝突時丟掉複本，{@code current} 與 {@code pending/} 都維持原狀（不像 {@link #sync}
     * 會把這一份寫進 {@code pending/}），不留下半批。
     *
     * @param current  開啟中的保管庫（不會被修改）
     * @param change   對複本的修改（只改記憶體）
     * @param location 遠端位置
     * @return 成功時 {@link Outcome#vault()} 是寫出的那一份（複本，或合併後的遠端那份），由呼叫端接手並上鎖
     *         {@code current}；有衝突時是 {@code current}（沒有寫遠端）
     * @throws VaultException 讀寫遠端失敗等（遠端、{@code current} 與 {@code pending/} 都沒有被改動）
     */
    public Outcome commit(Vault current, Consumer<Vault> change, VaultLocation location) throws VaultException {
        byte[] bytes = codec.serialize(current);
        // 同一把金鑰與鹽：0 次 KDF
        Vault copy = codec.openWithKeyOf(bytes, current);
        boolean handedOver = false;
        keepPendingOnFailure = false;
        try {
            change.accept(copy);
            Outcome outcome = sync(copy, true, location, true);
            if (outcome.report().hasConflicts()) {
                return new Outcome(current, outcome.report(), null, outcome.remoteHash());
            }
            handedOver = outcome.vault() == copy;
            return outcome;
        } finally {
            keepPendingOnFailure = true;
            if (!handedOver) {
                copy.close();
            }
        }
    }

    private void keepPending(Vault local, boolean dirty, VaultLocation location) {
        if (keepPendingOnFailure && dirty && !local.isClosed()) {
            saver.writePending(local, location);
        }
    }
}
