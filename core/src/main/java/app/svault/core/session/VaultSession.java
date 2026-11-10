package app.svault.core.session;

import app.svault.core.storage.BackupStore;
import app.svault.core.storage.Hashes;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.RestoreResult;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.storage.VaultLocation;
import app.svault.core.sync.ConflictDraft;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.MergeResult;
import app.svault.core.sync.Side;
import app.svault.core.sync.SyncReport;
import app.svault.core.sync.SyncService;
import app.svault.core.vault.KdfParams;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 解鎖狀態（Architecture §6）：目前開啟中的 {@link Vault} 與它的位置。一個 App 一個。
 *
 * <p>不另存主密碼位元組：{@link Vault} 已持有存檔與讀回驗證要用的金鑰材料（T-04）。上鎖＝{@link Vault#close()}
 * 清零並丟掉參照。自動上鎖（閒置、系統鎖定、切到背景；T-18）由平台外殼經 {@code VaultOps.lock} 呼叫 {@link #lock(Map)}。
 *
 * <p>同步（T-14）：存檔、解鎖後與回到前景的更新、衝突解決都經 {@link SyncService}（遠端有變更時先三方合併）。
 * 合併後開啟中的保管庫換成合併後的版本（舊的清零）。這裡也記著「這台有沒寫進遠端的修改」與還沒解決的衝突。
 *
 * <p>方法互斥執行：背景執行緒存檔時，UI 執行緒的上鎖會等存檔結束，不會在序列化途中清掉金鑰。
 */
public final class VaultSession {

    private static final Logger LOG = Logger.getLogger(VaultSession.class.getName());

    /** 解鎖狀態的通知（在呼叫 {@link #unlock}／{@link #lock()} 的執行緒上呼叫）。 */
    public interface Listener {

        /** 剛解鎖成功。 */
        default void onUnlocked() {
        }

        /** 剛上鎖；顯示保管庫內容的畫面應該離開。 */
        void onLocked();
    }

    private final SaveCoordinator saver;
    private final SyncService sync;
    private final RecentStore recent;
    private final Clock clock;
    // 通知中允許監聽者移除自己
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Vault vault;
    private VaultLocation location;
    /** 記憶體中有還沒寫進遠端的修改（存檔失敗、有衝突、解鎖時讀到 {@code pending/}）。 */
    private boolean dirty;
    /** 還沒解決的衝突（AC-08-3）與那時遠端的雜湊；沒有時 {@code null}。 */
    private MergeResult conflict;
    private String conflictHash;
    /** 解鎖時讀到的衝突暫存（{@link ConflictDraft} 的文字，AC-08-5）；沒有或已帶回時 {@code null}。 */
    private String resumeDraft;

    /**
     * @param codec  開啟保管庫用
     * @param saver  存檔用
     * @param recent 記錄最近開啟與遠端雜湊（AC-06-3）
     * @param clock  最後開啟時間、衝突解決的時間
     */
    public VaultSession(VaultCodec codec, SaveCoordinator saver, RecentStore recent, Clock clock) {
        this.saver = Objects.requireNonNull(saver, "saver");
        this.sync = new SyncService(Objects.requireNonNull(codec, "codec"), saver);
        this.recent = Objects.requireNonNull(recent, "recent");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 讀取並解鎖保管庫；已有開啟中的保管庫時先上鎖。成功後記進 {@code recent.json}（含遠端雜湊）。
     * 上次有沒寫進遠端的修改（{@code pending/}）時開啟的是那一份；和遠端的合併在之後的 {@link #refresh()}。
     *
     * @param location 保管庫位置
     * @param password 主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @throws VaultException 讀不到、主密碼錯誤、格式不支援或損壞；這時維持上鎖
     */
    public synchronized void unlock(VaultLocation location, byte[] password) throws VaultException {
        unlock(location, password, false);
    }

    /**
     * 同 {@link #unlock(VaultLocation, byte[])}；第一次丟 {@code CostlyKdfException} 時畫面顯示預估時間，使用者確認後
     * 用 {@code costlyConfirmed = true} 再呼叫一次（C-4，D-186）。
     *
     * @param location        保管庫位置
     * @param password        主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @param costlyConfirmed 使用者已確認成本過高的金鑰衍生
     * @throws VaultException 讀不到、主密碼錯誤、格式不支援、損壞，或沒確認而成本過高；這時維持上鎖
     */
    public synchronized void unlock(VaultLocation location, byte[] password, boolean costlyConfirmed)
            throws VaultException {
        lock();
        SyncService.Opened opened = sync.open(location, password, costlyConfirmed);
        this.vault = opened.vault();
        this.location = location;
        this.dirty = opened.dirty();
        // AC-08-5：上次衝突沒解決就上鎖時留下的選擇；refresh() 重新合併後由 takeResumedChoices() 帶回
        this.resumeDraft = saver.readConflictDraft(opened.vault(), location);
        remember(location, opened.vault(), saver.readBase(opened.vault().id(), location));
        for (Listener l : listeners) {
            l.onUnlocked();
        }
    }

    private void remember(VaultLocation where, Vault opened, byte[] synced) {
        remember(where, opened, synced == null ? null : Hashes.sha256(synced));
    }

    private void remember(VaultLocation where, Vault opened, String remoteHash) {
        try {
            recent.record(new RecentVault(where.persistentId(), where.displayName(), opened.id(),
                    clock.instant(), remoteHash));
        } catch (IOException e) {
            // 最近開啟清單只是方便，寫不進去不影響解鎖
            LOG.log(Level.WARNING, "無法更新最近開啟清單：{0}", e.getClass().getName());
        }
    }

    /**
     * 接手呼叫端已開啟的保管庫，當成剛解鎖：建立保管庫並經 {@link SaveCoordinator} 寫出第一版後用這個，
     * 不用再以主密碼 {@link #unlock} 一次（多算一次 KDF，手機約 1.9 秒；D-51）。已有開啟中的保管庫時先上鎖。
     * 成功後記進 {@code recent.json}，之後由這個物件負責上鎖：呼叫端不要再 {@code close()} 它。
     *
     * @param opened     開啟中的保管庫（內容要和 {@code location} 上的檔案相同）
     * @param location   保管庫位置
     * @param remoteHash {@code location} 目前位元組的 SHA-256（例如 {@link SaveResult#remoteHash()}）
     * @throws IllegalStateException {@code opened} 已上鎖；這時維持上鎖、不記錄
     */
    public synchronized void adopt(Vault opened, VaultLocation location, String remoteHash) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(remoteHash, "remoteHash");
        if (opened.isClosed()) {
            throw new IllegalStateException("保管庫已上鎖，不能接手");
        }
        if (opened != vault) {
            lock();
        }
        this.vault = opened;
        this.location = location;
        clearSyncState();
        remember(location, opened, remoteHash);
        for (Listener l : listeners) {
            l.onUnlocked();
        }
    }

    /** @return 有開啟中的保管庫 */
    public synchronized boolean isUnlocked() {
        return vault != null;
    }

    /**
     * @return 開啟中的保管庫（合併後會換成另一個物件：每次使用前重新取得，不要保留）
     * @throws IllegalStateException 已上鎖
     */
    public synchronized Vault vault() {
        requireUnlocked();
        return vault;
    }

    /**
     * @return 開啟中的保管庫的位置
     * @throws IllegalStateException 已上鎖
     */
    public synchronized VaultLocation location() {
        requireUnlocked();
        return location;
    }

    /**
     * @return 有還沒寫進遠端的修改（上一次存檔失敗、有衝突沒解決，或解鎖時讀到 {@code pending/}）
     */
    public synchronized boolean hasUnsavedChanges() {
        return vault != null && dirty;
    }

    /**
     * @return 還沒解決的衝突（衝突對照畫面 T-15／T-16 用 {@link MergeResult#conflicts()}）；沒有時為空
     */
    public synchronized Optional<MergeResult> conflict() {
        return Optional.ofNullable(conflict);
    }

    /**
     * 存檔（使用者的修改已在記憶體中的保管庫）：遠端有變更時先三方合併再寫（AC-07-1）。
     *
     * @return 結果（{@link SyncReport.Status#CONFLICT} 時沒有寫遠端，見 {@link #conflict()}）
     * @throws VaultException        存檔失敗（遠端維持原狀；修改已寫進 {@code pending/}，之後 {@link #refresh()} 重試）
     * @throws IllegalStateException 已上鎖
     */
    public synchronized SyncReport save() throws VaultException {
        requireUnlocked();
        dirty = true;
        return apply(sync.sync(vault, true, location, true));
    }

    /**
     * 整批寫入（T-33，模組的寫入入口 {@code ModuleVaultWriter}）：在複本上套用 {@code change} 再存檔一次，全部成功才換掉
     * 開啟中的保管庫；失敗或有衝突時記憶體、{@code pending/} 與同步狀態都維持原狀（{@link SyncService#commit}）。
     * 這台原本有沒寫出去的修改時一起寫出。
     *
     * @param change 對複本的修改（只改記憶體）；丟出的例外原樣傳出
     * @return 結果（{@link SyncReport#hasConflicts()} 時沒有寫遠端、什麼都沒套用）
     * @throws VaultException        存檔失敗（什麼都沒套用）
     * @throws IllegalStateException 已上鎖，或有還沒解決的衝突（要先在衝突對照畫面處理）
     */
    public synchronized SyncReport commit(Consumer<Vault> change) throws VaultException {
        requireUnlocked();
        if (conflict != null) {
            throw new IllegalStateException("有還沒解決的衝突");
        }
        SyncService.Outcome outcome = sync.commit(vault, change, location);
        if (!outcome.report().hasConflicts()) {
            Vault old = vault;
            vault = outcome.vault();
            old.close();
            clearSyncState();
        }
        return outcome.report();
    }

    /**
     * 偏弱的金鑰衍生升級成 S-Vault 預設並立刻存檔（L-4，D-187）。遠端剛好被改過而走合併時，寫回的是遠端那份的參數。
     * @return 存檔結果
     * @throws VaultException 存檔失敗（同 {@link #commit}）
     */
    public synchronized SyncReport upgradeKdf() throws VaultException {
        return commit(v -> v.useKdf(KdfParams.DEFAULT));
    }

    /**
     * 解鎖後、回到前景與自動重試的更新（AC-07-5）：遠端和上次同步時相同、這台也沒有要寫的時什麼都不做；遠端有變更就
     * 合併，這台有沒寫出去的修改就寫出去。
     *
     * @return 結果
     * @throws VaultException        讀寫遠端失敗（遠端維持原狀；有修改時已寫進 {@code pending/}）
     * @throws IllegalStateException 已上鎖
     */
    public synchronized SyncReport refresh() throws VaultException {
        requireUnlocked();
        return apply(sync.sync(vault, dirty, location, false));
    }

    /**
     * 套用使用者在衝突對照畫面的選擇（AC-08-2）後寫回。遠端在那之後又變了時重新合併一次（可能又有衝突）。
     *
     * @param choices 每筆衝突條目、每個欄位選哪一邊（{@code Conflict#choose} 可整筆選同一邊）
     * @return 結果
     * @throws VaultException           寫回失敗（修改已寫進 {@code pending/}）
     * @throws IllegalArgumentException 有衝突的欄位沒有選擇
     * @throws IllegalStateException    已上鎖，或沒有衝突
     */
    public synchronized SyncReport resolve(Map<UUID, Map<String, Side>> choices) throws VaultException {
        requireUnlocked();
        if (conflict == null) {
            throw new IllegalStateException("沒有要解決的衝突");
        }
        MergeResult resolved = conflict.resolve(choices, clock.instant());
        return apply(sync.resolve(vault, location, resolved, conflictHash));
    }

    private SyncReport apply(SyncService.Outcome outcome) {
        if (outcome.vault() != vault) {
            // 合併後的版本是另一個物件（套用在遠端那份上）；舊的清零
            Vault old = vault;
            vault = outcome.vault();
            old.close();
        }
        SyncReport report = outcome.report();
        if (report.hasConflicts()) {
            dirty = true;
            conflict = outcome.conflict();
            conflictHash = outcome.remoteHash();
        } else if (report.status() != SyncReport.Status.UP_TO_DATE) {
            clearSyncState();
        }
        return report;
    }

    /**
     * 解鎖中從備份還原（AC-09-2）：經 {@link SaveCoordinator#restore} 寫回選定的備份，再把開啟中的保管庫換成還原後的版本
     * （舊的清零）。不發 {@link Listener#onLocked()}／{@link Listener#onUnlocked()}：呼叫端自己重新載入畫面，
     * 不會先被帶回解鎖畫面。失敗時維持原本開啟中的保管庫。
     *
     * @param backup 要還原的備份（{@link SaveCoordinator#listBackups} 的一筆）
     * @return 結果；其中的保管庫已由這個物件接手，呼叫端不要 {@code close()}
     * @throws VaultException        還原失敗（遠端維持原狀），例如 {@code CorruptBackupException}
     * @throws IllegalStateException 已上鎖
     */
    public synchronized RestoreResult restore(BackupStore.Backup backup) throws VaultException {
        requireUnlocked();
        RestoreResult result = saver.restore(vault, location, backup);
        Vault old = vault;
        vault = result.vault();
        old.close();
        // 還原＝這台不和遠端合併，結果就是備份的內容（Architecture §5）；沒寫出去的修改與衝突一起放掉
        clearSyncState();
        resumeDraft = null;
        return result;
    }

    /**
     * @return 解鎖時讀到上次衝突沒解決就上鎖留下的選擇、而且現在（{@link #refresh()} 之後）仍有衝突時，可以沿用的選擇
     *         （AC-08-5：下次解鎖回到衝突對照畫面）。只回傳一次，之後由畫面的 Banner「處理」開啟；還沒有衝突（例如讀不到
     *         遠端）時不算用掉
     */
    public synchronized Optional<Map<UUID, Map<String, Side>>> takeResumedChoices() {
        if (resumeDraft == null || conflict == null) {
            return Optional.empty();
        }
        String draft = resumeDraft;
        resumeDraft = null;
        return Optional.of(ConflictDraft.decode(draft, ConflictView.of(conflict)));
    }

    /** 上鎖：清零金鑰材料、丟掉保管庫並通知；已上鎖時不做事。有沒解決的衝突時同 {@link #lock(Map)}（沒有新的選擇）。 */
    public synchronized void lock() {
        lock(Map.of());
    }

    /**
     * 上鎖。有沒解決的衝突時，先把對照畫面上的選擇經 {@link SaveCoordinator#writeConflictDraft} 加密保存（AC-08-5；本機的
     * 修改已在 {@code pending/}），下次解鎖後 {@link #takeResumedChoices()} 帶回。
     *
     * @param conflictChoices 對照畫面上目前的選擇（可能還沒選完；沒開過時傳空的）
     */
    public synchronized void lock(Map<UUID, Map<String, Side>> conflictChoices) {
        if (vault == null) {
            return;
        }
        if (conflict != null) {
            ConflictView view = ConflictView.of(conflict);
            Map<UUID, Map<String, Side>> chosen = conflictChoices;
            if (resumeDraft != null) {
                // 解鎖後還沒回到對照畫面就又上鎖：上次留下的選擇不能因此不見，畫面上的選擇優先
                chosen = new LinkedHashMap<>(ConflictDraft.decode(resumeDraft, view));
                chosen.putAll(conflictChoices);
            }
            saver.writeConflictDraft(vault, location, ConflictDraft.encode(view, chosen));
        }
        // 先清零再丟參照：Vault.close() 會把複合金鑰與已衍生的金鑰陣列填 0（T-04）
        vault.close();
        vault = null;
        location = null;
        resumeDraft = null;
        clearSyncState();
        for (Listener l : listeners) {
            l.onLocked();
        }
    }

    /**
     * @param listener 解鎖狀態的通知對象
     */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * @param listener 不再通知的對象
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void clearSyncState() {
        dirty = false;
        conflict = null;
        conflictHash = null;
    }

    private void requireUnlocked() {
        if (vault == null) {
            throw new IllegalStateException("保管庫已上鎖");
        }
    }
}
