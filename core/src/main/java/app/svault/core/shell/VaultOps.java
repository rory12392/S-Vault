package app.svault.core.shell;

import app.svault.core.session.VaultSession;
import app.svault.core.storage.VaultIoException;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 條目畫面的寫入操作（US-02）。同步執行，呼叫端放在保管庫的背景執行緒（desktop {@code VaultWorker}、android
 * {@code SvaultApplication.worker()}；和上鎖同一條，上鎖會等存檔寫完）。兩平台共用（T-14 從雙胞胎檔案搬來）。
 *
 * <p>每個操作都是「{@link VaultEditor} 改記憶體中的保管庫 → {@link VaultSession#save()}（遠端有變更時先三方合併，
 * 再經 SaveCoordinator：備份 → 暫存 → 讀回驗證 → 取代）」，寫入不走其他路徑（CLAUDE.md 安全底線）。存檔失敗時修改留在
 * 記憶體並寫進 {@code pending/}，結果帶著失敗原因讓畫面提示「尚未同步，稍後自動重試」；畫面每
 * {@link #RETRY_INTERVAL} 呼叫一次 {@link #refresh()}，下一次操作或「再試一次」也會一起存進去。
 *
 * <p>解鎖後與回到前景（desktop 視窗取得焦點、android {@code onResume}）呼叫 {@link #refresh()} 讀遠端的最新版本
 * （AC-07-5）；回到前景的檢查至少間隔 {@link #FOREGROUND_INTERVAL}（Architecture §5，{@link #refreshDue()}）。
 */
public final class VaultOps {

    /** 回到前景時兩次檢查遠端的最短間隔（Architecture §5）。 */
    public static final Duration FOREGROUND_INTERVAL = Duration.ofSeconds(30);
    /** 存檔失敗（離線）後自動重試的間隔。 */
    public static final Duration RETRY_INTERVAL = Duration.ofSeconds(60);

    private static final Logger LOG = Logger.getLogger(VaultOps.class.getName());

    /**
     * 操作的結果。
     *
     * @param data      操作後的內容（存檔失敗時也是修改後的內容）；{@link #refresh()} 沒有任何變更時 {@code null}
     *                  （畫面不必重畫）
     * @param subject   操作對象（新增的條目、群組等）；沒有時 {@code null}
     * @param recycled  刪除時移到資源回收桶為 {@code true}；檔案停用回收桶而永久刪除時為 {@code false}
     * @param saveError 存檔或同步失敗的原因（離線等，修改已寫進 {@code pending/}）；成功時 {@code null}
     * @param sync      同步的結果（合併了幾筆、通知、衝突）；失敗或沒有存檔時 {@code null}
     */
    public record Outcome(ScreenData data, UUID subject, boolean recycled, VaultException saveError,
                          SyncReport sync) {

        /** @return 修改已寫進檔案（失敗或有衝突沒寫時 {@code false}） */
        public boolean saved() {
            return saveError == null && (sync == null || !sync.hasConflicts());
        }

        /**
         * @return 合併了另一台裝置的修改，要顯示「已更新為最新資料（合併 n 筆）」（AC-07-5）。遠端只是重存過、條目沒有
         *         不同（例如另一個工具開了又存）時不顯示
         */
        public boolean merged() {
            return sync != null && sync.status() == SyncReport.Status.MERGED
                    && (sync.changedEntries() > 0 || !sync.notices().isEmpty());
        }

        /**
         * 畫面在 UI 執行緒用結果更新同步狀態，不必再問 {@link VaultSession}（它在背景同步時被鎖住，會卡住畫面）。
         *
         * @param before 操作前的狀態
         * @return 操作後的狀態：失敗是 {@link SyncState#PENDING}；沒有存檔（內容沒變）維持原狀
         */
        public SyncState state(SyncState before) {
            if (saveError != null) {
                return SyncState.PENDING;
            }
            if (sync == null) {
                return before;
            }
            return sync.hasConflicts() ? SyncState.CONFLICT : SyncState.SYNCED;
        }
    }

    /** 畫面的同步狀態（同步 Banner）。 */
    public enum SyncState {
        /** 已和遠端同步。 */
        SYNCED,
        /** 有還沒寫進遠端的修改，稍後自動重試（warning Banner「尚未同步，稍後自動重試」）。 */
        PENDING,
        /** 有衝突要處理（error Banner「有 n 筆衝突需要處理」）。 */
        CONFLICT
    }

    private final VaultSession session;
    private final Clock clock;
    /** 上一次讀遠端的時間（存檔或更新；只在背景執行緒寫入，畫面執行緒讀）。 */
    private volatile Instant lastSync = Instant.MIN;

    /**
     * @param session 解鎖狀態
     * @param clock   修改時間的來源，也決定回到前景的檢查間隔
     */
    public VaultOps(VaultSession session, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return 目前的內容（解鎖後第一次顯示） */
    public ScreenData load() {
        return ScreenData.of(session.vault());
    }

    /** @return 有沒寫進檔案的修改 */
    public boolean hasUnsavedChanges() {
        return session.hasUnsavedChanges();
    }

    /** @return 目前的同步狀態 */
    public SyncState syncState() {
        if (session.conflict().isPresent()) {
            return SyncState.CONFLICT;
        }
        return session.hasUnsavedChanges() ? SyncState.PENDING : SyncState.SYNCED;
    }

    /** @return 還沒解決的衝突筆數（{@link SyncState#CONFLICT} 時大於 0） */
    public int conflictCount() {
        return session.conflict().map(r -> r.conflicts().size()).orElse(0);
    }

    /**
     * @return 還沒解決的衝突要在對照畫面顯示的內容；沒有衝突時為空
     */
    public Optional<ConflictView> conflicts() {
        return session.conflict().map(ConflictView::of);
    }

    /**
     * 套用使用者在衝突對照畫面的選擇並寫回（AC-08-2）。遠端在那之後又變了時重新合併，可能又有衝突
     * （{@code sync.hasConflicts()}）；寫回失敗（離線）時 {@code saveError} 有值，衝突還在、稍後自動重試時會再合併一次。
     *
     * @param choices 每筆衝突條目、每個欄位選哪一邊（{@link ConflictChoices#toMap()}）
     * @return 結果
     * @throws IllegalArgumentException 有衝突的欄位沒有選擇
     * @throws IllegalStateException    已上鎖，或已經沒有衝突（例如在這之間上鎖又解鎖）
     */
    public Outcome resolve(Map<UUID, Map<String, Side>> choices) {
        lastSync = clock.instant();
        try {
            SyncReport report = session.resolve(choices);
            return new Outcome(load(), null, false, null, report);
        } catch (VaultException e) {
            logFailure("衝突解決後寫回失敗", e);
            return new Outcome(load(), null, false, e, null);
        }
    }

    /**
     * @return 距離上一次讀遠端已超過 {@link #FOREGROUND_INTERVAL}（回到前景時要不要 {@link #refresh()}）
     */
    public boolean refreshDue() {
        return !clock.instant().isBefore(lastSync.plus(FOREGROUND_INTERVAL));
    }

    /**
     * 讀遠端的最新版本並合併；這台有沒寫出去的修改時一併寫出（解鎖後、回到前景、自動重試；AC-07-5）。
     *
     * @return 結果；沒有任何變更時 {@code data} 是 {@code null}；有衝突時 {@code sync.hasConflicts()}
     */
    public Outcome refresh() {
        lastSync = clock.instant();
        try {
            SyncReport report = session.refresh();
            ScreenData data = report.status() == SyncReport.Status.UP_TO_DATE ? null : load();
            return new Outcome(data, null, false, null, report);
        } catch (VaultException e) {
            logFailure("同步失敗", e);
            return new Outcome(load(), null, false, e, null);
        }
    }

    /**
     * 新增條目（AC-02-1）。
     *
     * @param groupId 所屬群組
     * @param draft   內容
     * @return 結果；{@code subject} 是新條目
     */
    public Outcome addEntry(UUID groupId, EntryDraft draft) {
        return change(editor -> editor.addEntry(groupId, draft));
    }

    /**
     * 修改條目（AC-02-2），群組不同時一併移動（AC-02-4）。內容與群組都沒變時不寫檔。
     *
     * @param entryId   條目
     * @param draft     新內容
     * @param fromGroup 編輯開始時所在的群組
     * @param groupId   選定的群組
     * @return 結果；{@code subject} 是這筆條目
     */
    public Outcome updateEntry(UUID entryId, EntryDraft draft, UUID fromGroup, UUID groupId) {
        VaultEditor editor = editor();
        boolean changed = editor.updateEntry(entryId, draft);
        if (!fromGroup.equals(groupId)) {
            editor.moveEntry(entryId, groupId);
            changed = true;
        }
        if (!changed && !session.hasUnsavedChanges()) {
            return new Outcome(load(), entryId, false, null, null);
        }
        return save(entryId, false);
    }

    /**
     * @param entryId 條目
     * @param groupId 目標群組（desktop 的拖放，AC-02-4）
     * @return 結果
     */
    public Outcome moveEntry(UUID entryId, UUID groupId) {
        return change(editor -> {
            editor.moveEntry(entryId, groupId);
            return entryId;
        });
    }

    /**
     * 刪除條目：移到資源回收桶（AC-02-3）。
     *
     * @param entryId 條目
     * @return 結果；{@code recycled=false} 表示檔案停用回收桶、已永久刪除
     */
    public Outcome recycleEntry(UUID entryId) {
        boolean recycled = editor().recycleEntry(entryId);
        return save(entryId, recycled);
    }

    /**
     * @param entryId 資源回收桶裡的條目
     * @return 結果
     */
    public Outcome restoreEntry(UUID entryId) {
        return change(editor -> {
            editor.restoreEntry(entryId);
            return entryId;
        });
    }

    /**
     * @param entryId 資源回收桶裡的條目
     * @return 結果
     */
    public Outcome deleteEntryPermanently(UUID entryId) {
        return change(editor -> {
            editor.deleteEntryPermanently(entryId);
            return entryId;
        });
    }

    /**
     * @param parentId 上層群組
     * @param name     名稱（前後空白會去掉）
     * @return 結果；{@code subject} 是新群組
     */
    public Outcome addGroup(UUID parentId, String name) {
        return change(editor -> editor.addGroup(parentId, Texts.strip(name)));
    }

    /**
     * @param groupId 群組
     * @param name    新名稱（前後空白會去掉）
     * @return 結果
     */
    public Outcome renameGroup(UUID groupId, String name) {
        return change(editor -> {
            editor.renameGroup(groupId, Texts.strip(name));
            return groupId;
        });
    }

    /**
     * 刪除群組（連同條目與子群組移到資源回收桶，AC-02-4）。
     *
     * @param groupId 群組
     * @return 結果；{@code recycled=false} 表示已永久刪除
     */
    public Outcome recycleGroup(UUID groupId) {
        boolean recycled = editor().recycleGroup(groupId);
        return save(groupId, recycled);
    }

    /**
     * @param groupId 資源回收桶裡的群組
     * @return 結果
     */
    public Outcome restoreGroup(UUID groupId) {
        return change(editor -> {
            editor.restoreGroup(groupId);
            return groupId;
        });
    }

    /**
     * @param groupId 資源回收桶裡的群組
     * @return 結果
     */
    public Outcome deleteGroupPermanently(UUID groupId) {
        return change(editor -> {
            editor.deleteGroupPermanently(groupId);
            return groupId;
        });
    }

    /** @return 再存一次（上一次存檔失敗後的「再試一次」、desktop 的 {@code Ctrl+S}） */
    public Outcome retrySave() {
        return save(null, false);
    }

    /**
     * 上鎖（AC-12-3、AC-08-5；手動與自動上鎖都走這裡，放在保管庫的背景執行緒）：先存編輯畫面上的內容（名稱空白不存，
     * 同「儲存」的檢查）與之前存檔失敗的修改，再 {@link VaultSession#lock(Map)}（有衝突時連同選擇加密保存）。離線時修改留在
     * {@code pending/}，照常上鎖。
     *
     * @param edit            編輯畫面上還沒儲存的內容；不在編輯或沒有修改時 {@code null}
     * @param conflictChoices 衝突對照畫面上目前的選擇；沒開過時傳空的
     */
    public void lock(PendingEdit edit, Map<UUID, Map<String, Side>> conflictChoices) {
        if (edit != null && session.isUnlocked() && !Texts.strip(edit.draft().title()).isEmpty()) {
            try {
                if (edit.entryId() == null) {
                    addEntry(edit.group(), edit.draft());
                } else {
                    updateEntry(edit.entryId(), edit.draft(), edit.fromGroup(), edit.group());
                }
            } catch (RuntimeException e) {
                // 例如編輯中的條目已被合併刪除：不存這份內容，照常上鎖
                LOG.log(Level.WARNING, "上鎖前無法存入編輯中的內容：{0}", e.getClass().getName());
            }
        }
        saveBeforeLock();
        session.lock(conflictChoices);
    }

    /**
     * 解鎖後第一次 {@link #refresh()} 之後：上次衝突沒解決就上鎖、現在仍有衝突時，回到對照畫面要帶入的選擇（AC-08-5）。
     *
     * @return 要開對照畫面時的選擇；不需要回到對照畫面時為空
     */
    public Optional<ConflictChoices> resumeConflicts() {
        return session.takeResumedChoices()
                .flatMap(preset -> conflicts().map(view -> new ConflictChoices(view, preset)));
    }

    /**
     * 上鎖前把沒寫進檔案的修改存一次（AC-12-3）；失敗只記錄類型，照常上鎖。
     */
    public void saveBeforeLock() {
        if (!session.hasUnsavedChanges()) {
            return;
        }
        try {
            // 有衝突時同樣不寫遠端（AC-08-3），修改已在 pending/，下次解鎖回來
            session.save();
        } catch (VaultException | RuntimeException e) {
            LOG.log(Level.WARNING, "上鎖前存檔失敗：{0}", e.getClass().getName());
        }
    }

    private Outcome change(Function<VaultEditor, UUID> edit) {
        UUID subject = edit.apply(editor());
        return save(subject, true);
    }

    private Outcome save(UUID subject, boolean recycled) {
        lastSync = clock.instant();
        VaultException failure = null;
        SyncReport report = null;
        try {
            report = session.save();
        } catch (VaultException e) {
            failure = e;
            logFailure("存檔失敗", e);
        }
        return new Outcome(load(), subject, recycled, failure, report);
    }

    private static void logFailure(String what, VaultException e) {
        // 類型、原因分類與訊息（VaultIoException 的訊息只有檔名與步驟，不含條目內容；CONVENTIONS §3）
        LOG.log(Level.WARNING, what + "：{0} {1} {2}", new Object[] {e.getClass().getName(),
                e instanceof VaultIoException io ? io.reason() : "",
                e instanceof VaultIoException ? e.getMessage() : ""});
    }

    private VaultEditor editor() {
        return new VaultEditor(session.vault(), clock);
    }
}
